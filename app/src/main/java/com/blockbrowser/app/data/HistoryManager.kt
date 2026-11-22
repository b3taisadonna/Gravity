package com.blockbrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val id: String,
    val title: String,
    val url: String,
    val timestamp: Long
)

object HistoryManager {
    private const val PREFS = "block_browser_history"
    private const val KEY_ITEMS = "history_list"
    private const val MAX_ITEMS = 300

    fun getAll(context: Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val list = mutableListOf<HistoryItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        }
        return list
    }

    fun add(context: Context, title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val items = getAll(context).toMutableList()
        if (items.isNotEmpty() && items.first().url == url) {
            val updated = items.first().copy(
                title = if (title.isNotBlank()) title else items.first().title,
                timestamp = System.currentTimeMillis()
            )
            items[0] = updated
        } else {
            val item = HistoryItem(
                id = System.currentTimeMillis().toString(),
                title = if (title.isNotBlank()) title else url,
                url = url,
                timestamp = System.currentTimeMillis()
            )
            items.add(0, item)
        }
        if (items.size > MAX_ITEMS) {
            items.subList(MAX_ITEMS, items.size).clear()
        }
        saveAll(context, items)
    }

    fun remove(context: Context, id: String) {
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

    private fun saveAll(context: Context, items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("url", item.url)
            obj.put("timestamp", item.timestamp)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }
}
