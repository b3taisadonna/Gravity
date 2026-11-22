package com.blockbrowser.app.extensions

import android.content.Context
import android.webkit.WebView
import java.io.File

/**
 * Extension system: manages built-in sample extensions (userscripts)
 * and Firefox WebExtensions (.xpi) installed by the user.
 */
data class Extension(
    val id: String,
    val name: String,
    val description: String,
    val matches: List<String>, // substrings matched against the page URL; "*" = all sites
    val scriptAsset: String,
    var enabled: Boolean
)

object ExtensionManager {
    private const val PREFS = "block_browser_extensions"
    private val extensions = mutableListOf<Extension>()
    private var loaded = false

    private val catalog = listOf(
        Extension(
            id = "dark_reader_lite",
            name = "Dark Reader (einfach)",
            description = "Erzwingt ein dunkles Farbschema auf jeder Seite.",
            matches = listOf("*"),
            scriptAsset = "userscripts/dark_mode.js",
            enabled = false
        ),
        Extension(
            id = "cookie_banner_hider",
            name = "Cookie-Banner entfernen",
            description = "Versteckt gängige Cookie-/Consent-Banner per CSS.",
            matches = listOf("*"),
            scriptAsset = "userscripts/hide_cookie_banners.js",
            enabled = true
        )
    )

    fun ensureLoaded(context: Context) {
        if (loaded) return
        extensions.clear()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        catalog.forEach { template ->
            val saved = prefs.getBoolean(template.id, template.enabled)
            extensions.add(template.copy(enabled = saved))
        }
        loaded = true
    }

    fun all(): List<Extension> = extensions.toList()

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        extensions.find { it.id == id }?.enabled = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(id, enabled).apply()
    }

    /** Call from WebViewClient.onPageFinished to inject every enabled, matching extension. */
    fun injectAllFor(context: Context, webView: WebView, url: String) {
        // 1. Built-in sample extensions
        extensions.filter { it.enabled && matches(it, url) }.forEach { ext ->
            runCatching {
                context.assets.open(ext.scriptAsset).use { input ->
                    val js = input.bufferedReader().readText()
                    webView.evaluateJavascript(js, null)
                }
            }
        }

        // 2. Installed Firefox .xpi extensions
        val xpiList = XpiManager.getInstalledExtensions(context)
        xpiList.filter { it.enabled && XpiManager.matchesUrl(it, url) }.forEach { xpi ->
            xpi.scriptFiles.forEach { filePath ->
                runCatching {
                    val file = File(filePath)
                    if (file.exists()) {
                        val js = file.readText()
                        webView.evaluateJavascript(js, null)
                    }
                }
            }
        }
    }

    private fun matches(ext: Extension, url: String): Boolean =
        ext.matches.any { it == "*" || url.contains(it, ignoreCase = true) }
}
