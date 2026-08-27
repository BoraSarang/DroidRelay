package com.borasarang.droidrelay.relay

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** yt-dlp 서버 클라이언트 — 서버에서 YouTube URL 분석/다운로드 URL 획득 */
object YtDlpClient {
    private const val TAG = "YtDlp"
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 서버 /analyze 엔드포인트 호출 — 포맷 목록 반환 */
    fun analyze(serverUrl: String, apiKey: String?, videoUrl: String): JSONObject {
        val base = serverUrl.removeSuffix("/")
        val reqBody = JSONObject().apply { put("url", videoUrl) }.toString()
        val request = Request.Builder()
            .url("$base/analyze")
            .post(reqBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .apply { if (apiKey != null && apiKey.isNotBlank()) header("X-API-Key", apiKey) }
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: "HTTP ${response.code}"
            throw RuntimeException("분석 실패: $err")
        }
        val body = response.body?.string() ?: throw RuntimeException("빈 응답")
        return JSONObject(body)
    }

    /** 서버 /download 엔드포인트 호출 — 직접 다운로드 URL 리스트 반환 (비디오+오디오) */
    fun getDownloadUrls(serverUrl: String, apiKey: String?, videoUrl: String, formatId: String): List<String> {
        val base = serverUrl.removeSuffix("/")
        val reqBody = JSONObject().apply {
            put("url", videoUrl)
            put("format", formatId)
        }.toString()
        val request = Request.Builder()
            .url("$base/download")
            .post(reqBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .apply { if (apiKey != null && apiKey.isNotBlank()) header("X-API-Key", apiKey) }
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: "HTTP ${response.code}"
            throw RuntimeException("다운로드 URL 획득 실패: $err")
        }
        val body = response.body?.string() ?: throw RuntimeException("빈 응답")
        val json = JSONObject(body)
        val urlsArray = json.optJSONArray("urls")
        if (urlsArray == null) throw RuntimeException("응답에 urls 필드 없음: $body")
        return (0 until urlsArray.length()).map { urlsArray.getString(it) }
    }
}