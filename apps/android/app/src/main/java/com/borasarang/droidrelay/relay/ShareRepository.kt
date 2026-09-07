package com.borasarang.droidrelay.relay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 만료 공유 링크 저장소 — 토큰이 곧 권한 (T-951) */
object ShareRepository {
    private const val TAG = "Share"

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
        loaded = true
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
    }

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
            file(context).writeText(arr.toString())
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

    fun create(context: Context, path: String, hours: Int): ShareLink {
        ensureLoaded(context)
        val h = hours.coerceIn(1, 720)
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val token = (1..16).map { chars.random() }.joinToString("")
        val now = System.currentTimeMillis()
        val link = ShareLink(token, path, now + h * 3_600_000L, now)
        map[token] = link
        persist(context)
        DebugLogger.i(TAG, "[FEATURE] 공유 링크 발급 path=$path ${h}시간")
        return link
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
