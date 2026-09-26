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
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
internal fun Map<String, Any?>.toJson(): String = (this.toJsonElement() as JSONObject).toString()

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

/** LAN IP + 서브넷 판정 캐시 — 매 요청 NetworkInterface 열거 제거 (10s TTL) */
private object NetCache {
    class AuthPair(val expected: String, val guestExpected: String)
    @Volatile var lan: String? = null
    @Volatile var lanAt = 0L
    val subnet = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    val favicon = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    @Volatile var authKey = ""
    @Volatile var auth: AuthPair = AuthPair("", "")
    private val authLock = Any()

    fun authFor(key: String, user: String, password: String, guestPassword: String): AuthPair {
        if (key == authKey) return auth
        synchronized(authLock) {
            if (key == authKey) return auth
            val next = AuthPair(
                expected = "Basic " + Base64.getEncoder()
                    .encodeToString("$user:$password".toByteArray()),
                guestExpected = "Basic " + Base64.getEncoder()
                    .encodeToString("guest:$guestPassword".toByteArray()),
            )
            auth = next
            authKey = key
            return next
        }
    }
}

fun lanAddress(): String? {
    val now = System.currentTimeMillis()
    if (now - NetCache.lanAt < 10_000) return NetCache.lan
    val preferred = listOf("swlan0", "ap0", "wlan0")
    val candidates = mutableListOf<String>()
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (nif in ifaces.asSequence()) {
        if (!nif.isUp || nif.isLoopback) continue
        for (addr in nif.inetAddresses.asSequence()) {
            val host = addr.hostAddress ?: continue
            if (addr.isLoopbackAddress || addr.address.size != 4) continue
            if (host.startsWith("169.254")) continue
            if (nif.name in preferred) {
                NetCache.lan = host
                NetCache.lanAt = now
                return host
            }
            candidates += host
        }
    }
    return candidates.firstOrNull().also {
        NetCache.lan = it
        NetCache.lanAt = now
    }
}

/** 신규 기기 승인 게이트 (T-112) */
object DeviceGate {
    private const val TAG = "Gate"
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val deniedAt = ConcurrentHashMap<String, Long>()
    private const val DENY_TTL_MS = 10 * 60_000L

    /** 서비스가 연결 — 팝업/알림으로 사용자 결정 유도 */
    @Volatile var onRequest: ((ip: String, resolve: (Boolean) -> Unit) -> Unit)? = null

