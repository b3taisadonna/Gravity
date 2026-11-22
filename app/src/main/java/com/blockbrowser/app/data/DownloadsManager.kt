package com.blockbrowser.app.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

data class DownloadRecord(
    val id: String,
    val fileName: String,
    val url: String,
    val timestamp: Long,
    val mimeType: String
)

object DownloadsManager {
    private const val PREFS = "block_browser_downloads"
    private const val KEY_ITEMS = "downloads_list"

    fun getAll(context: Context): List<DownloadRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val list = mutableListOf<DownloadRecord>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DownloadRecord(
                        id = obj.getString("id"),
                        fileName = obj.getString("fileName"),
                        url = obj.getString("url"),
                        timestamp = obj.getLong("timestamp"),
                        mimeType = obj.optString("mimeType", "*/*")
                    )
                )
            }
        }
        return list
    }

    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                }
                setTitle(fileName)
                setDescription("Download über Gravity")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            addRecord(
                context,
                DownloadRecord(
                    id = System.currentTimeMillis().toString(),
                    fileName = fileName,
                    url = url,
                    timestamp = System.currentTimeMillis(),
                    mimeType = mimeType ?: "*/*"
                )
            )

            Toast.makeText(context, "Download gestartet: $fileName", Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            Toast.makeText(context, "Download-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addRecord(context: Context, record: DownloadRecord) {
        val items = getAll(context).toMutableList()
        items.add(0, record)
        saveAll(context, items)
    }

    fun removeRecord(context: Context, id: String) {
        val items = getAll(context).toMutableList()
        items.removeAll { it.id == id }
        saveAll(context, items)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    fun openSystemDownloads(context: Context) {
        runCatching {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "Download-Ordner konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAll(context: Context, items: List<DownloadRecord>) {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("fileName", item.fileName)
            obj.put("url", item.url)
            obj.put("timestamp", item.timestamp)
            obj.put("mimeType", item.mimeType)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }
}
