package com.borasarang.droidrelay.relay

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** Jackett/Prowlarr Torznab 검색 (T-950) */
class TorznabClient(private val context: Context) {

    data class Result(
        val title: String,
        val size: Long,
        val seeders: Int,
        val peers: Int,
        val magnet: String?,
        val url: String?,
        val indexer: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Torznab t=search — 시드순 최대 50건 */
    fun search(baseUrl: String, apiKey: String, q: String): List<Result> {
        val url = baseUrl.trimEnd('/') +
            "/api/v2.0/indexers/all/results/torznab/?t=search&q=" +
            java.net.URLEncoder.encode(q, "UTF-8") +
            "&apikey=" + java.net.URLEncoder.encode(apiKey, "UTF-8")
        val req = Request.Builder().url(url)
            .header("User-Agent", "DroidRelay/1.0")
            .build()
        DebugLogger.i(TAG, "[FEATURE] 토렌트 검색 q=${q.take(60)}")
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw SearchException("검색 실패 HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw SearchException("빈 응답")
            return parse(body).sortedByDescending { it.seeders }.take(50)
        }
    }

    internal fun parse(xml: String): List<Result> {
        val out = mutableListOf<Result>()
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = true
        val p = f.newPullParser()
        p.setInput(StringReader(xml))
        var title = ""
        var link = ""
        var enclosureUrl = ""
        var enclosureLen = 0L
        var indexer = ""
        val attrs = mutableMapOf<String, String>()
        var inItem = false
        var text = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    when (p.name) {
                        "item" -> {
                            inItem = true
                            title = ""; link = ""; enclosureUrl = ""; enclosureLen = 0L
                            indexer = ""; attrs.clear()
                        }
                        "enclosure" -> {
                            enclosureUrl = p.getAttributeValue(null, "url") ?: ""
                            enclosureLen = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                        "attr" -> {
                            val n = p.getAttributeValue(null, "name") ?: ""
                            val v = p.getAttributeValue(null, "value") ?: ""
                            if (n.isNotEmpty()) attrs[n] = v
                        }
                    }
                }
                XmlPullParser.TEXT -> text = p.text ?: ""
                XmlPullParser.END_TAG -> {
                    if (inItem) {
                        when (p.name) {
                            "title" -> title = text
                            "link" -> link = text.trim()
                            "jackettindexer" -> indexer = text.trim()
                            "prowlarrindexer" -> indexer = text.trim()
                            "item" -> {
                                inItem = false
                                val magnet = attrs["magneturl"]?.takeIf { it.startsWith("magnet:") }
                                    ?: attrs["infohash"]?.takeIf { it.isNotBlank() }?.let { "magnet:?xt=urn:btih:$it" }
                                val torrentUrl = enclosureUrl.takeIf { it.endsWith(".torrent") || ".torrent" in it }
                                    ?: link.takeIf { ".torrent" in it }
                                val size = attrs["size"]?.toLongOrNull()
                                    ?: enclosureLen.takeIf { it > 0 } ?: 0L
                                if (title.isNotBlank() && (magnet != null || torrentUrl != null)) {
                                    out.add(
                                        Result(
                                            title = title,
                                            size = size,
                                            seeders = attrs["seeders"]?.toIntOrNull() ?: 0,
                                            peers = attrs["peers"]?.toIntOrNull() ?: 0,
                                            magnet = magnet,
                                            url = torrentUrl,
                                            indexer = indexer,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    class SearchException(message: String) : Exception(message)

    companion object {
        private const val TAG = "Search"
    }
}