    fun awaitDecision(ip: String): Boolean {
        val f = pending.computeIfAbsent(ip) {
            DebugLogger.i(TAG, "신규 기기 접속 감지 → 승인 요청 $ip")
            onRequest?.invoke(ip) { allowed ->
                DebugLogger.i(TAG, "기기 결정 $ip allowed=$allowed")
                if (!allowed) deniedAt[ip] = System.currentTimeMillis()
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
        if (!allowed) deniedAt[ip] = System.currentTimeMillis()
        else deniedAt.remove(ip)
        pending.remove(ip)?.complete(allowed)
    }

    fun isDenied(ip: String): Boolean {
        val at = deniedAt[ip] ?: return false
        if (System.currentTimeMillis() - at > DENY_TTL_MS) {
            deniedAt.remove(ip)
            return false
        }
        return true
    }

    /** 서비스 종료 시 세션 상태 해제 — 영구 거부/미결 대기 정리 */
    fun clearSession() {
        deniedAt.clear()
        pending.values.forEach { runCatching { it.complete(false) } }
        pending.clear()
    }
}

class RelayServer(
    private val context: Context,
    val port: Int = SettingsConstraints.DEFAULT_HTTP_PORT,
    val httpsPort: Int = HTTPS_PORT,
) {
    companion object {
        /** HTTPS 기본 포트 — 다운로드 경고(안전하지 않은 다운로드) 회피용 자체 서명 TLS */
        const val HTTPS_PORT = 8443
        private const val KEY_STORE_ASSET = "certs/server.p12"
        // TLS 키스토어 비밀번호 — tls.properties → BuildConfig 주입 (소스 하드코딩 금지)
        private val KEY_STORE_PASSWORD: String get() = BuildConfig.TLS_KEYSTORE_PASSWORD
        private const val KEY_ALIAS = "relay"
    }

    @Volatile private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    /** 기동 성공을 명시적으로 기록 — watchdog 헬스체크가 루프백 HTTP 없이 판정한다 */
    @Volatile private var started = false
    @Volatile var settings: AppSettings = AppSettings()

    /** 실제 HTTPS 바인드 포트 — HTTP와 같으면 +1 회피 (v0.34, UI 충돌검사와 이중 방어) */
    val effectiveHttpsPort: Int get() = if (port == httpsPort) httpsPort + 1 else httpsPort

    fun updateSettings(s: AppSettings) {
        settings = s
        DebugLogger.d("Server", "설정 스냅샷 갱신 port=${s.port} https=${s.httpsPort} auth=${s.webAuthEnabled} limit=${s.speedLimitKbps}KB/s")
    }

    fun start(): Boolean {
        if (server != null) return true
        RelayApp.getVideo(context) // FFmpeg 스모크 + 재시작 스테일 비디오 잡 정리
        val s = runCatching { createServer() }
            .onFailure { e ->
                DebugLogger.e("Server", "서버 기동 실패 E-SRV-NET-1421 ${e.message}")
            }
            .getOrNull() ?: return false
        return runCatching {
            s.start(wait = false)
            server = s
            started = true
            val httpsPart = if (settings.httpsEnabled) " + https://0.0.0.0:$effectiveHttpsPort" else " (HTTPS 끔)"
            DebugLogger.i("Server", "[FEATURE] HTTPS 포트 기동 완료 http://0.0.0.0:$port$httpsPart (LAN=${lanAddress() ?: "?"})")
            true
        }.onFailure { e ->
            DebugLogger.e("Server", "서버 시작 실패 E-SRV-NET-1421 ${e.message}")
            server = null
            started = false
            runCatching { s.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
        }.getOrDefault(false)
    }

    fun stop() {
        RelayApp.video?.stopAll()
        runCatching { server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500) }
        server = null
        started = false
        DebugLogger.i("Server", "서버 정지")
    }

    /**
     * watchdog용 헬스체크.
     * 서버가 기동 상태로 기록돼 있고 종료되지 않았는지만 확인한다 —
     * 루프백 HTTP 요청은 Ktor 파이프라인 전체를 통과하며 /api/info 핸들러
     * (PackageManager binder + StatFs + JSON 직렬화)까지 실행하므로
     * 무활동 상태에서도 분당 1회 불필요한 왕복을 만든다.
     * 전체 파이프라인을 실제로 확인해야 하는 경우 isHealthyHttp 을 쓴다(수동 점검용).
     */
    fun isHealthy(): Boolean = started

    /** 실제 HTTP 왕복을 포함한 헬스체크 — 진단용 (watchdog 주기는 isHealthy 사용) */
    fun isHealthyHttp(timeoutMs: Int = 1500): Boolean {
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
        // stop() 은 FFmpeg 세션 cancel 등 네이티브 정리에서 throw 할 수 있다.
        // 그 예외가 위로 새면 서버가 내리고도 다시 뜨지 않는 최악 상태가 된다.
        runCatching { stop() }
            .onFailure { DebugLogger.e("Server", "watchdog 재시작: stop 실패 ${it.message}") }
        runCatching {
            server = createServer().also { it.start(wait = false) }
            started = true
            DebugLogger.i("Server", "watchdog 재시작 완료 http://0.0.0.0:$port")
        }.onFailure { e ->
            DebugLogger.e("Server", "watchdog 재시작 실패 ${e.message}")
            server = null
            started = false
        }
    }

    /** HTTP + HTTPS(TLS) 이중 커넥터 생성. 인증서는 assets/certs/server.p12 (mkcert 로컬 CA 서명).
     * v0.36: httpsEnabled=false면 HTTP 단일 커넥터만 (HTTPS 포트 미바인드). */
    private fun createServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val httpsOn = settings.httpsEnabled
        val keystore = if (httpsOn) {
            context.assets.open(KEY_STORE_ASSET).use { stream ->
                KeyStore.getInstance("PKCS12").also { it.load(stream, KEY_STORE_PASSWORD.toCharArray()) }
            }
        } else null
        val httpPort = port
        val env = applicationEnvironment { }
        return embeddedServer(
            Netty,
            env,
            configure = {
                connector {
                    this.port = httpPort
                    host = "0.0.0.0"
                }
                if (httpsOn && keystore != null) {
                    sslConnector(keystore, KEY_ALIAS, { KEY_STORE_PASSWORD.toCharArray() }, { KEY_STORE_PASSWORD.toCharArray() }) {
                        this.port = effectiveHttpsPort
                        host = "0.0.0.0"
                    }
                }
            },
            module = { relayRoutes(context, this@RelayServer) },
        )
    }
}

/** 게스트 허용 — GET 열람·다운로드만, 설정·디버그·발행·제어 차단 (T-952) */
private fun isGuestAllowed(method: String, path: String): Boolean {
    // 파비콘/북마크 아이콘은 민감 정보가 없어 게스트 포함 전원 허용 (T-1007)
    if (Favicon.isFavicon(path)) return true
    if (method != "GET" && method != "HEAD" && method != "OPTIONS") return false
    if (path == "/debug" || path.startsWith("/api/debug")) return false
    if (path.startsWith("/api/settings")) return false
    if (path.startsWith("/api/share")) return false
    if (path.startsWith("/mcp")) return false
    return true
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
    val now = System.currentTimeMillis()
    NetCache.subnet[host]?.let { (at, v) -> if (now - at < 10_000) return v }
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
            if ((self and mask) == (h and mask)) {
                NetCache.subnet[host] = now to true
                return true
            }
        }
    }
    NetCache.subnet[host] = now to false
    return false
}

