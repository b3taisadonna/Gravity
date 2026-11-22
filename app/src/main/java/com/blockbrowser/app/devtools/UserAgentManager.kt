package com.blockbrowser.app.devtools

enum class UserAgentMode(val id: String, val displayName: String) {
    AUTO("auto", "Automatisch (Firefox für Add-ons)"),
    FIREFOX_DESKTOP("firefox_desktop", "Firefox Desktop"),
    FIREFOX_MOBILE("firefox_mobile", "Firefox Mobil"),
    DEFAULT_MOBILE("default_mobile", "Standard System (Mobil)"),
    DESKTOP("desktop", "Desktop (Firefox)");

    companion object {
        fun fromId(id: String?): UserAgentMode =
            values().firstOrNull { it.id == id } ?: AUTO
    }
}

object UserAgentManager {
    const val UA_FIREFOX_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"

    const val UA_FIREFOX_MOBILE =
        "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"

    fun getEffectiveUserAgent(mode: UserAgentMode, url: String?, isDesktop: Boolean): String? {
        val lowerUrl = url?.lowercase().orEmpty()
        return when (mode) {
            UserAgentMode.FIREFOX_DESKTOP -> UA_FIREFOX_DESKTOP
            UserAgentMode.FIREFOX_MOBILE -> UA_FIREFOX_MOBILE
            UserAgentMode.DEFAULT_MOBILE -> null
            UserAgentMode.DESKTOP -> UA_FIREFOX_DESKTOP
            UserAgentMode.AUTO -> {
                when {
                    lowerUrl.contains("addons.mozilla.org") -> UA_FIREFOX_DESKTOP
                    isDesktop -> UA_FIREFOX_DESKTOP
                    else -> null
                }
            }
        }
    }
}
