package com.blockbrowser.app.browser

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Holds the set of blocked hosts and answers "should this request be blocked?".
 * Used both by the in-page WebView blocker (shouldInterceptRequest) and by the
 * system-wide AdBlockVpnService (DNS-level blocking).
 *
 * The bundled list (assets/adblock_hosts.txt) is a small starter sample in the
 * classic "0.0.0.0 domain.tld" hosts-file format used by many public blocklists
 * (e.g. StevenBlack's hosts, AdAway). Replace/extend it with any list in that
 * format - just drop a bigger file in assets and it will be parsed the same way.
 */
object AdBlockManager {

    private val blockedHosts = HashSet<String>()
    @Volatile var isEnabled: Boolean = true
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                context.assets.open("adblock_hosts.txt").use { input ->
                    BufferedReader(InputStreamReader(input)).forEachLine { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                        // Supports "0.0.0.0 host" / "127.0.0.1 host" / plain "host" lines
                        val parts = line.split(Regex("\\s+"))
                        val host = if (parts.size >= 2) parts[1] else parts[0]
                        if (host.isNotBlank() && host != "0.0.0.0" && host != "localhost") {
                            blockedHosts.add(host.lowercase())
                        }
                    }
                }
            } catch (_: Exception) {
                // Missing/broken list -> fail open (nothing blocked) rather than crash.
            }
            loaded = true
        }
    }

    fun isBlockedUrl(url: String): Boolean {
        if (!isEnabled) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return isBlockedHost(host)
    }

    fun isBlockedHost(host: String): Boolean {
        if (!isEnabled) return false
        val h = host.lowercase()
        if (blockedHosts.contains(h)) return true
        // also match parent domain for subdomains, e.g. ads.example.com -> example.com
        var idx = h.indexOf('.')
        while (idx != -1) {
            val parent = h.substring(idx + 1)
            if (blockedHosts.contains(parent)) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    fun blockedCount(): Int = blockedHosts.size
}