private fun Application.relayRoutes(context: Context, serverRef: RelayServer) {
    // ── 전역 예외 안전망 ──
    // StatusPages 가 없으면 라우트에서 throw 한 예외가 Netty 기본 핸들러로 새어나가
    // 클라이언트는 "응답 본문 없는 연결 끊김"을 받고 서버 로그에도 원인이 남지 않는다.
    // (예: org.json.JSONException — 본문 "x" 로 POST /api/storage/mkdir 호출 시)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // 코루틴 취소는 오류가 아니다 — 그대로 재던져야 폴링/스트림 루프가 정지한다
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            DebugLogger.e("Http", "미처리 예외 ${call.request.httpMethod.value} ${runCatching { call.request.path() }.getOrDefault("?")}: ${cause.javaClass.simpleName} ${cause.message}")
            if (!call.response.isCommitted) {
                call.respondText(
                    """{"error":"server_error","detail":"${cause.javaClass.simpleName}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
    }

    // 보안 파이프라인: HTTP→HTTPS → IP 게이트 → Basic Auth
    intercept(ApplicationCallPipeline.Plugins) {
        val s = serverRef.settings

        // HTTP로 들어온 LAN 요청은 HTTPS로 이동 — 다운로드/페이지 모두 안전 채널
        // loopback(localhost/127.0.0.1/자기 IP)은 예외 — 터널(tailscaled)과 앱 자체 점검이 https 인증서를 신뢰하지 않으므로
        runCatching {
            val remoteHost = call.request.origin.remoteHost
            // forceHttpsRedirect=false(기본)면 LAN HTTP를 그대로 서빙(자체서명 인증서 미신뢰 브라우저 호환).
            // true면 HTTPS로 강제 이동. HTTPS OFF(v0.36)면 리다이렉트 무의미 → 스킵.
            if (call.request.local.scheme != "https" && !isLocalHost(remoteHost) && s.httpsEnabled && s.forceHttpsRedirect) {
                // 리다이렉트 대상은 실제 클라이언트가 접근 가능한 LAN IP(핫스팟 우선)로 고정.
                // call.request.local.localHost는 바인드 주소(0.0.0.0)나 VPN 인터페이스 IP를 줄 수 있어
                // iPad 등이 접속 불가한 IP로 유도될 수 있음 → lanAddress() 우선, 실패 시 localHost 폴백.
                val targetHost = lanAddress()?.takeIf { it.isNotEmpty() }
                    ?: call.request.local.localHost.ifEmpty { "" }
                if (targetHost.isNotEmpty()) {
                    val target = "https://$targetHost:${serverRef.effectiveHttpsPort}${call.request.local.uri}"
                    DebugLogger.d("Security", "HTTP→HTTPS 리다이렉트 $remoteHost → $target")
                    call.response.header(HttpHeaders.Location, target)
                    call.respond(HttpStatusCode.TemporaryRedirect)
                    finish()
                    return@intercept
                }
            }
        }.onFailure { DebugLogger.e("Security", "리다이렉트 판단 실패 ${it.message}") }

        val host = runCatching { call.request.origin.remoteHost }.getOrDefault("?")
        val reqPath = runCatching { call.request.path() }.getOrDefault("")
        // 파비콘/북마크 아이콘은 정적 공개 에셋 — 접속범위 승인대기·Basic Auth 모두 스킵 (T-1007)
        val isFavicon = Favicon.isFavicon(reqPath)

        // accessScope에 따른 클라이언트 접속 범위 제어
        val isTrusted = when (s.accessScope) {
            AccessScope.SUBNET_ONLY -> isLocalHost(host) || sameSubnetAsLocal(host)
            AccessScope.ANY_WITH_PASSWORD -> true
            AccessScope.APPROVED_ONLY -> isLocalHost(host) || host in s.allowedIps
        }

        if (!isTrusted && !isFavicon) {
            when {
                DeviceGate.isDenied(host) -> {
                    DebugLogger.w("Security", "차단 세션 기기 접속 거부 $host ${call.request.path()}")
                    call.respondText("거부된 기기입니다", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                    finish()
                    return@intercept
                }
                host !in s.allowedIps -> {
                    // Netty 워커 블로킹 방지 — IO 디스패처에서 승인 대기
                    val allowed = withContext(Dispatchers.IO) { DeviceGate.awaitDecision(host) }
                    if (!allowed) {
                        call.respondText("거부된 기기입니다", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                        finish()
                        return@intercept
                    }
                    DebugLogger.i("Security", "기기 허가됨 $host")
                }
            }
        }

        if (!isFavicon && s.webAuthEnabled && s.webPassword.isNotEmpty()) {
            // /s/ 공유 링크는 토큰 자체가 권한이라 인증 예외 (T-951)
            if (!reqPath.startsWith("/s/")) {
                val auth = call.request.headers[HttpHeaders.Authorization] ?: ""
                // 매 요청 Base64 2회 생성 제거 — 설정 변경 시만 재계산 (원자화)
                val key = "${s.webUser}\u0000${s.webPassword}\u0000${s.guestPassword}\u0000${s.guestEnabled}"
                val pair = NetCache.authFor(key, s.webUser, s.webPassword, s.guestPassword)
                val expected = pair.expected
                val guestExpected = pair.guestExpected
                val isGuest = s.guestEnabled && s.guestPassword.isNotEmpty() && auth == guestExpected
                if (auth != expected && !isGuest) {
                    DebugLogger.w("Security", "인증 실패 from=$host ${call.request.path()} (E-AND-DOWN-1003)")
                    call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"DroidRelay\"")
                    call.respondText("인증 필요", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                    finish()
                    return@intercept
                }
                // 게스트 읽기전용 — 열람·다운로드 GET만 (T-952)
                if (isGuest && !isGuestAllowed(call.request.httpMethod.value, reqPath)) {
                    DebugLogger.w("Security", "게스트 차단 from=$host ${call.request.httpMethod.value} ${call.request.path()}")
                    call.respondText("게스트는 읽기 전용입니다", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                    finish()
                }
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

        // ── 파비콘/북마크 아이콘 (정적 공개 에셋, 장기 캐시) ──
        Favicon.paths.forEach { favPath ->
            get(favPath) { call.serveFavicon(context, favPath) }
        }

        settingsRoutes(context, serverRef)

        storageRoutes(context, serverRef)

        davRoutes(context, serverRef)

        shareRoutes(context, serverRef)

        debugRoutes(context, serverRef)

        statsRoutes(context, serverRef)

        jobRoutes(context, serverRef)

        // ── Torrent API ──

        torrentRoutes(context, serverRef)

    }


    // ── MCP JSON-RPC 2.0 (Phase 2.1) ──
    McpServer.installRoutes(context, this, serverRef)
}

/** 파비콘/북마크 아이콘 서빙 — assets/web 고정 매핑, 장기 캐시 (T-1007) */
internal suspend fun ApplicationCall.serveFavicon(context: Context, path: String) {
    val asset = Favicon.assetFor(path)
    if (asset == null) {
        respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }
    // 매 요청 assets.open+readBytes 제거 — 메모리 캐시
    val bytes = NetCache.favicon[path]?.let { (f, b) ->
        if (f == asset.file) b else null
    } ?: run {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("web/${asset.file}").use { stream -> stream.readBytes() } }.getOrNull()
        } ?: run {
            DebugLogger.w("Web", "파비콘 에셋 없음 $path")
            respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return
        }
        NetCache.favicon[path] = asset.file to loaded
        loaded
    }
    response.header(HttpHeaders.CacheControl, "public, max-age=86400")
    respondBytes(bytes, asset.contentType)
}

/** 활성 파일 전송 추적 — watchdog이 전송 중 서버 재시작으로 연결을 끊지 않게 연기용 */
internal object TransferTracker {
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    val count: Int get() = active.get()

    suspend fun <T> track(block: suspend () -> T): T {
        active.incrementAndGet()
        return try {
            block()
        } finally {
            active.decrementAndGet()
        }
    }
}

/** Range(이어받기) 지원 파일 스트리밍 */
internal suspend fun ApplicationCall.serveFile(
    file: File,
    jobId: String,
    inline: Boolean = false,
    contentType: ContentType? = null,
) {
    val total = file.length()
    response.header(HttpHeaders.AcceptRanges, "bytes")

    val range = RangeParser.parse(request.headers[HttpHeaders.Range], total)
    if (range.invalid) {
        response.header(HttpHeaders.ContentRange, "bytes */$total")
        respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val length = range.to - range.from + 1L
    if (inline) {
        val enc = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$enc\"; filename*=UTF-8''$enc")
    } else {
        response.header(HttpHeaders.ContentDisposition, DispositionHeader.make(file.name))
    }
    if (range.partial) {
        response.status(HttpStatusCode.PartialContent)
        response.header(HttpHeaders.ContentRange, "bytes ${range.from}-${range.to}/$total")
    }

    val t0 = System.currentTimeMillis()
    TransferTracker.track {
        respondBytesWriter(contentType = contentType ?: ContentType.Application.OctetStream, contentLength = length) {
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(range.from)
                    val buf = ByteArray(SERVE_BUFFER_SIZE)
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
        }
    }
    DebugLogger.d(
        "Serve",
        "전송 종료 id=$jobId '${file.name}' ${fmt(length)} " +
            "(${if (range.partial) "206 부분" else "200 전체"}) 소요=${System.currentTimeMillis() - t0}ms",
    )
    // 트래픽 통계 (v0.37) — 썸네일(내부 UI 에셋)은 제외
    if (jobId != "thumb") TrafficLedger.addUpServe(length)
}

/** 재생용 Content-Type — 확장자 기반 (T-940) */
internal object StreamContentType {
    fun forName(name: String): ContentType {
        val ext = name.substringAfterLast('.', "").lowercase()
        val pair = when (ext) {
            "mp4", "m4v" -> "video" to "mp4"
            "webm" -> "video" to "webm"
            "mov" -> "video" to "quicktime"
            "mkv" -> "video" to "x-matroska"
            "ogv" -> "video" to "ogg"
            "mp3" -> "audio" to "mpeg"
            "m4a" -> "audio" to "mp4"
            "ogg", "oga" -> "audio" to "ogg"
            "wav" -> "audio" to "wav"
            "flac" -> "audio" to "flac"
            "jpg", "jpeg" -> "image" to "jpeg"
            "png" -> "image" to "png"
            "gif" -> "image" to "gif"
            "webp" -> "image" to "webp"
            "pdf" -> "application" to "pdf"
            else -> "application" to "octet-stream"
        }
        return ContentType(pair.first, pair.second)
    }
}

/** RFC 6266 — 비ASCII(한글/일본어/중국어) 파일명은 filename*=UTF-8''<percent-encoded>로 전달 */
internal object DispositionHeader {
    fun make(name: String): String {
        val safe = name.ifBlank { "download" }
        val enc = java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$enc\"; filename*=UTF-8''$enc"
    }

    /** 응답/쿼리 Content-Disposition에서 파일명 추출 — filename*=UTF-8'' 우선, 없으면 filename= (T-1004) */
    fun parse(header: String?): String? {
        if (header.isNullOrBlank()) return null
        // filename*= 우선 (RFC 5987: [charset'lang'value], charset 접두사 선택)
        val starRegex = Regex("""filename\*\s*=\s*(?:[^']*''){0,2}([^;\s]+)""", RegexOption.IGNORE_CASE)
        starRegex.find(header)?.let { m ->
            val decoded = runCatching {
                java.net.URLDecoder.decode(m.groupValues[1].trim().trim('"'), "UTF-8")
            }.getOrNull()?.trim()
            if (!decoded.isNullOrBlank()) return decoded
        }
        // filename= ("quoted" 또는 token)
        val regex = Regex("""filename\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE)
        val raw = regex.find(header)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim().trim('"')
        return decoded.ifBlank { null }
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
internal const val SERVE_BUFFER_SIZE = 256 * 1024

private fun fmt(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024}KB"
    n < 1_073_741_824 -> String.format("%.1fMB", n / 1_073_741_824.0)
    else -> String.format("%.2fGB", n / 1_073_741_824.0)
}

// ── 보관함 JSON 응답 헬퍼 ──

internal suspend fun ApplicationCall.respondOk() =
    respondText("""{"ok":true}""", ContentType.Application.Json)

internal suspend fun ApplicationCall.respondErr(msg: String) =
    respondText(JSONObject().put("error", msg).toString(), ContentType.Application.Json)
