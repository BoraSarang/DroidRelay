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
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 입력이 m3u8/mpd/mp4 직접 주소면 그대로, 웹페이지면 스니핑. 실패 시 VideoException. */
    fun analyze(input: String): Found {
        val url = input.trim()
        if (directMp4.containsMatchIn(url)) {
            DebugLogger.i(TAG, "[FEATURE] 직접 동영상 url=${url.take(90)}")
            return Found(url, "동영상 (직접 주소)", true, "mp4")
        }
        if (directManifest.containsMatchIn(url)) {
            DebugLogger.i(TAG, "[FEATURE] 직접 매니페스트 url=${url.take(90)}")
            val qs = parseManifestVariants(url)
            return Found(url, "스트림 (직접 주소)", true, "stream", qs)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw VideoException("E-AND-VID-0200", "스트림 주소(m3u8/mpd) 또는 웹페이지 URL이 아닙니다")
        }

        DebugLogger.i(TAG, "[FEATURE] 페이지 스니핑 url=${url.take(90)}")
        val html = fetch(url)
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
        val qs = parseManifestVariants(abs)
        DebugLogger.i(TAG, "검출 성공 title='$title' url=${abs.take(90)} quality=${qs.size}")
        return Found(abs, title, false, "stream", qs)
    }

    /** 매니페스트 URL을 GET해 variant(해상도) 및 프로토콜 판정 */
    private fun parseManifestVariants(manifestUrl: String): List<Quality> {
        val protocol = when {
            manifestUrl.contains(".mpd", true) -> "dash"
            else -> "hls"
        }
        return try {
            val body = fetch(manifestUrl)
            if (body.isBlank()) emptyList()
            else when (protocol) {
                "dash" -> parseDashManifest(body, manifestUrl)
                else -> parseHlsMaster(body, manifestUrl)
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "매니페스트 variant 파싱 실패 (${e.message}) → 원본만 사용")
            emptyList()
        }
    }

    /** 입력 URL이 무엇인지 판정 — kindOf: stream(m3u8/mpd) / mp4 / page(그 외 웹페이지) */
    fun kindOf(url: String): String = when {
        directMp4.containsMatchIn(url) -> "mp4"
        directManifest.containsMatchIn(url) -> "stream"
        else -> "page"
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

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/vnd.apple.mpegurl;q=0.9,application/dash+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "https://www.google.com/")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 403) {
                        throw VideoException(
                            "E-AND-VID-0200",
                            "접근이 차단되었습니다(403 — Cloudflare/봇 차단). 브라우저에서 재생해 m3u8/mpd 주소를 직접 복사해 입력해 주세요.",
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