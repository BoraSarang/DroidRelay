package com.borasarang.droidrelay.relay

import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.provider.Settings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.borasarang.droidrelay.BuildConfig
import com.borasarang.droidrelay.relay.TorrentRepository

@Suppress("UNCHECKED_CAST")
private fun Any?.toJsonElement(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply { (this@toJsonElement as Map<String, Any?>).forEach { (k, v) -> put(k, v.toJsonElement()) } }
    is List<*> -> JSONArray().apply { this@toJsonElement.forEach { put(it.toJsonElement()) } }
    is Boolean, is Number, is String -> this
    else -> toString()
}
internal fun Map<String, Any?>.toJson(): String = (this.toJsonElement() as JSONObject).toString(2)

object RelayApp {
    private const val TAG = "App"
    @Volatile var engine: DownloadEngine? = null
    @Volatile var torrentEngine: TorrentEngine? = null
    @Volatile var video: VideoDownloadManager? = null
    val startTime: Long = System.currentTimeMillis()

    fun get(ctx: Context): DownloadEngine =
        engine ?: synchronized(this) {
            DebugLogger.i(TAG, "DownloadEngine 최초 생성 (설정·영구저장 연동)")
            val appCtx = ctx.applicationContext
            engine ?: DownloadEngine(
                appCtx,
                SettingsRepository.get(appCtx),
                JobsPersistence(appCtx),
            ).also { engine = it }
        }

    fun getTorrent(ctx: Context): TorrentEngine =
        torrentEngine ?: synchronized(this) {
            DebugLogger.i(TAG, "TorrentEngine 최초 생성")
            val appCtx = ctx.applicationContext
            torrentEngine ?: TorrentEngine(
                appCtx,
                SettingsRepository.get(appCtx),
                TorrentPersistence(appCtx),
            ).also { torrentEngine = it }
        }

    /** 비디오(범용 스트림) 다운로드 매니저 — 최초 생성 시 FFmpeg 스모크 + 스테일 잡 정리 */
    fun getVideo(ctx: Context): VideoDownloadManager =
        video ?: synchronized(this) {
            DebugLogger.i(TAG, "VideoDownloadManager 최초 생성")
            val appCtx = ctx.applicationContext
            video ?: VideoDownloadManager(appCtx).also { it.init(); video = it }
        }

    /** 전역 속도 제한 즉시 적용 (BPS 단위) */
    fun applySpeedLimit(downloadBps: Long, uploadBps: Long) {
        engine?.applySpeedLimit(downloadBps, uploadBps)
        torrentEngine?.applySpeedLimit(downloadBps, uploadBps)
    }

    /** 전체 설정 동적 적용 (재시작 불필요) */
    fun applySettings(s: AppSettings) {
        engine?.applySettings(s)
        torrentEngine?.applySettings(s)
    }
}

fun lanAddress(): String? {
    val preferred = listOf("swlan0", "ap0", "wlan0")
    val candidates = mutableListOf<String>()
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (nif in ifaces.asSequence()) {
        if (!nif.isUp || nif.isLoopback) continue
        for (addr in nif.inetAddresses.asSequence()) {
            val host = addr.hostAddress ?: continue
            if (addr.isLoopbackAddress || addr.address.size != 4) continue
            if (host.startsWith("169.254")) continue
            if (nif.name in preferred) return host
            candidates += host
        }
    }
    return candidates.firstOrNull()
}

/** 신규 기기 승인 게이트 (T-112) */
object DeviceGate {
    private const val TAG = "Gate"
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val deniedSession = ConcurrentHashMap.newKeySet<String>()

    /** 서비스가 연결 — 팝업/알림으로 사용자 결정 유도 */
    @Volatile var onRequest: ((ip: String, resolve: (Boolean) -> Unit) -> Unit)? = null

    fun awaitDecision(ip: String): Boolean {
        val f = pending.computeIfAbsent(ip) {
            DebugLogger.i(TAG, "신규 기기 접속 감지 → 승인 요청 $ip")
            onRequest?.invoke(ip) { allowed ->
                DebugLogger.i(TAG, "기기 결정 $ip allowed=$allowed")
                if (!allowed) deniedSession.add(ip)
                pending.remove(ip)?.complete(allowed)
            }
            CompletableFuture<Boolean>()
        }
        // 무응답 시 Netty 워커 고갈 방지 — 60초 타임아웃 후 거부 (F5)
        return try {
            f.get(60, TimeUnit.SECONDS)
        } catch (_: Exception) {
            pending.remove(ip)
            DebugLogger.w(TAG, "기기 승인 타임아웃 → 거부 $ip")
            false
        }
    }

