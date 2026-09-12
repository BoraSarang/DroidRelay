package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** 커뮤니티 트래커 자동 동기 (v0.23 Phase B, Motrix 트래커 관리 축소판).
 *
 * - 24시간 파일 캐시, 실패 시 번들 목록 폴백, 절대 throw 금지
 * - 주입은 TorrentEngine.applyExtraTrackers가 담당 (게이트 내 + try-catch)
 */
object TrackerListProvider {
    const val SOURCE_URL =
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    const val MAX_TRACKERS = 40
    const val CACHE_TTL_MS = 24L * 3600 * 1000

    /** 번들 폴백 — 캐시도 네트워크도 없을 때 */
    val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.bittor.pw:1337/announce",
    )

    /** 목록 파싱 — http/https/udp만, trim, dedupe, cap. 순수 함수. */
    fun parseList(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.startsWith("http://") && !line.startsWith("https://") && !line.startsWith("udp://")) continue
            if (line.any { it.isWhitespace() || it.code < 32 }) continue
            out.add(line)
            if (out.size >= MAX_TRACKERS) break
        }
        return out.toList()
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, "trackers.txt")

    /** 캐시(유효) → 번들 순으로 반환. never throw. */
    fun getCached(context: Context): List<String> = getCached(cacheFile(context))

    internal fun getCached(f: File): List<String> {
        return try {
            if (!f.exists()) return DEFAULT_TRACKERS
            if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return DEFAULT_TRACKERS
            parseList(f.readText()).ifEmpty { DEFAULT_TRACKERS }
        } catch (e: Exception) {
            DebugLogger.e("Tracker", "캐시 읽기 실패 — 번들 사용", e)
            DEFAULT_TRACKERS
        }
    }

    fun cacheAgeMs(context: Context): Long {
        val f = cacheFile(context)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else -1
    }

    /** 원격 새로고침 — 성공 시 캐시 저장, 실패 시 기존 캐시/번들. never throw. */
    suspend fun refresh(context: Context): List<String> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val body = client.newCall(Request.Builder().url(SOURCE_URL).build())
                .execute().use { resp ->
                    if (resp.code !in 200..299) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            val list = parseList(body)
            if (list.isEmpty()) throw IllegalStateException("빈 목록")
            cacheFile(context).writeText(list.joinToString("\n"))
            DebugLogger.i("Tracker", "[FEATURE] 트래커 동기 완료 ${list.size}개")
            list
        } catch (e: Exception) {
            DebugLogger.e("Tracker", "트래커 동기 실패 — 기존 목록 유지", e)
            getCached(context)
        }
    }
}
