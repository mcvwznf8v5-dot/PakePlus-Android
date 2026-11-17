package com.app.pakeplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile

/**
 * 让 App 能接住「用其他应用打开 / 分享」进来的 .zip / .cbz 文件，
 * 并把文件内容交给网页里的 JSZip 使用。
 */
object ComicIntent {

    private const val CACHE_NAME = "incoming_comic.zip"

    @Volatile
    private var pendingFile: File? = null

    @Volatile
    private var pendingName: String = "comic.zip"

    @Volatile
    private var pendingReady: Boolean = false

    fun handle(activity: Activity, webView: WebView, intent: Intent?, allowed: Boolean) {
        if (!allowed) return
        val uri = extractUri(intent) ?: return

        val displayName = queryDisplayName(activity, uri)
        pendingReady = false
        pendingFile = null

        Thread {
            val target = File(activity.cacheDir, CACHE_NAME)
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                } ?: error("无法读取文件（可能没有授权）")

                pendingName = displayName
                pendingFile = target
                pendingReady = true
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "打开失败：" + (e.message ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            val js = "window.__ppComicReady && window.__ppComicReady()"
            webView.post { webView.evaluateJavascript(js, null) }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> intent.data
        }
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.substringAfterLast('/') ?: "comic.zip"
        }
        try {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        } catch (_: Exception) {
            // 有些 provider 不支持查询，忽略即可
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "comic.zip"
    }

    /** 网页里通过 window.PPOpen 调用的桥 */
    object Bridge {

        @JavascriptInterface
        fun hasPending(): Boolean = pendingReady && pendingFile?.exists() == true

        @JavascriptInterface
        fun name(): String = pendingName

        @JavascriptInterface
        fun size(): Long = if (pendingReady) (pendingFile?.length() ?: 0L) else 0L

        @JavascriptInterface
        fun chunk(offset: Long, len: Int): String {
            val file = pendingFile ?: return ""
            if (!file.exists() || len <= 0 || offset < 0) return ""
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val available = raf.length() - offset
                    if (available <= 0) return ""
                    val count = minOf(len.toLong(), available).toInt()
                    val buffer = ByteArray(count)
                    raf.seek(offset)
                    raf.readFully(buffer)
                    Base64.encodeToString(buffer, Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun clear() {
            pendingReady = false
            pendingFile?.delete()
            pendingFile = null
        }
    }
}
