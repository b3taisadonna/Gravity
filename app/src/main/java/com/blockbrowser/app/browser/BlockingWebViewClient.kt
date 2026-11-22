package com.blockbrowser.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.blockbrowser.app.data.HistoryManager
import com.blockbrowser.app.extensions.ExtensionManager
import java.io.ByteArrayInputStream

/**
 * Blocks requests to known ad/tracker hosts in-page (works regardless of whether
 * the system-wide VPN blocker is running), injects enabled extensions once
 * a page finishes loading, and records browsing history.
 */
class BlockingWebViewClient(
    private val context: Context,
    private val onPageStateChanged: (title: String, url: String, loading: Boolean) -> Unit
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (AdBlockManager.isBlockedUrl(url)) {
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStateChanged(view.title ?: url.orEmpty(), url.orEmpty(), true)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrBlank()) {
            ExtensionManager.injectAllFor(context, view, url)
            val pageTitle = view.title?.takeIf { it.isNotBlank() } ?: url
            HistoryManager.add(context, pageTitle, url)
        }
        onPageStateChanged(view.title ?: url.orEmpty(), url.orEmpty(), false)
    }
}
