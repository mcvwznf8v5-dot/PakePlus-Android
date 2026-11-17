package com.app.pakeplus

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * 本地漫画库：导入、书架、封面、按页读取。
 *
 * 设计要点：
 *  - 导入时把压缩包**复制**到 App 私有目录（files/books/<id>/book.zip），
 *    之后原文件被删除、U 盘拔掉都不影响阅读，也能真离线。
 *  - 读页用 java.util.zip.ZipFile：它只解析中央目录，取第 N 页是"随机定位 +
 *    解压这一条"，内存里只有当前这一页，所以几 G 的包也不会 OOM。
 *  - 元数据存在 files/books/books.json，页面通过 window.ComicLib 这个 JS 接口访问。
 */
object ComicLib {

    private const val DIR_BOOKS = "books"
    private const val META_FILE = "books.json"
    private const val COVER_WIDTH = 480
    private const val THUMB_WIDTH = 160
    private const val COPY_BUFFER = 256 * 1024

    /** 所有磁盘操作串行化，避免同时导入/删除时互相踩 */
    private val io = Executors.newSingleThreadExecutor()

    private val metaLock = Any()
    private val zipLock = Any()

    private var appContext: Context? = null
    private var activityRef: ComponentActivity? = null
    private var webViewRef: WebView? = null
    private var picker: ActivityResultLauncher<Array<String>>? = null

    @Volatile
    private var importing = false

    @Volatile
    private var importTitle = ""

    @Volatile
    private var importPercent = 0

    // 当前打开的书（避免每翻一页都重新解析中央目录）
    private var openId: String? = null
    private var openZip: ZipFile? = null
    private var openNames: List<String> = emptyList()

