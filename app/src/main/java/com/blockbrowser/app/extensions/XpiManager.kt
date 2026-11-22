package com.blockbrowser.app.extensions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class InstalledXpiExtension(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String = "Unbekannt",
    val homepageUrl: String = "",
    val matches: List<String>,
    val scriptFiles: List<String>,
    val permissions: List<String> = emptyList(),
    val optionsPagePath: String? = null,
    val popupPagePath: String? = null,
    var enabled: Boolean = true
)

object XpiManager {
    private const val PREFS = "block_browser_xpi_extensions"
    private const val KEY_INSTALLED = "installed_xpi_list"

    fun getInstalledExtensions(context: Context): List<InstalledXpiExtension> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_INSTALLED, "[]") ?: "[]"
        val list = mutableListOf<InstalledXpiExtension>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val matchesArr = obj.optJSONArray("matches") ?: JSONArray()
                val matchesList = mutableListOf<String>()
                for (j in 0 until matchesArr.length()) {
                    matchesList.add(matchesArr.getString(j))
                }
                val scriptsArr = obj.optJSONArray("scriptFiles") ?: JSONArray()
                val scriptsList = mutableListOf<String>()
                for (j in 0 until scriptsArr.length()) {
                    scriptsList.add(scriptsArr.getString(j))
                }
                val permsArr = obj.optJSONArray("permissions") ?: JSONArray()
                val permsList = mutableListOf<String>()
                for (j in 0 until permsArr.length()) {
                    permsList.add(permsArr.getString(j))
                }

                list.add(
                    InstalledXpiExtension(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        version = obj.optString("version", "1.0"),
                        author = obj.optString("author", "Unbekannt"),
                        homepageUrl = obj.optString("homepageUrl", ""),
                        matches = matchesList,
                        scriptFiles = scriptsList,
                        permissions = permsList,
                        optionsPagePath = obj.optString("optionsPagePath", "").takeIf { it.isNotBlank() },
                        popupPagePath = obj.optString("popupPagePath", "").takeIf { it.isNotBlank() },
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
        }
        return list
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val list = getInstalledExtensions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(enabled = enabled)
            saveAll(context, list)
        }
    }

    fun updateMatches(context: Context, id: String, newMatches: List<String>) {
        val list = getInstalledExtensions(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(matches = if (newMatches.isEmpty()) listOf("*") else newMatches)
            saveAll(context, list)
        }
    }

    fun uninstall(context: Context, id: String) {
        val list = getInstalledExtensions(context).toMutableList()
        list.removeAll { it.id == id }
        saveAll(context, list)
        val extDir = File(context.filesDir, "xpi_extensions/$id")
        if (extDir.exists()) {
            extDir.deleteRecursively()
        }
    }

    fun matchesUrl(item: InstalledXpiExtension, url: String): Boolean {
        if (item.matches.any { it == "*" || it == "<all_urls>" }) return true
        return item.matches.any { pattern ->
            url.contains(pattern, ignoreCase = true)
        }
    }

    fun installFromStream(
        context: Context,
        inputStream: InputStream,
        fallbackName: String = "Firefox-Erweiterung"
    ): Result<InstalledXpiExtension> {
        return runCatching {
            val tempDir = File(context.cacheDir, "xpi_extract_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            var manifestContent: String? = null
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory) {
                        val outFile = File(tempDir, entryName)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        if (entryName.equals("manifest.json", ignoreCase = true) ||
                            entryName.endsWith("/manifest.json", ignoreCase = true)
                        ) {
                            manifestContent = outFile.readText()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (manifestContent == null) {
                tempDir.deleteRecursively()
                error("Keine gültige manifest.json in der .xpi Datei gefunden.")
            }

            val manifest = JSONObject(manifestContent!!)
            var extName = manifest.optString("name", fallbackName)
            if (extName.startsWith("__MSG_") && extName.endsWith("__")) {
                extName = fallbackName
            }
            var extDesc = manifest.optString("description", "")
            if (extDesc.startsWith("__MSG_") && extDesc.endsWith("__")) {
                extDesc = "Firefox WebExtension"
            }
            val extVersion = manifest.optString("version", "1.0")
            val extAuthor = manifest.optString("author", manifest.optJSONObject("author")?.optString("name", "Firefox Developer") ?: "Firefox Developer")
            val extHomepage = manifest.optString("homepage_url", "")

            val id = "xpi_${System.currentTimeMillis()}"
            val targetDir = File(context.filesDir, "xpi_extensions/$id")
            targetDir.mkdirs()

            // Copy all extracted files into target directory
            tempDir.copyRecursively(targetDir, overwrite = true)

            // Permissions
            val permsList = mutableListOf<String>()
            val permsArr = manifest.optJSONArray("permissions")
            if (permsArr != null) {
                for (p in 0 until permsArr.length()) {
                    permsList.add(permsArr.getString(p))
                }
            }

            // Options page detection
            var optionsPath: String? = null
            val optionsUi = manifest.optJSONObject("options_ui")
            val optionsPageName = optionsUi?.optString("page") ?: manifest.optString("options_page", "")
            if (optionsPageName.isNotBlank()) {
                val optFile = File(targetDir, optionsPageName)
                if (optFile.exists()) {
                    optionsPath = optFile.absolutePath
                }
            }

            // Popup page detection
            var popupPath: String? = null
            val browserAction = manifest.optJSONObject("browser_action") ?: manifest.optJSONObject("action")
            val popupName = browserAction?.optString("default_popup", "")
            if (!popupName.isNullOrBlank()) {
                val pFile = File(targetDir, popupName)
                if (pFile.exists()) {
                    popupPath = pFile.absolutePath
                }
            }

            // Fallback for options/popup if not explicitly declared in manifest
            if (optionsPath == null) {
                val candidate = File(targetDir, "options.html")
                if (candidate.exists()) optionsPath = candidate.absolutePath
            }
            if (popupPath == null) {
                val candidate = File(targetDir, "popup.html")
                if (candidate.exists()) popupPath = candidate.absolutePath
            }

            // Content scripts
            val contentScripts = manifest.optJSONArray("content_scripts") ?: JSONArray()
            val matchesList = mutableListOf<String>()
            val scriptPaths = mutableListOf<String>()

            for (i in 0 until contentScripts.length()) {
                val csObj = contentScripts.getJSONObject(i)
                val matchesArr = csObj.optJSONArray("matches") ?: JSONArray()
                for (m in 0 until matchesArr.length()) {
                    val matchStr = matchesArr.getString(m)
                    if (matchStr == "<all_urls>" || matchStr == "*://*/*" || matchStr == "*") {
                        matchesList.add("*")
                    } else {
                        val clean = matchStr.replace("*://", "").replace("/*", "").trim('/')
                        matchesList.add(clean)
                    }
                }

                val jsArr = csObj.optJSONArray("js") ?: JSONArray()
                for (j in 0 until jsArr.length()) {
                    val jsFileName = jsArr.getString(j)
                    val jsFile = File(targetDir, jsFileName)
                    if (jsFile.exists()) {
                        scriptPaths.add(jsFile.absolutePath)
                    }
                }
            }

            // Fallback script detection if content_scripts was empty
            if (scriptPaths.isEmpty()) {
                targetDir.walkTopDown().filter { it.isFile && it.extension.equals("js", ignoreCase = true) }.forEach { jsFile ->
                    scriptPaths.add(jsFile.absolutePath)
                }
                if (matchesList.isEmpty()) {
                    matchesList.add("*")
                }
            }

            tempDir.deleteRecursively()

            val installed = InstalledXpiExtension(
                id = id,
                name = extName,
                description = extDesc,
                version = extVersion,
                author = extAuthor,
                homepageUrl = extHomepage,
                matches = if (matchesList.isEmpty()) listOf("*") else matchesList.distinct(),
                scriptFiles = scriptPaths.distinct(),
                permissions = permsList.distinct(),
                optionsPagePath = optionsPath,
                popupPagePath = popupPath,
                enabled = true
            )

            val existing = getInstalledExtensions(context).toMutableList()
            existing.add(0, installed)
            saveAll(context, existing)

            installed
        }
    }

    private fun saveAll(context: Context, list: List<InstalledXpiExtension>) {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("description", item.description)
            obj.put("version", item.version)
            obj.put("author", item.author)
            obj.put("homepageUrl", item.homepageUrl)
            obj.put("matches", JSONArray(item.matches))
            obj.put("scriptFiles", JSONArray(item.scriptFiles))
            obj.put("permissions", JSONArray(item.permissions))
            obj.put("optionsPagePath", item.optionsPagePath ?: "")
            obj.put("popupPagePath", item.popupPagePath ?: "")
            obj.put("enabled", item.enabled)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INSTALLED, arr.toString())
            .apply()
    }
}