    /** 서비스 알림 액션 등 외부에서 결정 주입 */
    fun resolve(ip: String, allowed: Boolean) {
        DebugLogger.i(TAG, "기기 결정 $ip allowed=$allowed")
        if (!allowed) deniedSession.add(ip)
        pending.remove(ip)?.complete(allowed)
    }

    fun isDenied(ip: String) = ip in deniedSession
}

class RelayServer(
    private val context: Context,
    val port: Int = 8080,
) {
    companion object {
        /** HTTPS 포트 — 다운로드 경고(안전하지 않은 다운로드) 회피용 자체 서명 TLS */
        const val HTTPS_PORT = 8443
        private const val KEY_STORE_ASSET = "certs/server.p12"
        // TLS 키스토어 비밀번호 — tls.properties → BuildConfig 주입 (소스 하드코딩 금지)
        private val KEY_STORE_PASSWORD: String get() = BuildConfig.TLS_KEYSTORE_PASSWORD
        private const val KEY_ALIAS = "relay"
    }

    @Volatile private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    @Volatile var settings: AppSettings = AppSettings()

    fun updateSettings(s: AppSettings) {
        settings = s
        DebugLogger.d("Server", "설정 스냅샷 갱신 port=${s.port} auth=${s.webAuthEnabled} limit=${s.speedLimitKbps}KB/s")
    }

    fun start() {
        if (server != null) return
        RelayApp.getVideo(context) // FFmpeg 스모크 + 재시작 스테일 비디오 잡 정리
        server = runCatching { createServer() }
            .onSuccess { s ->
                s.start(wait = false)
                DebugLogger.i("Server", "기동 완료 http://0.0.0.0:$port + https://0.0.0.0:$HTTPS_PORT (LAN=${lanAddress() ?: "?"})")
            }
            .onFailure { e ->
                DebugLogger.e("Server", "서버 기동 실패 E-SRV-NET-1421 ${e.message}")
                server = null
            }
            .getOrNull()
    }

    fun stop() {
        RelayApp.video?.stopAll()
        runCatching { server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500) }
        server = null
        DebugLogger.i("Server", "서버 정지")
    }

    /** 서버가 실제로 요청을 응답하는지 루프백 헬스체크 (watchdog용) */
    fun isHealthy(timeoutMs: Int = 1500): Boolean {
        if (server == null) return false
        return try {
            val conn = java.net.URL("http://127.0.0.1:$port/api/info").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            DebugLogger.d("Server", "헬스체크 실패: ${e.message}")
            false
        }
    }

    /** watchdog용 재시작 — 현재 서버를 내리고 새로 띄운다. */
    fun restart() {
        DebugLogger.i("Server", "watchdog 재시작 시작")
        stop()
        runCatching {
            server = createServer().also { it.start(wait = false) }
            DebugLogger.i("Server", "watchdog 재시작 완료 http://0.0.0.0:$port")
        }.onFailure { e ->
            DebugLogger.e("Server", "watchdog 재시작 실패 ${e.message}")
            server = null
        }
    }

    /** HTTP + HTTPS(TLS) 이중 커넥터 생성. 인증서는 assets/certs/server.p12 (mkcert 로컬 CA 서명) */
    private fun createServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val keystore = context.assets.open(KEY_STORE_ASSET).use { stream ->
            KeyStore.getInstance("PKCS12").also { it.load(stream, KEY_STORE_PASSWORD.toCharArray()) }
        }
        val httpPort = port
        val httpsPort = if (port == HTTPS_PORT) HTTPS_PORT + 1 else HTTPS_PORT
        val env = applicationEnvironment { }
        return embeddedServer(
            Netty,
            env,
            configure = {
                connector {
                    this.port = httpPort
                    host = "0.0.0.0"
                }
                sslConnector(keystore, KEY_ALIAS, { KEY_STORE_PASSWORD.toCharArray() }, { KEY_STORE_PASSWORD.toCharArray() }) {
                    this.port = httpsPort
                    host = "0.0.0.0"
                }
            },
            module = { relayRoutes(context, this@RelayServer) },
        )
    }
}

private fun isLocalHost(host: String): Boolean {
    if (host in listOf("127.0.0.1", "::1", "localhost")) return true
    return host == lanAddress()
}