    /** 页栏缩略图缓存，最多留 40 张 */
    private val thumbCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > 40
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 必须在 MainActivity.onCreate 里调用（registerForActivityResult 只能在这个时机）。
     */
    fun attach(activity: ComponentActivity, webView: WebView) {
        activityRef = activity
        webViewRef = webView
        appContext = activity.applicationContext
        picker = activity.registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (!uris.isNullOrEmpty()) {
                enqueue(uris)
            }
        }
    }

    /** 「用其他应用打开 / 分享」进来时调用，直接进书架 */
    fun importFromUri(activity: Activity, webView: WebView, uri: Uri) {
        webViewRef = webView
        if (appContext == null) appContext = activity.applicationContext
        startImport(listOf(uri))
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    private fun startImport(uris: List<Uri>) {
        enqueue(uris)
    }

    // ------------------------------------------------------------------
    // 导入队列：一次选很多个也排队处理，队列没清空前 worker 一直跑
    // ------------------------------------------------------------------

    private val pending = java.util.Collections.synchronizedList(ArrayList<Uri>())

    @Volatile
    private var workerRunning = false

    @Volatile
    private var queueDone = 0

    @Volatile
    private var lastError = ""

    private fun enqueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending.addAll(uris)
        if (workerRunning) return
        workerRunning = true
        importing = true
        queueDone = 0
        lastError = ""
        io.execute { runWorker() }
    }

    private fun runWorker() {
        val ctx = appContext
        if (ctx == null) {
            workerRunning = false
            importing = false
            return
        }
        val created = JSONArray()
        while (true) {
            val uri: Uri? = synchronized(pending) {
                if (pending.isEmpty()) null else pending.removeAt(0)
            }
            if (uri == null) break
            try {
                val id = importOne(ctx, uri)
                if (id != null) created.put(id)
            } catch (e: Exception) {
                lastError = e.message ?: "导入失败"
                e.printStackTrace()
                toast("导入失败：" + (e.message ?: "未知错误"))
            }
            queueDone++
        }
        importing = false
        importPercent = 0
        importTitle = ""
        workerRunning = false
        if (created.length() > 0) {
            notifyImportDone(created.toString())
        }
        notifyChanged()
    }

    private fun importOne(ctx: Context, uri: Uri): String? {
        val rawName = queryName(ctx, uri)
        importTitle = stripExtension(rawName)
        importPercent = 0

        var total = querySize(ctx, uri)
        val id = "b" + System.currentTimeMillis().toString(36) + queueDone.toString(36)
        val bookDir = File(File(ctx.filesDir, DIR_BOOKS), id)
        if (!bookDir.exists() && !bookDir.mkdirs()) {
            throw IllegalStateException("无法创建目录")
        }
        val target = File(bookDir, "book.zip")

        var copied = 0L
        try {
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取文件（可能没有授权）")
            input.use { ins ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            importPercent = ((copied * 100) / total).toInt().coerceIn(0, 99)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            bookDir.deleteRecursively()
            throw e
        }

        if (total <= 0) total = target.length()

        // 校验确实是个能读的压缩包，并且里面有图片
        val zip = ZipFile(target)
        val names = collectImages(zip)
        if (names.isEmpty()) {
            zip.close()
            bookDir.deleteRecursively()
            throw IllegalStateException("压缩包里没有找到图片文件")
        }
        makeCover(zip, names, File(bookDir, "cover.jpg"))
        zip.close()

        val meta = JSONObject()
        meta.put("id", id)
        meta.put("title", stripExtension(rawName))
        meta.put("pages", names.size)
        meta.put("size", target.length())
        meta.put("importedAt", System.currentTimeMillis())
        meta.put("lastRead", 0)

        synchronized(metaLock) {
            val old = readBooks()
            val out = JSONArray()
            out.put(meta)                       // 最新的放前面
            for (i in 0 until old.length()) out.put(old.get(i))
            writeBooks(out)
        }
        return id
    }

    private fun makeCover(zip: ZipFile, names: List<String>, outFile: File): Boolean {
        return try {
            val coverName = names.firstOrNull {
                it.substringAfterLast('/').startsWith("cover", true)
            } ?: names.first()

            val entry = zip.getEntry(coverName) ?: return false

            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2

            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            val scaled = if (bmp.width > COVER_WIDTH) {
                Bitmap.createScaledBitmap(bmp, COVER_WIDTH, bmp.height * COVER_WIDTH / bmp.width, true)
            } else {
                bmp
            }
            FileOutputStream(outFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private fun collectImages(zip: ZipFile): List<String> {
        val list = ArrayList<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            if (name.startsWith("__MACOSX/")) continue
            if (name.substringAfterLast('/').startsWith("._")) continue
            val lower = name.lowercase()
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
            ) {
                list.add(name)
            }
        }
        list.sortWith(NaturalOrder)
        return list
    }

    private fun ensureOpenLocked(id: String): ZipFile? {
        if (openId == id && openZip != null) return openZip
        closeOpenLocked()
        val ctx = appContext ?: return null
        val file = File(File(File(ctx.filesDir, DIR_BOOKS), id), "book.zip")
        if (!file.exists()) return null
        return try {
            val zip = ZipFile(file)
            openZip = zip
            openId = id
            openNames = collectImages(zip)
            zip
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun closeOpenLocked() {
        try {
            openZip?.close()
        } catch (_: Exception) {
        }
        openZip = null
        openId = null
        openNames = emptyList()
    }

    // ------------------------------------------------------------------
    // 元数据
    // ------------------------------------------------------------------

    private fun metaFile(): File {
        val ctx = appContext ?: throw IllegalStateException("not attached")
        val dir = File(ctx.filesDir, DIR_BOOKS)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, META_FILE)
    }

    private fun readBooks(): JSONArray {
        return try {
            val file = metaFile()
            if (!file.exists()) JSONArray()
            else JSONArray(file.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeBooks(array: JSONArray) {
        try {
            val file = metaFile()
            val tmp = File(file.parentFile, META_FILE + ".tmp")
            tmp.writeText(array.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun queryName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "comic.zip"
    }

    private fun querySize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index)
                }
            }
        } catch (_: Exception) {
        }
        return 0L
    }

    private fun stripExtension(name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith(".zip") || lower.endsWith(".cbz") || lower.endsWith(".rar")) {
            return name.substring(0, name.length - 4)
        }
        return name
    }

    private fun mimeOf(entryName: String): String {
        val lower = entryName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".bmp") -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    private fun toast(message: String) {
        val activity = activityRef ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun js(code: String) {
        val view = webViewRef ?: return
        view.post { view.evaluateJavascript(code, null) }
    }

    private fun notifyChanged() {
        js("window.__comicLibChanged && window.__comicLibChanged()")
    }

    private fun notifyImportDone(idsJson: String) {
        js("window.__comicLibImportDone && window.__comicLibImportDone($idsJson)")
    }

    // ------------------------------------------------------------------
    // 给网页用的接口（只有标了 @JavascriptInterface 的方法才会暴露给 JS）
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun listBooks(): String {
        return try {
            val root = JSONObject()
            synchronized(metaLock) { root.put("books", readBooks()) }
            root.put("importing", importing)
            root.put("importTitle", importTitle)
            root.put("importPercent", importPercent)
            root.put("queueDone", queueDone)
            root.put("queueRemaining", pending.size + if (importing) 1 else 0)
            root.put("lastError", lastError)
            root.toString()
        } catch (e: Exception) {
            "{\"books\":[],\"importing\":false}"
        }
    }

    @JavascriptInterface
    fun pickAndImport() {
        val activity = activityRef ?: return
        activity.runOnUiThread {
            try {
                picker?.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(activity, "打不开文件选择器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun pageCount(id: String): Int {
        synchronized(zipLock) {
            ensureOpenLocked(id) ?: return 0
            return openNames.size
        }
    }

    /** 返回整条 data URL（含 mime），页面直接塞给 <img src> 即可 */
    @JavascriptInterface
    fun getPage(id: String, index: Int): String {
        synchronized(zipLock) {
            val zip = ensureOpenLocked(id) ?: return ""
            if (index < 0 || index >= openNames.size) return ""
            val name = openNames[index]
            val entry = zip.getEntry(name) ?: return ""
            return try {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                "data:" + mimeOf(name) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }
    }

    @JavascriptInterface
    fun getCover(id: String): String {
        return try {
            val ctx = appContext ?: return ""
            val file = File(File(File(ctx.filesDir, DIR_BOOKS), id), "cover.jpg")
            if (!file.exists()) return ""
            val bytes = file.readBytes()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /** 页栏用的小缩略图（160px 宽 jpg），带内存缓存 */
    @JavascriptInterface
    fun getThumb(id: String, index: Int): String {
        val key = id + "#" + index
        synchronized(thumbCache) {
            thumbCache[key]?.let { return it }
        }
        synchronized(zipLock) {
            val zip = ensureOpenLocked(id) ?: return ""
            if (index < 0 || index >= openNames.size) return ""
            val entry = zip.getEntry(openNames[index]) ?: return ""
            val url = try {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
                val opts = BitmapFactory.Options()
                opts.inSampleSize = sample
                val bmp = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: return ""
                val scaled = if (bmp.width > THUMB_WIDTH) {
                    Bitmap.createScaledBitmap(bmp, THUMB_WIDTH, bmp.height * THUMB_WIDTH / bmp.width, true)
                } else {
                    bmp
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
            if (url.isNotEmpty()) {
                synchronized(thumbCache) { thumbCache[key] = url }
            }
            return url
        }
    }

    @JavascriptInterface
    fun setProgress(id: String, index: Int) {
        io.execute {
            synchronized(metaLock) {
                val array = readBooks()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString("id") == id) {
                        item.put("lastRead", index)
                        item.put("updatedAt", System.currentTimeMillis())
                        break
                    }
                }
                writeBooks(array)
            }
        }
    }

    @JavascriptInterface
    fun deleteBook(id: String) {
        io.execute {
            synchronized(zipLock) {
                if (openId == id) closeOpenLocked()
            }
            val ctx = appContext ?: return@execute
            File(File(ctx.filesDir, DIR_BOOKS), id).deleteRecursively()
            synchronized(metaLock) {
                val array = readBooks()
                val out = JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString("id") != id) out.put(item)
                }
                writeBooks(out)
            }
            notifyChanged()
        }
    }

    @JavascriptInterface
    fun storageInfo(): String {
        return try {
            val ctx = appContext ?: return "{}"
            val dir = File(ctx.filesDir, DIR_BOOKS)
            val obj = JSONObject()
            obj.put("booksBytes", folderSize(dir))
            obj.put("freeBytes", ctx.filesDir.usableSpace)
            obj.toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    private fun folderSize(dir: File): Long {
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            total += if (f.isDirectory) folderSize(f) else f.length()
        }
        return total
    }

    /** 图片名自然排序：1.jpg < 2.jpg < 10.jpg */
    private object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var si = i
                    while (si < a.length && a[si].isDigit()) si++
                    var sj = j
                    while (sj < b.length && b[sj].isDigit()) sj++
                    val na = a.substring(i, si).trimStart('0')
                    val nb = b.substring(j, sj).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val cmp = na.compareTo(nb)
                    if (cmp != 0) return cmp
                    i = si
                    j = sj
                } else {
                    if (ca != cb) return ca.compareTo(cb)
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
