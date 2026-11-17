package com.app.pakeplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebView

/**
 * 兼容层：MainActivity 里原有的
 *   ComicIntent.handle(this, webView, intent, ...)
 * 继续保留，只把「用其他应用打开 / 分享进来的文件」转交给书架 ComicLib。
 *
 * 以前这里是把整个压缩包复制到缓存再交给网页，现在改成直接导入书架，
 * 这样几 G 的包也不会进内存。
 */
object ComicIntent {

    fun handle(activity: Activity, webView: WebView, intent: Intent?, allowed: Boolean) {
        if (!allowed) return
        val uri = extractUri(intent) ?: return
        ComicLib.importFromUri(activity, webView, uri)
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
}
