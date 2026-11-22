package com.blockbrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteItem(
    val id: String,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

object FavoritesManager {
    private const val PREFS = "block_browser_favorites"
    private const val KEY_ITEMS = "favorites_list"

    fun getAll(context: Context): List<FavoriteItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val list = mutableListOf<FavoriteItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FavoriteItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        timestamp = obj.optLong("timestamp", 0)
                    )
                )
            }
        }
        return list
    }

    fun isFavorite(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val cleanUrl = url.trimEnd('/')
        return getAll(context).any { it.url.trimEnd('/') == cleanUrl }
    }

    fun add(context: Context, title: String, url: String): FavoriteItem {
        val items = getAll(context).toMutableList()
        val cleanTitle = if (title.isBlank()) url else title
        val item = FavoriteItem(
            id = System.currentTimeMillis().toString(),
            title = cleanTitle,
            url = url
        )
        items.removeAll { it.url.trimEnd('/') == url.trimEnd('/') }
        items.add(0, item)
        saveAll(context, items)
        return item
    }

    fun remove(context: Context, id: String) {
        val items = getAll(context).toMutableList()
        items.removeAll { it.id == id }
        saveAll(context, items)
    }

    fun removeByUrl(context: Context, url: String) {
        val cleanUrl = url.trimEnd('/')
        val items = getAll(context).toMutableList()
        items.removeAll { it.url.trimEnd('/') == cleanUrl }
        saveAll(context, items)
    }

    private fun saveAll(context: Context, items: List<FavoriteItem>) {
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