/** IPv4 문자열 → 부호없는 정수 */
private fun ipToLong(ip: String): Long? = runCatching {
    val p = ip.split('.').map { it.toLong() }
    if (p.size != 4 || p.any { it < 0 || it > 255 }) return null
    (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
}.getOrNull()

/**
 * 자기 핫스팟/로컬 인터페이스와 같은 서브넷인지 검사.
 * 개인 테더링 AP에 붙은 기기는 사용자가 비밀번호를 공유한 대상이므로 신뢰한다. (Transfer식 승인 팝업은 타 서브넷만)
 */
private fun sameSubnetAsLocal(host: String): Boolean {
    val h = ipToLong(host) ?: return false
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return false
    for (nif in ifaces.asSequence()) {
        if (!nif.isUp || nif.isLoopback) continue
        for (addr in nif.interfaceAddresses) {
            val a = addr.address?.hostAddress ?: continue
            val self = ipToLong(a) ?: continue
            val prefix = addr.networkPrefixLength
            if (prefix !in 1..32) continue
            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            if ((self and mask) == (h and mask)) return true
        }
    }
    return false
}

private fun Application.relayRoutes(context: Context, serverRef: RelayServer) {
    // 보안 파이프라인: HTTP→HTTPS → IP 게이트 → Basic Auth
    intercept(ApplicationCallPipeline.Plugins) {
        val s = serverRef.settings

        // HTTP(8080)로 들어온 LAN 요청은 HTTPS(8443)로 이동 — 다운로드/페이지 모두 안전 채널
        // loopback(localhost/127.0.0.1/자기 IP)은 예외 — 터널(tailscaled)과 앱 자체 점검이 https 인증서를 신뢰하지 않으므로
        runCatching {
            val remoteHost = call.request.origin.remoteHost
            // forceHttpsRedirect=false(기본)면 LAN HTTP를 그대로 서빙(자체서명 인증서 미신뢰 브라우저 호환).
            // true면 HTTPS(8443)로 강제 이동.
            if (call.request.local.scheme != "https" && !isLocalHost(remoteHost) && s.forceHttpsRedirect) {
                // 리다이렉트 대상은 실제 클라이언트가 접근 가능한 LAN IP(핫스팟 우선)로 고정.
                // call.request.local.localHost는 바인드 주소(0.0.0.0)나 VPN 인터페이스 IP를 줄 수 있어
                // iPad 등이 접속 불가한 IP로 유도될 수 있음 → lanAddress() 우선, 실패 시 localHost 폴백.
                val targetHost = lanAddress()?.takeIf { it.isNotEmpty() }
                    ?: call.request.local.localHost.ifEmpty { "" }
                if (targetHost.isNotEmpty()) {
                    val target = "https://$targetHost:${RelayServer.HTTPS_PORT}${call.request.local.uri}"
                    DebugLogger.d("Security", "HTTP→HTTPS 리다이렉트 $remoteHost → $target")
                    call.response.header(HttpHeaders.Location, target)
                    call.respond(HttpStatusCode.TemporaryRedirect)
                    finish()
                    return@intercept
                }
            }
        }.onFailure { DebugLogger.e("Security", "리다이렉트 판단 실패 ${it.message}") }

        val host = runCatching { call.request.origin.remoteHost }.getOrDefault("?")

        // accessScope에 따른 클라이언트 접속 범위 제어
        val isTrusted = when (s.accessScope) {
            AccessScope.SUBNET_ONLY -> isLocalHost(host) || sameSubnetAsLocal(host)
            AccessScope.ANY_WITH_PASSWORD -> true
            AccessScope.APPROVED_ONLY -> isLocalHost(host) || host in s.allowedIps
        }

        if (!isTrusted) {
            when {
                DeviceGate.isDenied(host) -> {
                    DebugLogger.w("Security", "차단 세션 기기 접속 거부 $host ${call.request.path()}")
                    call.respondText("거부된 기기입니다", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                    finish()
                    return@intercept
                }
                host !in s.allowedIps -> {
                    val allowed = DeviceGate.awaitDecision(host)
                    if (!allowed) {
                        call.respondText("거부된 기기입니다", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        finish()
                        return@intercept
                    }
                    DebugLogger.i("Security", "기기 허가됨 $host")
                }
            }
        }

        if (s.webAuthEnabled && s.webPassword.isNotEmpty()) {
            val expected = "Basic " + Base64.getEncoder()
                .encodeToString("${s.webUser}:${s.webPassword}".toByteArray())
            if (call.request.headers[HttpHeaders.Authorization] != expected) {
                DebugLogger.w("Security", "인증 실패 from=$host ${call.request.path()} (E-AND-DOWN-1003)")
                call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"DroidRelay\"")
                call.respondText("인증 필요", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }

    // API 호출 자동 기록 — Call 단계 (응답 후 status 캡처)
    // 폴링(정보 획득용 GET) 엔드포인트는 웹의 refresh()로 빈번히 호출되어 버퍼/로그를 채우므로 제외
    val pollExempt = setOf(
        "/api/info", "/api/jobs", "/api/torrents", "/api/guard/status"
    )
    intercept(ApplicationCallPipeline.Call) {
        val pathRaw = call.request.path()
        try {
            proceed()
        } finally {
            if (pathRaw.startsWith("/api/") &&
                !pathRaw.startsWith("/api/events") &&
                pathRaw !in pollExempt
            ) {
                val status = call.response.status()?.value ?: 0
                DebugLogger.api("?", pathRaw, status)
            }
        }
    }

    routing {
        get("/") {
            call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
            call.respondText(WebAssets.dashboardHtml, ContentType.Text.Html)
        }

        get("/debug") {
            call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
            call.respondText(WebAssets.debugHtml, ContentType.Text.Html)
        }

        settingsRoutes(context, serverRef)

        storageRoutes(context, serverRef)

        debugRoutes(context, serverRef)

        jobRoutes(context, serverRef)

        // ── Torrent API ──

        torrentRoutes(context, serverRef)

    }


    // ── MCP JSON-RPC 2.0 (Phase 2.1) ──
    McpServer.installRoutes(context, this, serverRef)
}

/** Range(이어받기) 지원 파일 스트리밍 */
internal suspend fun ApplicationCall.serveFile(file: File, jobId: String) {
    val total = file.length()
    response.header(HttpHeaders.AcceptRanges, "bytes")

    val range = RangeParser.parse(request.headers[HttpHeaders.Range], total)
    if (range.invalid) {
        response.header(HttpHeaders.ContentRange, "bytes */$total")
        respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val length = range.to - range.from + 1L
    response.header(HttpHeaders.ContentDisposition, DispositionHeader.make(file.name))
    if (range.partial) {
        response.status(HttpStatusCode.PartialContent)
        response.header(HttpHeaders.ContentRange, "bytes ${range.from}-${range.to}/$total")
    }

    val t0 = System.currentTimeMillis()
    respondBytesWriter(contentType = ContentType.Application.OctetStream, contentLength = length) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(range.from)
            val buf = ByteArray(BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                writeFully(buf, 0, n)
                remaining -= n
            }
        }
    }
    DebugLogger.i(
        "Serve",
        "전송 종료 id=$jobId '${file.name}' ${fmt(length)} " +
            "(${if (range.partial) "206 부분" else "200 전체"}) 소요=${System.currentTimeMillis() - t0}ms",
    )
}

/** RFC 6266 — 비ASCII(한글/일본어/중국어) 파일명은 filename*=UTF-8''<percent-encoded>로 전달 */
internal object DispositionHeader {
    fun make(name: String): String {
        val enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$enc\"; filename*=UTF-8''$enc"
    }
}

internal object RangeParser {
    data class Result(val from: Long, val to: Long, val partial: Boolean, val invalid: Boolean)

    fun parse(header: String?, total: Long): Result {
        val full = Result(0, total - 1, partial = false, invalid = false)
        if (header == null || !header.startsWith("bytes=")) return full

        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        var from = 0L
        var to = total - 1L
        val parsedOk = runCatching {
            when {
                spec.startsWith("-") -> {
                    from = (total - spec.substring(1).toLong()).coerceAtLeast(0)
                    true
                }
                dash > 0 -> {
                    from = spec.substring(0, dash).toLong()
                    to = spec.substring(dash + 1).toLongOrNull()?.coerceAtMost(total - 1L) ?: (total - 1L)
                    true
                }
                else -> false
            }
        }.getOrDefault(false)

        if (!parsedOk) return full
        val satisfiable = from <= to && from < total
        if (!satisfiable) return Result(from, to, partial = false, invalid = true)
        return Result(from, to, partial = true, invalid = false)
    }
}

internal const val BUFFER_SIZE = 64 * 1024

private fun fmt(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024}KB"
    n < 1_073_741_824 -> String.format("%.1fMB", n / 1_073_741_824.0)
    else -> String.format("%.2fGB", n / 1_073_741_824.0)
}

// ── 보관함 JSON 응답 헬퍼 ──

internal suspend fun ApplicationCall.respondOk() =
    respondText("""{"ok":true}""", ContentType.Application.Json)

internal suspend fun ApplicationCall.respondErr(msg: String) =
    respondText("""{"error":"$msg"}""", ContentType.Application.Json)
