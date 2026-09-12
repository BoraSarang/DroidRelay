package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 트래커 도달성 프로빙 (v0.26 Phase E).
 *
 * - UDP: BEP15 connect 핸드셰이크 (magic + action0 + txId → action0 + txId 응답 검증)
 * - HTTP/HTTPS: TCP connect만 (HTTP 요청 없음 — 트래커 통계 오염 방지)
 * - URL별 try-catch, never throw. 측정은 호출자 scope에서 백그라운드로 수행.
 */
data class ProbeResult(
    val url: String,
    val reachable: Boolean,
    val rttMs: Long,
)

data class TrackerEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
)

object TrackerProbe {
    const val PROBE_TIMEOUT_MS = 4000
    const val PROBE_PARALLELISM = 8
    const val PROBE_CACHE_TTL_MS = 24L * 3600 * 1000
    private const val UDP_MAGIC = 0x41727101980L

    /** URL → 엔드포인트. 순수 함수 (기본포트 http80/https443, udp 필수 포트). */
    fun parseEndpoint(url: String): TrackerEndpoint? {
        return try {
            val u = java.net.URI(url.trim())
            val scheme = (u.scheme ?: "").lowercase()
            val host = u.host ?: return null
            if (host.isBlank()) return null
            val port = when {
                u.port > 0 -> u.port
                scheme == "http" -> 80
                scheme == "https" -> 443
                else -> return null // udp는 포트 필수
            }
            if (scheme != "http" && scheme != "https" && scheme != "udp") return null
            TrackerEndpoint(scheme, host, port)
        } catch (_: Exception) {
            null
        }
    }

    /** UDP connect 요청 16바이트 구성. 순수 함수. */
    fun buildUdpConnectRequest(txId: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(UDP_MAGIC)
            .putInt(0) // action: connect
            .putInt(txId)
            .array()

    /** UDP connect 응답 검증 — action0 + 동일 txId. 순수 함수. */
    fun parseUdpConnectResponse(bytes: ByteArray, txId: Int): Boolean {
        if (bytes.size < 16) return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return buf.int == 0 && buf.int == txId
    }

    /** 단일 URL 측정 — 실패 시 unreachable. 블로킹 I/O (IO 디스패처에서 호출). */
    fun probeOne(url: String, timeoutMs: Int = PROBE_TIMEOUT_MS): ProbeResult {
        val ep = parseEndpoint(url) ?: return ProbeResult(url, false, -1)
        val t0 = System.currentTimeMillis()
        val ok = try {
            if (ep.scheme == "udp") probeUdp(ep.host, ep.port, timeoutMs)
            else probeTcp(ep.host, ep.port, timeoutMs)
        } catch (_: Exception) {
            false
        }
        return ProbeResult(url, ok, if (ok) System.currentTimeMillis() - t0 else -1)
    }

    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return s.isConnected
        }
    }

    private fun probeUdp(host: String, port: Int, timeoutMs: Int): Boolean {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val txId = (Math.random() * Int.MAX_VALUE).toInt()
            val req = buildUdpConnectRequest(txId)
            sock.send(DatagramPacket(req, req.size, InetSocketAddress(host, port)))
            val buf = ByteArray(16)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return parseUdpConnectResponse(resp.data.copyOf(resp.length), txId)
        }
    }

    /** 병렬 측정 — URL별 격리, never throw. */
    suspend fun probeAll(
        urls: List<String>,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
        parallelism: Int = PROBE_PARALLELISM,
    ): List<ProbeResult> = coroutineScope {
        val sem = Semaphore(parallelism)
        urls.map { url ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching { probeOne(url, timeoutMs) }
                        .getOrDefault(ProbeResult(url, false, -1))
                }
            }
        }.awaitAll()
    }

    // ── 프로브 캐시 (수동 직렬화, never throw) ──

    private fun probeFile(context: Context): File = File(context.filesDir, "trackers_probe.txt")

    fun encodeProbe(results: List<ProbeResult>, now: Long = System.currentTimeMillis()): String =
        results.filter { !it.url.contains('|') }.joinToString("\n") { r ->
            "${r.url}|${if (r.reachable) 1 else 0}|${r.rttMs}|$now"
        }

    data class CachedProbe(val result: ProbeResult, val at: Long)

    fun decodeProbe(text: String?): Map<String, CachedProbe> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, CachedProbe>()
        for (line in text.lineSequence()) {
            runCatching {
                val f = line.split("|")
                if (f.size != 4) return@runCatching
                val at = f[3].toLong()
                out[f[0]] = CachedProbe(ProbeResult(f[0], f[1] == "1", f[2].toLong()), at)
            }
        }
        return out
    }

    fun getProbeCached(context: Context): Map<String, CachedProbe> {
        return try {
            val f = probeFile(context)
            if (!f.exists()) return emptyMap()
            if (System.currentTimeMillis() - f.lastModified() > PROBE_CACHE_TTL_MS) return emptyMap()
            decodeProbe(f.readText())
        } catch (e: Exception) {
            DebugLogger.e("Tracker", "프로브 캐시 읽기 실패", e)
            emptyMap()
        }
    }

    fun getProbeCached(f: File): Map<String, CachedProbe> {
        return try {
            if (!f.exists()) return emptyMap()
            decodeProbe(f.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveProbe(context: Context, results: List<ProbeResult>) {
        try {
            probeFile(context).writeText(encodeProbe(results))
            val ok = results.count { it.reachable }
            DebugLogger.i("Tracker", "[FEATURE] 트래커 프로브 완료 도달 $ok/${results.size}개")
        } catch (e: Exception) {
            DebugLogger.e("Tracker", "프로브 캐시 저장 실패", e)
        }
    }

    fun probeAgeMs(context: Context): Long {
        val f = probeFile(context)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else -1
    }

    /** 도달 우선 정렬 — 도달(rtt 오름) → 미확인 → 불가. 순수 함수. */
    fun orderByProbe(urls: List<String>, probe: Map<String, CachedProbe>): List<String> {
        val rank = { u: String ->
            when (val c = probe[u]) {
                null -> 1
                else -> if (c.result.reachable) 0 else 2
            }
        }
        return urls.sortedWith(compareBy({ rank(it) }, { probe[it]?.result?.rttMs ?: Long.MAX_VALUE }))
    }

    /** 측정 + 캐시 저장 일괄 — 엔진 scope에서 백그라운드 호출. never throw. */
    suspend fun probeAndCache(context: Context, urls: List<String>) {
        try {
            if (urls.isEmpty()) return
            val results = probeAll(urls)
            withContext(Dispatchers.IO) { saveProbe(context, results) }
        } catch (e: Exception) {
            DebugLogger.e("Tracker", "프로브 실패 (무시)", e)
        }
    }
}
