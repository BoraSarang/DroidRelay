package com.borasarang.droidrelay.relay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Debrid 제공자 */
enum class DebridProvider(val displayName: String, val baseUrl: String) {
    REALDEBRID("Real-Debrid", "https://api.real-debrid.com/rest/1.0"),
    ALLDEBRID("AllDebrid", "https://api.alldebrid.com/v4"),
    PREMIUMIZE("Premiumize", "https://api.premiumize.me/pm-api/v1"),
}

/** 언리스트링크 결과 */
data class DebridLink(
    val id: String,
    val filename: String,
    val filesize: Long,
    val directUrl: String,
    val chunks: Int = 1,
    val downloadSpeed: Long = 0,
    val streamable: Boolean = false,
)

/**
 * RealDebrid / AllDebrid / Premiumize 공통 클라이언트.
 * OkHttp 사용 (DownloadEngine, RssFeedManager와 동일 패턴).
 */
class DebridClient(private val context: Context) {

    private val TAG = "Debrid"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 언리스트링크 변환 — 클라우드 직접 다운로드 URL로 변환.
     * @param url 변환할 URL (일반 HTTP 링크 또는 토렌트 내부 파일 링크)
     * @param provider 사용할 Debrid 제공자
     * @param apiKey API 키
     * @return DebridLink (directUrl 포함)
     * @throws Exception API 실패 시
     */
    suspend fun unrestrict(
        url: String,
        provider: DebridProvider,
        apiKey: String,
    ): DebridLink = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        DebugLogger.i(TAG, "언리스트링크 요청 provider=${provider.name} url=${url.take(80)}")

        val (apiUrl, headers) = when (provider) {
            DebridProvider.REALDEBRID -> Pair(
                "${provider.baseUrl}/unrestrict/link",
                mapOf("Authorization" to "Bearer $apiKey"),
            )
            DebridProvider.ALLDEBRID -> Pair(
                "${provider.baseUrl}/link/unlock",
                mapOf("Authorization" to "Bearer $apiKey"),
            )
            DebridProvider.PREMIUMIZE -> Pair(
                "${provider.baseUrl}/link/unlock",
                mapOf("Authorization" to "Bearer $apiKey"),
            )
        }

        val bodyJson = JSONObject().apply {
            put("link", url)
        }

        val requestBody = bodyJson.toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        val requestBuilder = Request.Builder().url(apiUrl).post(requestBody)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        DebugLogger.d(TAG, "API 호출 POST $apiUrl")

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""
        response.close()
        DebugLogger.d(TAG, "API 응답 code=${response.code} bodyLen=${responseBody.length} ${System.currentTimeMillis() - t0}ms")

        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(responseBody).optString("error", "API 오류 ${response.code}")
            } catch (_: Exception) {
                "API 오류 ${response.code}: ${responseBody.take(200)}"
            }
            DebugLogger.e(TAG, "언리스트링크 실패 code=${response.code} msg=$errorMsg")
            throw DebridException(errorMsg, response.code)
        }

        val result = parseResponse(responseBody, provider)
        DebugLogger.i(TAG, "언리스트링크 완료 id=${result.id} file=${result.filename} size=${result.filesize} ${System.currentTimeMillis() - t0}ms")
        result
    }

    /**
     * 계정 정보 확인 (연결 테스트용).
     */
    suspend fun checkAccount(
        provider: DebridProvider,
        apiKey: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val apiUrl = when (provider) {
            DebridProvider.REALDEBRID -> "${provider.baseUrl}/user"
            DebridProvider.ALLDEBRID -> "${provider.baseUrl}/user"
            DebridProvider.PREMIUMIZE -> "${provider.baseUrl}/account/info"
        }
        DebugLogger.d(TAG, "계정 확인 GET $apiUrl")

        val requestBuilder = Request.Builder().url(apiUrl).get()
        requestBuilder.header("Authorization", "Bearer $apiKey")

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: "{}"
        response.close()
        DebugLogger.d(TAG, "계정 응답 code=${response.code}")

        if (!response.isSuccessful) {
            throw DebridException("계정 확인 실패: ${response.code}", response.code)
        }

        JSONObject(responseBody)
    }

    private fun parseResponse(body: String, provider: DebridProvider): DebridLink {
        val json = JSONObject(body)

        return when (provider) {
            DebridProvider.REALDEBRID -> DebridLink(
                id = json.optString("id", ""),
                filename = json.optString("filename", "unknown"),
                filesize = json.optLong("filesize", 0),
                directUrl = json.optString("link", ""),
                chunks = json.optInt("chunks", 1),
                downloadSpeed = json.optLong("download_speed", 0),
                streamable = json.optBoolean("streamable", false),
            )
            DebridProvider.ALLDEBRID -> {
                val data = json.optJSONObject("data") ?: json
                DebridLink(
                    id = data.optString("id", ""),
                    filename = data.optString("filename", "unknown"),
                    filesize = data.optLong("filesize", 0),
                    directUrl = data.optString("link", ""),
                    chunks = data.optInt("chunks", 1),
                    downloadSpeed = data.optLong("download_speed", 0),
                    streamable = data.optBoolean("streamable", false),
                )
            }
            DebridProvider.PREMIUMIZE -> {
                val content = json.optJSONObject("content") ?: json
                DebridLink(
                    id = content.optString("link", "").hashCode().toString(),
                    filename = content.optString("filename", "unknown"),
                    filesize = content.optLong("size", 0),
                    directUrl = content.optString("link", ""),
                    chunks = 1,
                    streamable = content.optBoolean("streamable", false),
                )
            }
        }
    }
}

class DebridException(message: String, val code: Int = 0) : Exception(message)
