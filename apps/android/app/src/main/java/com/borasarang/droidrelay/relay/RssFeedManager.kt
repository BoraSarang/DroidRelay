package com.borasarang.droidrelay.relay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class RssFeedManager(
    private val context: Context,
) {
    private val TAG = "RssFeedMgr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var pollingJob: kotlinx.coroutines.Job? = null
    @Volatile private var isRunning = false

    /** 폴링 시작 (15분 간격) */
    fun start() {
        if (isRunning) return
        isRunning = true
        RssFeedRepository.init(context)
        pollingJob = scope.launch {
            DebugLogger.i(TAG, "RSS 폴링 시작 (15분 간격)")
            while (isRunning) {
                checkAllFeeds()
                delay(15 * 60 * 1000L) // 15분
            }
        }
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        DebugLogger.i(TAG, "RSS 폴링 중지")
    }

    /** 전체 피드 즉시 확인 */
    suspend fun checkAllFeeds() {
        val feeds = RssFeedRepository.all().filter { it.enabled }
        if (feeds.isEmpty()) return
        DebugLogger.i(TAG, "RSS 피드 확인 시작 (${feeds.size}건)")
        for (feed in feeds) {
            try {
                checkFeed(feed)
            } catch (e: Exception) {
                DebugLogger.e(TAG, "피드 확인 실패 id=${feed.id} url=${feed.url}", e)
                RssFeedRepository.update(feed.id) {
                    it.copy(lastCheckedAt = System.currentTimeMillis(), error = e.message?.take(100))
                }
            }
            delay(1000) // 피드 간 1초 간격
        }
    }

    /** 개별 피드 확인 + 자동 다운로드 */
    private suspend fun checkFeed(feed: RssFeed) {
        val items = fetchFeed(feed.url)
        if (items.isEmpty()) {
            RssFeedRepository.update(feed.id) {
                it.copy(lastCheckedAt = System.currentTimeMillis(), error = "항목 없음")
            }
            return
        }

        // 필터 적용
        val filtered = items.filter { item ->
            matchesFilter(item, feed.filterKeyword, feed.filterRegex)
        }

        // 이미 다운로드한 항목 필터 (lastItemTitle 기반)
        val newItems = if (feed.lastItemTitle.isNotEmpty()) {
            val lastIdx = items.indexOfFirst { it.title == feed.lastItemTitle }
            if (lastIdx >= 0) items.subList(0, lastIdx) else filtered.take(5)
        } else {
            filtered.take(5) // 첫 실행 시 최대 5개만
        }

        // 자동 다운로드
        var downloadedCount = 0
        if (feed.autoDownload && newItems.isNotEmpty()) {
            for (item in newItems.reversed()) { // 오래된 것부터
                val url = item.enclosureUrl ?: item.link
                if (url.isNotBlank()) {
                    val existingJob = JobsRepository.jobs.value.find { it.url == url }
                    val existingTorrent = TorrentRepository.all().any { it.magnet == url }
                    if (existingJob == null && !existingTorrent) {
                        val ok = when {
                            url.startsWith("magnet:") -> {
                                runCatching { RelayApp.getTorrent(context).addMagnet(url) }.isSuccess
                            }
                            url.endsWith(".torrent") || url.substringBefore('?').endsWith(".torrent") -> {
                                fetchTorrentFile(url)
                            }
                            else -> {
                                RelayApp.get(context).enqueue(url)
                                true
                            }
                        }
                        if (ok) {
                            downloadedCount++
                            DebugLogger.i(TAG, "RSS 자동 다운로드 id=${feed.id} title='${item.title}' kind=${url.substringBefore(':')} url=${url.take(80)}")
                        } else {
                            DebugLogger.w(TAG, "RSS 항목 다운로드 실패 id=${feed.id} url=${url.take(80)}")
                        }
                    }
                }
            }
        }

        // 상태 업데이트
        RssFeedRepository.update(feed.id) {
            it.copy(
                lastCheckedAt = System.currentTimeMillis(),
                lastItemTitle = items.firstOrNull()?.title ?: it.lastItemTitle,
                lastItemCount = items.size,
                error = null,
            )
        }
        DebugLogger.i(TAG, "피드 확인 완료 id=${feed.id} name='${feed.name}' 항목=${items.size} 신규=${newItems.size} 다운로드=$downloadedCount")
    }

    /** RSS/Atom 피드 가져오기 + 파싱 */
    private fun fetchFeed(url: String): List<RssFeedItem> {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .header("Accept", "application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .build()
        val response = client.newCall(request).execute()
        val code = response.code
        val body = response.body?.string() ?: ""
        response.close()

        if (code !in 200..299) {
            val hint = if (code == 403) " (Cloudflare/보안 챌린지 차단 가능)" else ""
            throw Exception("HTTP $code$hint")
        }

        val trimmed = body.trimStart()
        if (!trimmed.startsWith("<?xml") && !trimmed.startsWith("<rss") && !trimmed.startsWith("<feed") && trimmed.startsWith("<")) {
            throw Exception("HTML 응답 — XML 피드가 아닙니다 (보안 챌린지 차단 가능, ${body.take(80).replace("\n", " ")}…)".take(200))
        }

        return parseFeed(body)
    }

    /** RSS 2.0 / Atom 파싱 */
    private fun parseFeed(xml: String): List<RssFeedItem> {
        val items = mutableListOf<RssFeedItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var inEntry = false
            var title = ""
            var link = ""
            var pubDate = ""
            var encUrl = ""
            var encType = ""
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name ?: ""
                        currentTag = name
                        when (name) {
                            "item" -> { inItem = true; title = ""; link = ""; pubDate = ""; encUrl = ""; encType = "" }
                            "entry" -> { inEntry = true; title = ""; link = ""; pubDate = ""; encUrl = ""; encType = "" }
                            "enclosure" -> {
                                if (inItem || inEntry) {
                                    encUrl = parser.getAttributeValue(null, "url") ?: ""
                                    encType = parser.getAttributeValue(null, "type") ?: ""
                                }
                            }
                            "link" -> {
                                if (inEntry) {
                                    // Atom: link의 href 속성
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    if (href.isNotBlank()) link = href
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotBlank()) {
                            when (currentTag) {
                                "title" -> if (inItem || inEntry) title = text
                                "link" -> if (inItem) link = text
                                "pubDate", "published", "updated" -> if (inItem || inEntry) pubDate = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        when (name) {
                            "item" -> {
                                inItem = false
                                if (title.isNotBlank() || link.isNotBlank()) {
                                    items.add(RssFeedItem(title = title, link = link, pubDate = pubDate, enclosureUrl = encUrl, enclosureType = encType))
                                }
                            }
                            "entry" -> {
                                inEntry = false
                                if (title.isNotBlank() || link.isNotBlank()) {
                                    items.add(RssFeedItem(title = title, link = link, pubDate = pubDate, enclosureUrl = encUrl, enclosureType = encType))
                                }
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "피드 파싱 실패", e)
        }
        return items
    }

    /** .torrent 파일 내려받아 토렌트 엔진에 추가 */
    private fun fetchTorrentFile(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code !in 200..299) {
                    DebugLogger.w(TAG, ".torrent 다운로드 실패 HTTP ${resp.code} url=${url.take(80)}")
                    return false
                }
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.size < 8) return false
                val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "rss-${System.currentTimeMillis()}.torrent" }
                RelayApp.getTorrent(context).addTorrentFile(bytes, name)
                true
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, ".torrent 추가 실패 url=${url.take(80)}", e)
            false
        }
    }

    /** 필터 매칭 */
    private fun matchesFilter(item: RssFeedItem, keyword: String, regex: String): Boolean {
        val text = "${item.title} ${item.link}"
        if (keyword.isNotBlank() && !text.contains(keyword, ignoreCase = true)) return false
        if (regex.isNotBlank()) {
            try {
                if (!Regex(regex).containsMatchIn(text)) return false
            } catch (_: Exception) { }
        }
        return true
    }
}
