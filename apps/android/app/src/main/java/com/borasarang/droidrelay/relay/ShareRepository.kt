package com.borasarang.droidrelay.relay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 만료 공유 링크 저장소 — 토큰이 곧 권한 (T-951) */
object ShareRepository {
    private const val TAG = "Share"
    private const val TOKEN_LENGTH = 16
    private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    data class ShareLink(
        val token: String,
        val path: String,
        val expiresAt: Long,
        val createdAt: Long,
    )

    private val map = ConcurrentHashMap<String, ShareLink>()
    @Volatile private var loaded = false

    private fun file(context: Context) = java.io.File(context.filesDir, "share_links.json")

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        // loaded 는 성공 뒤에만 켠다 — 앞세우면 읽기 실패 시 재시도 없이
        // 공유 링크가 전부 사라진 채 "링크 없음" 만 남는다.
        runCatching {
            val f = file(context)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val now = System.currentTimeMillis()
            (0 until arr.length()).forEach { i ->
                val o = arr.getJSONObject(i)
                val link = ShareLink(
                    token = o.optString("token"),
                    path = o.optString("path"),
                    expiresAt = o.optLong("expiresAt"),
                    createdAt = o.optLong("createdAt"),
                )
                if (link.token.isNotBlank() && link.expiresAt > now) map[link.token] = link
            }
        }.onFailure { DebugLogger.w(TAG, "공유 링크 복원 실패: ${it.message}") }
        loaded = true
    }

    /** 원자 쓰기 — 생성(create)과 만료 정리(all/get)가 동시에 persist 를 부른다 */
    @Synchronized
    private fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            map.values.forEach { l ->
                arr.put(JSONObject().apply {
                    put("token", l.token)
                    put("path", l.path)
                    put("expiresAt", l.expiresAt)
                    put("createdAt", l.createdAt)
                })
            }
            val f = file(context)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            // 절단적 writeText 는 동시 쓰기 시 파일이 깨진다 — 원자 rename 으로 교체
            tmp.renameTo(f) || run { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }.onFailure { DebugLogger.w(TAG, "공유 링크 저장 실패: ${it.message}") }
    }

    /** 만료 정리 — 유효 목록 반환 */
    fun all(context: Context): List<ShareLink> {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val expired = map.values.filter { it.expiresAt <= now }.map { it.token }
        expired.forEach { map.remove(it) }
        if (expired.isNotEmpty()) persist(context)
        return map.values.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun create(context: Context, path: String, hours: Int): ShareLink {
        ensureLoaded(context)
        val h = hours.coerceIn(1, 720)
        val token = newToken()
        val now = System.currentTimeMillis()
        val link = ShareLink(token, path, now + h * 3_600_000L, now)
        map[token] = link
        persist(context)
        DebugLogger.i(TAG, "[FEATURE] 공유 링크 발급 path=$path ${h}시간")
        return link
    }

    /**
     * 공유 토큰 생성 — SecureRandom.
     * 토큰 자체가 유일한 권한이다 (/s/{token} 은 Basic Auth 를 우회).
     * kotlin.random.Random 은 예측 가능해 16자 토큰을 사실상 추측할 수 있었다.
     * 알파벳 62^16 ≈ 2^95.5 로 충분하다.
     */
    private fun newToken(): String {
        val chars = TOKEN_ALPHABET
        val sb = StringBuilder(TOKEN_LENGTH)
        val rnd = java.security.SecureRandom()
        while (sb.length < TOKEN_LENGTH) {
            val v = rnd.nextInt(chars.length)
            sb.append(chars[v])
        }
        return sb.toString()
    }

    fun get(context: Context, token: String): ShareLink? {
        ensureLoaded(context)
        val link = map[token] ?: return null
        if (link.expiresAt <= System.currentTimeMillis()) {
            map.remove(token)
            persist(context)
            return null
        }
        return link
    }

    fun delete(context: Context, token: String): Boolean {
        ensureLoaded(context)
        val removed = map.remove(token) != null
        if (removed) persist(context)
        return removed
    }
}
