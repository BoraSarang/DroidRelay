package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RssFeed(
    val id: String,
    val url: String,
    val name: String,
    val filterKeyword: String = "",
    val filterRegex: String = "",
    val autoDownload: Boolean = true,
    val enabled: Boolean = true,
    val lastCheckedAt: Long = 0L,
    val lastItemTitle: String = "",
    val lastItemCount: Int = 0,
    val error: String? = null,
)

data class RssFeedItem(
    val title: String,
    val link: String,
    val pubDate: String = "",
    val enclosureUrl: String? = null,
    val enclosureType: String? = null,
)

object RssFeedRepository {
    private val TAG = "RssFeed"
    private val _feeds = MutableStateFlow<List<RssFeed>>(emptyList())
    val feeds: StateFlow<List<RssFeed>> = _feeds
    private val map = ConcurrentHashMap<String, RssFeed>()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "rss_feeds.json")
        load()
    }

    fun all(): List<RssFeed> = _feeds.value
    fun get(id: String): RssFeed? = map[id]

    fun add(url: String, name: String, filterKeyword: String = "", filterRegex: String = "", autoDownload: Boolean = true): RssFeed {
        val id = System.currentTimeMillis().toString(36) + (0..999).random()
        val feed = RssFeed(
            id = id,
            url = url.trim(),
            name = name.trim().ifBlank { url.substringAfter("//").take(40) },
            filterKeyword = filterKeyword,
            filterRegex = filterRegex,
            autoDownload = autoDownload,
        )
        map[id] = feed
        save()
        refresh()
        DebugLogger.i(TAG, "피드 추가 id=$id url=$url name='${feed.name}'")
        return feed
    }

    fun update(id: String, transform: (RssFeed) -> RssFeed) {
        map.computeIfPresent(id) { _, before -> transform(before) }
        save()
        refresh()
    }

    fun remove(id: String): Boolean {
        val removed = map.remove(id)
        if (removed != null) {
            save()
            refresh()
            DebugLogger.i(TAG, "피드 삭제 id=$id name='${removed.name}'")
        }
        return removed != null
    }

    private fun refresh() {
        _feeds.value = map.values.sortedBy { it.name }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            map.values.forEach { f ->
                arr.put(JSONObject().apply {
                    put("id", f.id)
                    put("url", f.url)
                    put("name", f.name)
                    put("filterKeyword", f.filterKeyword)
                    put("filterRegex", f.filterRegex)
                    put("autoDownload", f.autoDownload)
                    put("enabled", f.enabled)
                    put("lastCheckedAt", f.lastCheckedAt)
                    put("lastItemTitle", f.lastItemTitle)
                    put("lastItemCount", f.lastItemCount)
                    put("error", f.error ?: JSONObject.NULL)
                })
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            DebugLogger.d(TAG, "피드 저장 ${map.size}건")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "피드 저장 실패", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val feed = RssFeed(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    name = o.optString("name", ""),
                    filterKeyword = o.optString("filterKeyword", ""),
                    filterRegex = o.optString("filterRegex", ""),
                    autoDownload = o.optBoolean("autoDownload", true),
                    enabled = o.optBoolean("enabled", true),
                    lastCheckedAt = o.optLong("lastCheckedAt", 0L),
                    lastItemTitle = o.optString("lastItemTitle", ""),
                    lastItemCount = o.optInt("lastItemCount", 0),
                    error = if (o.isNull("error")) null else o.optString("error", null),
                )
                map[feed.id] = feed
            }
            refresh()
            DebugLogger.d(TAG, "피드 로드 ${map.size}건")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "피드 로드 실패", e)
        }
    }
}
