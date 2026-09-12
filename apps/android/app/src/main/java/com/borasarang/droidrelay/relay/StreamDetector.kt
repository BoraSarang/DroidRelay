package com.borasarang.droidrelay.relay

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import okhttp3.OkHttpClient
import okhttp3.Request

/** 비디오 공통 예외 — 사용자 노출 에러코드 래핑 */
class VideoException(val code: String, message: String) : Exception(message)

/**
 * 범용 스트림 감지 — 웹페이지에서 m3u8/mpd 주소 스니핑 또는 직접 입력 허용.
 * CF/403 차단 등으로 페이지 접근이 불가하면 직접 m3u8 경로 입력으로 안내한다 (PLAN_v0.12 위험 절).
 */
object StreamDetector {
    private const val TAG = "StreamDet"
    private val UA = "Mozilla/5.0 (Linux; Android 13; SM-S901N) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private val MANIFEST_RE = Pattern.compile("(['\"`]\\s*)([^'\"`\\s<>]+\\.(?:m3u8|mpd)[^'\"`\\s<>]*)(['\"`])")
    private val MP4_RE = Pattern.compile("(['\"`]\\s*)([^'\"`\\s<>]+\\.(?:mp4|webm|mov)[^'\"`\\s<>]*)(['\"`])")
    private val TITLE_RE = Pattern.compile("<title[^>]*>\\s*([^<]{1,120})</title>", Pattern.CASE_INSENSITIVE)
    private val directManifest = Regex("^https?://\\S+\\.(m3u8|mpd)([?#].*)?$", RegexOption.IGNORE_CASE)
    private val directMp4 = Regex("^https?://\\S+\\.(mp4|webm|mov)([?#].*)?$", RegexOption.IGNORE_CASE)
    private val EXTINF_RE = Regex("""#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""")

    /** 해상도 선택 가능한 스트림 variant — label(예: 720p) + 실제 다운로드 URL + 프로토콜 */
    data class Quality(
        val label: String,
        val url: String,
        val protocol: String, // hls | dash | direct
    )

    data class Found(
        val url: String,
        val title: String,
        val isDirect: Boolean,
        val kind: String, // stream | mp4 | page
        val qualities: List<Quality> = emptyList(),
        val segmentsTotal: Int = 0, // HLS 세그먼트 전체 개수 (0이면 미지원/미측정)
        val durationMs: Long = 0, // HLS 총 재생 시간(ms), -progress 진행률 분모 (0이면 미지원/미측정)
    )

    /** 매니페스트 1회 수신 결과 — variant(해상도) + 실측 세그먼트 개수 + 총 재생 시간 */
    data class ManifestResult(
        val variants: List<Quality>,
        val segments: Int,
        val durationMs: Long,
    )

    /** 브라우저 세션 전달 헤더 — 메모리 전용, 영속·로그 기록 금지 (v0.23 Phase B).
     * 값이 아닌 존재 여부만 로그에 남긴다. */
    data class ExtraHeaders(
        val referer: String?,
        val cookie: String?,
    ) {
        val hasReferer: Boolean get() = !referer.isNullOrBlank()
        val hasCookie: Boolean get() = !cookie.isNullOrBlank()
    }

    const val MAX_REFERER_LEN = 2048
    const val MAX_COOKIE_LEN = 4096

    /** 입력 정제 — 초과 길이는 null 반환(무음 절단 금지, 호출자가 400으로 거부).
     * 둘 다 비면 null (헤더 미적용 경로). 순수 함수. */
    fun sanitizeExtra(referer: String?, cookie: String?): ExtraHeaders? {
        val r = referer?.trim()?.ifBlank { null }
        val c = cookie?.trim()?.ifBlank { null }
        if (r != null && r.length > MAX_REFERER_LEN) return null
        if (c != null && c.length > MAX_COOKIE_LEN) return null
        if (r == null && c == null) return null
        return ExtraHeaders(r, c)
    }

    /** 길이 초과 여부 — 라우트가 400 판정에 사용 (sanitizeExtra와 같은 기준) */
    fun isExtraTooLong(referer: String?, cookie: String?): Boolean {
        if ((referer?.trim()?.length ?: 0) > MAX_REFERER_LEN) return true
        if ((cookie?.trim()?.length ?: 0) > MAX_COOKIE_LEN) return true
        return false
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 입력이 m3u8/mpd/mp4 직접 주소면 그대로, 웹페이지면 스니핑. 실패 시 VideoException. */
    fun analyze(input: String, extra: ExtraHeaders? = null): Found {
        val url = input.trim()
        if (directMp4.containsMatchIn(url)) {
            DebugLogger.i(TAG, "[FEATURE] 직접 동영상 url=${url.take(90)} ref=${extra?.hasReferer} ck=${extra?.hasCookie}")
            return Found(url, "동영상 (직접 주소)", true, "mp4")
        }
        if (directManifest.containsMatchIn(url)) {
            DebugLogger.i(TAG, "[FEATURE] 직접 매니페스트 url=${url.take(90)} ref=${extra?.hasReferer} ck=${extra?.hasCookie}")
            val m = parseManifest(url, extra)
            return Found(url, "스트림 (직접 주소)", true, "stream", m.variants, m.segments, m.durationMs)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw VideoException("E-AND-VID-0200", "스트림 주소(m3u8/mpd) 또는 웹페이지 URL이 아닙니다")
        }

        DebugLogger.i(TAG, "[FEATURE] 페이지 스니핑 url=${url.take(90)} ref=${extra?.hasReferer} ck=${extra?.hasCookie}")
        val html = fetch(url, extra)
        val title = TITLE_RE.matcher(html).run { if (find()) group(1)?.trim().orEmpty() else "스트림" }

        // 1차: 직접적으로 선언된 mp4 동영상 (video/source/og:video/인라인 JS file 키)
        scan(html, MP4_RE).firstOrNull()?.let { raw ->
            val abs = resolve(url, raw)
            DebugLogger.i(TAG, "MP4 검출 title='$title' url=${abs.take(90)}")
            return Found(abs, title, false, "mp4")
        }

        // 2차: m3u8/mpd 매니페스트 → variant(해상도) 파싱
        val found = scan(html, MANIFEST_RE).firstOrNull() ?: throw VideoException(
            "E-AND-VID-0200",
            "페이지에서 스트림(m3u8/mpd)을 찾지 못했습니다. 스트림 주소를 직접 입력해 주세요.",
        )
        val abs = resolve(url, found)
        val m = parseManifest(abs, extra)
        DebugLogger.i(TAG, "검출 성공 title='$title' url=${abs.take(90)} quality=${m.variants.size}")
        return Found(abs, title, false, "stream", m.variants, m.segments, m.durationMs)
    }

    /** 매니페스트 URL을 GET해 variant(해상도)·세그먼트 개수·총 재생 시간을 1회 수신으로 계산.
     *  403/네트워크 실패는 삼키지 않고 VideoException을 그대로 전파한다 (E-AND-VID-0206 등).
     *  마스터면 첫 variant 1회만 추가 fetch(세그먼트/재생시간 실측). DASH는 variant 파싱만. */
    fun parseManifest(manifestUrl: String, extra: ExtraHeaders? = null): ManifestResult {
        val body = fetch(manifestUrl, extra)
        val isDash = manifestUrl.contains(".mpd", true)
        val variants = if (isDash) parseDashManifest(body, manifestUrl) else parseHlsMaster(body, manifestUrl)
        var mediaBody: String? = null
        if (!isDash) {
            mediaBody = if (countHlsSegments(body) > 0) body
            else variants.firstOrNull()?.url?.let { fetch(it, extra) }
        }
        return ManifestResult(
            variants,
            mediaBody?.let { countHlsSegments(it) } ?: 0,
            mediaBody?.let { playlistDurationMs(it) } ?: 0,
        )
    }

    /** 입력 URL이 무엇인지 판정 — kindOf: stream(m3u8/mpd) / mp4 / page(그 외 웹페이지) */
    fun kindOf(url: String): String = when {
        directMp4.containsMatchIn(url) -> "mp4"
        directManifest.containsMatchIn(url) -> "stream"
        else -> "page"
    }

    /** HLS 매니페스트 본문에서 세그먼트 개수(#EXTINF 라인 수)를 센다 — 미디어 플레이리스트 기준 */
    fun countHlsSegments(playlist: String): Int {
        var n = 0
        var lines = playlist.lineSequence()
        // #EXTINF 값이 줄바꿈으로 이어질 수 있으나, 일반적으로 EXTINF 1줄 = 세그먼트 1개
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF")) n++
            if (t.startsWith("#EXT-X-ENDLIST")) break
        }
        return n
    }

    /** HLS 미디어 플레이리스트에서 총 재생 시간(ms)을 센다 (#EXTINF 합) — 미디어 플레이리스트 기준 */
    fun playlistDurationMs(playlist: String): Long {
        var total = 0L
        for (line in playlist.lineSequence()) {
            val t = line.trim()
            val v = EXTINF_RE.find(t)?.groupValues?.get(1) ?: continue
            total += (v.toDouble() * 1000).toLong()
            if (t.startsWith("#EXT-X-ENDLIST")) break
        }
        return total
    }

    /** HLS 마스터 매니페스트에서 #EXT-X-STREAM-INF(RESOLUTION) variant를 해상도별로 추출 */
    fun parseHlsMaster(manifest: String, baseUrl: String): List<Quality> {
        val lines = manifest.lineSequence().map { it.trim() }.toList()
        val out = mutableListOf<Quality>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                // 다음 줄이 variant URI (주석/빈 줄이 올 수 있으므로 다음 비주석 라인 탐색)
                var j = i + 1
                while (j < lines.size && (lines[j].startsWith("#") || lines[j].isBlank())) j++
                if (j < lines.size) {
                    val uri = lines[j].split(',')[0].trim()
                    val label = res?.let { "${it.groupValues[2]}p" } ?: "자동"
                    out.add(Quality(label, resolve(baseUrl, uri), "hls"))
                }
                i = j + 1
            } else {
                i++
            }
        }
        return out.distinctBy { it.url }
    }

    /** DASH 매니페스트에서 Representation 해상도(w/h) + BaseURL 추출 */
    fun parseDashManifest(manifest: String, baseUrl: String): List<Quality> {
        val out = mutableListOf<Quality>()
        val repRe = Regex(
            "<Representation[^>]*>(.*?)</Representation>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in repRe.findAll(manifest)) {
            val tag = m.value
            val w = Regex("width=\"(\\d+)\"").find(tag)?.groupValues?.get(1)
            val h = Regex("height=\"(\\d+)\"").find(tag)?.groupValues?.get(1)
            val base = Regex("<BaseURL[^>]*>([^<]+)</BaseURL>", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)?.trim()?.takeUnless { it.isBlank() }
                ?: Regex("baseURL=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
            if (h != null && base != null) {
                out.add(Quality("${h}p", resolve(baseUrl, base), "dash"))
            }
        }
        return out
    }

    private fun fetch(url: String, extra: ExtraHeaders? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/vnd.apple.mpegurl;q=0.9,application/dash+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            // extra referer가 있으면 사이트 세션 것으로 교체, 없으면 기본값 (값은 로그 금지)
            .header("Referer", extra?.referer ?: "https://www.google.com/")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
        if (extra?.hasCookie == true) builder.header("Cookie", extra.cookie!!)
        val req = builder.build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 403) {
                        throw VideoException(
                            "E-AND-VID-0206",
                            "해당 사이트가 브라우저 외 접근을 차단하고 있습니다.",
                        )
                    }
                    throw VideoException(
                        "E-AND-VID-0200",
                        "페이지 응답 오류(HTTP ${resp.code}). 스트림 주소를 직접 입력해 주세요.",
                    )
                }
                return resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            DebugLogger.e(TAG, "페이지 조회 실패 (E-AND-VID-0100)", e)
            throw VideoException("E-AND-VID-0100", "페이지를 조회하지 못했습니다 (${e.message ?: "네트워크"})")
        }
    }

    private fun scan(html: String, pattern: Pattern): List<String> = buildList {
        val m: Matcher = pattern.matcher(html)
        while (m.find()) {
            val raw = m.group(2)?.trim().orEmpty()
            val decoded = decodeEntities(raw)
            add(decoded)
            if (size >= 5) break
        }
    }

    private fun resolve(pageUrl: String, href: String): String = try {
        URI(pageUrl).resolve(href).toString()
    } catch (_: Exception) {
        if (href.startsWith("http")) href else "$pageUrl$href"
    }

    private fun decodeEntities(s: String): String = s
        .replace("\\u002F", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("\\/", "/")
}