package com.blockbrowser.app.browser

import android.webkit.WebView
import com.blockbrowser.app.devtools.UserAgentMode

data class BrowserTab(
    val id: Long,
    val webView: WebView,
    var title: String = "Neuer Tab",
    var url: String = "about:blank",
    var isDesktopMode: Boolean = false,
    var uaMode: UserAgentMode = UserAgentMode.AUTO
)
