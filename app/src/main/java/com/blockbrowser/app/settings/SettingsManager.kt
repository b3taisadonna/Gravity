package com.blockbrowser.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.blockbrowser.app.devtools.UserAgentManager
import com.blockbrowser.app.devtools.UserAgentMode

enum class SearchEngine(val displayName: String, val searchUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=");

    fun buildQueryUrl(query: String): String = "$searchUrl${Uri.encode(query)}"

    companion object {
        fun fromName(name: String?): SearchEngine =
            values().firstOrNull { it.name == name } ?: GOOGLE
    }
}

object SettingsManager {
    private const val PREFS = "block_browser_settings"
    private const val KEY_DEFAULT_DESKTOP_SITE = "default_desktop_site"
    private const val KEY_DEFAULT_UA_MODE = "default_ua_mode"
    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_JAVASCRIPT_ENABLED = "javascript_enabled"
    private const val KEY_DOM_STORAGE_ENABLED = "dom_storage_enabled"
    private const val KEY_HOME_PAGE = "home_page"

    const val DESKTOP_USER_AGENT = UserAgentManager.UA_FIREFOX_DESKTOP

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDefaultDesktopSite(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEFAULT_DESKTOP_SITE, false)

    fun setDefaultDesktopSite(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEFAULT_DESKTOP_SITE, enabled).apply()
    }

    fun getDefaultUserAgentMode(context: Context): UserAgentMode {
        val id = prefs(context).getString(KEY_DEFAULT_UA_MODE, UserAgentMode.AUTO.id)
        return UserAgentMode.fromId(id)
    }

    fun setDefaultUserAgentMode(context: Context, mode: UserAgentMode) {
        prefs(context).edit().putString(KEY_DEFAULT_UA_MODE, mode.id).apply()
    }

    fun getSearchEngine(context: Context): SearchEngine {
        val name = prefs(context).getString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.name)
        return SearchEngine.fromName(name)
    }

    fun setSearchEngine(context: Context, engine: SearchEngine) {
        prefs(context).edit().putString(KEY_SEARCH_ENGINE, engine.name).apply()
    }

    fun isJavaScriptEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_JAVASCRIPT_ENABLED, true)

    fun setJavaScriptEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_JAVASCRIPT_ENABLED, enabled).apply()
    }

    fun isDomStorageEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOM_STORAGE_ENABLED, true)

    fun setDomStorageEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOM_STORAGE_ENABLED, enabled).apply()
    }

    fun getHomePage(context: Context): String =
        prefs(context).getString(KEY_HOME_PAGE, "https://www.google.com") ?: "https://www.google.com"

    fun setHomePage(context: Context, url: String) {
        prefs(context).edit().putString(KEY_HOME_PAGE, url).apply()
    }

    fun buildSearchUrl(context: Context, query: String): String =
        getSearchEngine(context).buildQueryUrl(query)
}
