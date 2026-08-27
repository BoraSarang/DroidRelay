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
    private val TITLE_RE = Pattern.compile("<title[^>]*>\\s*([^<]{1,120})</title>", Pattern.CASE_INSENSITIVE)
    private val direct = Regex("^https?://\\S+\\.(m3u8|mpd)([?#].*)?$", RegexOption.IGNORE_CASE)

    data class Found(
        val url: String,
        val title: String,
        val isDirect: Boolean,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 입력이 m3u8/mpd 직접 주소면 그대로, 웹페이지면 스니핑. 실패 시 VideoException. */
    fun analyze(input: String): Found {
        val url = input.trim()
        if (direct.containsMatchIn(url)) {
            DebugLogger.i(TAG, "[FEATURE] 직접 매니페스트 url=${url.take(90)}")
            return Found(url, "스트림 (직접 주소)", true)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw VideoException("E-AND-VID-0200", "스트림 주소(m3u8/mpd) 또는 웹페이지 URL이 아닙니다")
        }

        DebugLogger.i(TAG, "[FEATURE] 페이지 스니핑 url=${url.take(90)}")
        val html = fetch(url)
        val found = scan(html).firstOrNull() ?: throw VideoException(
            "E-AND-VID-0200",
            "페이지에서 스트림(m3u8/mpd)을 찾지 못했습니다. 스트림 주소를 직접 입력해 주세요.",
        )
        val abs = resolve(url, found)
        val title = TITLE_RE.matcher(html).run { if (find()) group(1).trim() else "스트림" }
        DebugLogger.i(TAG, "검출 성공 title='$title' url=${abs.take(90)}")
        return Found(abs, title, false)
    }

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 403) {
                        throw VideoException(
                            "E-AND-VID-0200",
                            "페이지 접근이 차단되었습니다(403). 브라우저에서 재생해 스트림(m3u8/mpd) 주소를 직접 입력해 주세요.",
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

    private fun scan(html: String): List<String> = buildList {
        val m: Matcher = MANIFEST_RE.matcher(html)
        while (m.find()) {
            val raw = m.group(2).trim()
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