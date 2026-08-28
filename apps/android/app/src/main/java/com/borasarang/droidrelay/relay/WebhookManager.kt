package com.borasarang.droidrelay.relay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 웹훅/콜백 매니저.
 * 다운로드 완료/실패 시 지정된 URL로 POST 콜백 전송.
 * HMAC-SHA256 서명 + 지수 백오프 재시도.
 */
class WebhookManager(private val context: Context) {

    private val TAG = "Webhook"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 웹훅 전송 (비동기).
     * @param event 이벤트 타입 (download_complete, download_failed, torrent_complete 등)
     * @param payload 전송할 데이터
     */
    suspend fun send(
        event: String,
        payload: JSONObject,
        settings: AppSettings,
    ) = withContext(Dispatchers.IO) {
        if (!settings.webhookEnabled || settings.webhookUrl.isBlank()) return@withContext

        val body = JSONObject().apply {
            put("event", event)
            put("timestamp", System.currentTimeMillis())
            put("data", payload)
        }

        val bodyStr = body.toString()
        val signature = if (settings.webhookSecret.isNotBlank()) {
            hmacSha256(bodyStr, settings.webhookSecret)
        } else null

        val requestBody = bodyStr
            .toRequestBody("application/json".toMediaTypeOrNull())

        val requestBuilder = Request.Builder()
            .url(settings.webhookUrl)
            .post(requestBody)
            .header("User-Agent", "DroidRelay/0.9 Webhook")
            .header("X-DroidRelay-Event", event)
        if (signature != null) {
            requestBuilder.header("X-DroidRelay-Signature", "sha256=$signature")
        }

        // 지수 백오프 재시도 (최대 3회)
        var lastException: Exception? = null
        for (attempt in 0..2) {
            try {
                val response = client.newCall(requestBuilder.build()).execute()
                val success = response.isSuccessful
                response.close()

                if (success) {
                    DebugLogger.i(TAG, "웹훅 전송 성공 event=$event url=${settings.webhookUrl.take(60)}")
                    return@withContext
                } else {
                    DebugLogger.w(TAG, "웹훅 전송 실패 event=$event code=${response.code} attempt=$attempt")
                }
            } catch (e: Exception) {
                lastException = e
                DebugLogger.e(TAG, "웹훅 전송 실패 event=$event attempt=$attempt", e)
            }
            if (attempt < 2) {
                kotlinx.coroutines.delay(1000L * (1 shl attempt)) // 1s, 2s
            }
        }

        // 데드레터 큐에 저장
        saveToDeadLetterQueue(event, bodyStr, lastException?.message)
    }

    /**
     * 데드레터 큐에 저장 (재전송용).
     */
    private fun saveToDeadLetterQueue(event: String, body: String, error: String?) {
        try {
            val file = File(context.filesDir, "webhook_deadletter.json")
            val arr = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
            } else JSONArray()

            arr.put(JSONObject().apply {
                put("event", event)
                put("body", body)
                put("error", error ?: "")
                put("createdAt", System.currentTimeMillis())
                put("retries", 3)
            })

            // 최대 50건 유지
            while (arr.length() > 50) {
                arr.remove(0)
            }

            file.writeText(arr.toString())
            DebugLogger.d(TAG, "데드레터 큐 저장 event=$event")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "데드레터 큐 저장 실패", e)
        }
    }

    /**
     * 데드레터 큐 전송 재시도.
     */
    suspend fun retryDeadLetters(settings: AppSettings) = withContext(Dispatchers.IO) {
        if (!settings.webhookEnabled || settings.webhookUrl.isBlank()) return@withContext

        val file = File(context.filesDir, "webhook_deadletter.json")
        if (!file.exists()) return@withContext

        try {
            val arr = JSONArray(file.readText())
            val remaining = JSONArray()

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val retries = item.optInt("retries", 0)
                if (retries <= 0) continue

                val bodyStr = item.optString("body", "")
                val signature = if (settings.webhookSecret.isNotBlank()) {
                    hmacSha256(bodyStr, settings.webhookSecret)
                } else null

                val requestBody = bodyStr
                    .toRequestBody("application/json".toMediaTypeOrNull())

                val requestBuilder = Request.Builder()
                    .url(settings.webhookUrl)
                    .post(requestBody)
                    .header("User-Agent", "DroidRelay/0.9 Webhook")
                if (signature != null) {
                    requestBuilder.header("X-DroidRelay-Signature", "sha256=$signature")
                }

                try {
                    val response = client.newCall(requestBuilder.build()).execute()
                    response.close()
                    if (!response.isSuccessful) {
                        item.put("retries", retries - 1)
                        remaining.put(item)
                    }
                } catch (_: Exception) {
                    item.put("retries", retries - 1)
                    remaining.put(item)
                }
            }

            file.writeText(remaining.toString())
            DebugLogger.d(TAG, "데드레터 큐 정리 ${arr.length()}건 → ${remaining.length()}건")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "데드레터 큐 처리 실패", e)
        }
    }

    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
