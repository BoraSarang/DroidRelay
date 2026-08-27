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
import org.json.JSONArray
import org.json.JSONObject
import com.borasarang.droidrelay.relay.TorrentRepository

@Suppress("UNCHECKED_CAST")
private fun Any?.toJsonElement(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply { (this@toJsonElement as Map<String, Any?>).forEach { (k, v) -> put(k, v.toJsonElement()) } }
    is List<*> -> JSONArray().apply { this@toJsonElement.forEach { put(it.toJsonElement()) } }
    is Boolean, is Number, is String -> this
    else -> toString()
}
private fun Map<String, Any?>.toJson(): String = (this.toJsonElement() as JSONObject).toString(2)

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
        return runCatching { f.get() }.getOrDefault(false)
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
        private const val KEY_STORE_PASSWORD = "droidrelay01"
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
            if (call.request.local.scheme != "https" && !isLocalHost(remoteHost)) {
                val targetHost = call.request.local.localHost.ifEmpty { lanAddress() ?: "" }
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
    intercept(ApplicationCallPipeline.Call) {
        val pathRaw = call.request.path()
        try {
            proceed()
        } finally {
            if (pathRaw.startsWith("/api/") && !pathRaw.startsWith("/api/events")) {
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

        get("/api/info") {
            val dir = RelayApp.get(context).workDir
            val stat = runCatching { StatFs(dir.path) }.getOrNull()
            val jobs = JobsRepository.all()
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.5.0"
            val info = JSONObject().apply {
                put("ip", lanAddress() ?: JSONObject.NULL)
                put("port", serverRef.port)
                put("version", appVersion)
                put("storageFree", stat?.availableBytes ?: JSONObject.NULL)
                put("storageTotal", stat?.totalBytes ?: JSONObject.NULL)
                put("running", jobs.count { it.state == JobState.RUNNING })
                put("speedTotalBps", jobs.filter { it.state == JobState.RUNNING }.sumOf { it.speedBps })
            }
            call.respondText(info.toString(), ContentType.Application.Json)
        }

        // ── 전역 속도 제한 설정 ──
        get("/api/settings/speed-limit") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("maxDownloadBps", s.maxDownloadBps)
                    put("maxUploadBps", s.maxUploadBps)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/speed-limit") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("maxDownloadBps") == true) {
                val dl = json?.optLong("maxDownloadBps", 0) ?: 0L
                repo.setMaxDownloadBps(dl)
            }
            if (json?.has("maxUploadBps") == true) {
                val ul = json?.optLong("maxUploadBps", 0) ?: 0L
                repo.setMaxUploadBps(ul)
            }
            // 엔진에 즉시 반영
            val s = repo.firstBlocking()
            RelayApp.get(context).applySpeedLimit(s.maxDownloadBps, s.maxUploadBps)
            RelayApp.getTorrent(context).applySpeedLimit(s.maxDownloadBps, s.maxUploadBps)
            serverRef.settings = s
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 다운로드 설정 ──
        get("/api/settings/download") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("concurrency", s.concurrency)
                    put("speedLimitKbps", s.speedLimitKbps)
                    put("notifications", s.notifications)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/download") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            json?.optInt("concurrency", -1)?.let { if (it >= 1) repo.setConcurrency(it) }
            json?.optInt("speedLimitKbps", -1)?.let { if (it >= 0) repo.setSpeedLimit(it) }
            if (json?.has("notifications") == true) json?.optBoolean("notifications")?.let { repo.setNotifications(it) }
            // 엔진에 즉시 반영
            RelayApp.get(context).applySettings(repo.firstBlocking())
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 토렌트 설정 ──
        get("/api/settings/torrent") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("torrentUploadLimit", s.torrentUploadLimit)
                    put("torrentDownloadLimit", s.torrentDownloadLimit)
                    put("torrentMaxActive", s.torrentMaxActive)
                    put("torrentSeedRatio", s.torrentSeedRatio)
                    put("torrentDhtEnabled", s.torrentDhtEnabled)
                    put("torrentPexEnabled", s.torrentPexEnabled)
                    put("torrentListenPort", s.torrentListenPort)
                    put("torrentSavePath", s.torrentSavePath)
                    put("torrentSequentialDownload", s.torrentSequentialDownload)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/torrent") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            json?.optLong("torrentUploadLimit", -1)?.let { if (it >= 0) repo.setTorrentUploadLimit(it.toInt()) }
            json?.optLong("torrentDownloadLimit", -1)?.let { if (it >= 0) repo.setTorrentDownloadLimit(it.toInt()) }
            json?.optInt("torrentMaxActive", -1)?.let { if (it >= 1) repo.setTorrentMaxActive(it) }
            json?.optDouble("torrentSeedRatio", -1.0)?.let { if (it >= 0) repo.setTorrentSeedRatio(it.toFloat()) }
            if (json?.has("torrentDhtEnabled") == true) json?.optBoolean("torrentDhtEnabled")?.let { repo.setTorrentDhtEnabled(it) }
            if (json?.has("torrentPexEnabled") == true) json?.optBoolean("torrentPexEnabled")?.let { repo.setTorrentPexEnabled(it) }
            json?.optInt("torrentListenPort", -1)?.let { if (it >= 1024) repo.setTorrentListenPort(it) }
            json?.optString("torrentSavePath", "")?.let { if (it.isNotBlank()) repo.setTorrentSavePath(it) }
            if (json?.has("torrentSequentialDownload") == true) json?.optBoolean("torrentSequentialDownload")?.let { repo.setTorrentSequentialDownload(it) }
            // 엔진에 즉시 반영
            RelayApp.getTorrent(context).applySettings(repo.firstBlocking())
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 설정 리셋 (기본값 복원) ──
        post("/api/settings/reset") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val category = json.optString("category", "all")
            val repo = SettingsRepository.get(context)
            val ctx = context
            when (category) {
                "download" -> {
                    repo.setConcurrency(2)
                    repo.setSpeedLimit(0)
                    repo.setNotifications(true)
                    RelayApp.get(ctx).applySettings(repo.firstBlocking())
                }
                "torrent" -> {
                    repo.setTorrentUploadLimit(512)
                    repo.setTorrentDownloadLimit(0)
                    repo.setTorrentMaxActive(3)
                    repo.setTorrentSeedRatio(2.0f)
                    repo.setTorrentDhtEnabled(true)
                    repo.setTorrentPexEnabled(true)
                    val randomPort = (49152 + (Math.random() * 16384).toInt()).coerceIn(49152, 65535)
                    repo.setTorrentListenPort(randomPort)
                    repo.setTorrentSavePath("/sdcard/Download/DroidRelay")
                    RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
                }
                else -> {
                    repo.setConcurrency(2)
                    repo.setSpeedLimit(0)
                    repo.setNotifications(true)
                    repo.setTorrentUploadLimit(512)
                    repo.setTorrentDownloadLimit(0)
                    repo.setTorrentMaxActive(3)
                    repo.setTorrentSeedRatio(2.0f)
                    repo.setTorrentDhtEnabled(true)
                    repo.setTorrentPexEnabled(true)
                    val randomPort = (49152 + (Math.random() * 16384).toInt()).coerceIn(49152, 65535)
                    repo.setTorrentListenPort(randomPort)
                    repo.setTorrentSavePath("/sdcard/Download/DroidRelay")
                    RelayApp.get(ctx).applySettings(repo.firstBlocking())
                    RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
                }
            }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 경로 테스트 (쓰기 권한 확인) ──
        post("/api/storage/test-path") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val path = json?.optString("path", "") ?: ""
            if (path.isBlank()) {
                call.respondText("""{"ok":false,"error":"경로 없음"}""", ContentType.Application.Json)
                return@post
            }
            val testDir = java.io.File(path)
            try {
                testDir.mkdirs()
                val testFile = java.io.File(testDir, ".droidrelay_test_${System.currentTimeMillis()}")
                testFile.writeText("test")
                val ok = testFile.exists() && testFile.delete()
                call.respondText(
                    JSONObject().apply { put("ok", ok) }.toString(),
                    ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondText(
                    JSONObject().apply { put("ok", false); put("error", e.message ?: "알 수 없는 오류") }.toString(),
                    ContentType.Application.Json
                )
            }
        }

        // ── RSS 피드 관리 ──
        get("/api/rss") {
            val arr = JSONArray()
            RssFeedRepository.all().forEach { f ->
                arr.put(JSONObject().apply {
                    put("id", f.id)
                    put("url", f.url)
                    put("name", f.name)
                    put("filterKeyword", f.filterKeyword)
                    put("filterRegex", f.filterRegex)
                    put("autoDownload", f.autoDownload)
                    put("enabled", f.enabled)
                    put("lastCheckedAt", f.lastCheckedAt)
                    put("lastItemTitle", f.lastItemTitle)
                    put("lastItemCount", f.lastItemCount)
                    put("error", f.error ?: JSONObject.NULL)
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/rss") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val url = json?.optString("url", "") ?: ""
            if (url.isBlank()) {
                call.respondText("""{"ok":false,"error":"URL 필요"}""", ContentType.Application.Json)
                return@post
            }
            val name = json?.optString("name", "") ?: ""
            val filterKeyword = json?.optString("filterKeyword", "") ?: ""
            val filterRegex = json?.optString("filterRegex", "") ?: ""
            val autoDownload = json?.optBoolean("autoDownload", true) ?: true
            val feed = RssFeedRepository.add(url, name, filterKeyword, filterRegex, autoDownload)
            call.respondText(
                JSONObject().apply { put("ok", true); put("id", feed.id) }.toString(),
                ContentType.Application.Json
            )
        }

        delete("/api/rss/{id}") {
            val id = call.parameters["id"] ?: ""
            val ok = RssFeedRepository.remove(id)
            call.respondText(
                JSONObject().apply { put("ok", ok) }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/rss/{id}/check") {
            val id = call.parameters["id"] ?: ""
            // id "0" = 전체 피드 확인 (웹 UI "지금 확인" 버튼)
            if (id != "0") {
                val feed = RssFeedRepository.get(id)
                if (feed == null) {
                    call.respondText("""{"ok":false,"error":"피드 없음"}""", ContentType.Application.Json)
                    return@post
                }
            }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    RssFeedManager(context).checkAllFeeds()
                } catch (e: Exception) {
                    DebugLogger.e("RelayServer", "RSS 수동 확인 실패", e)
                }
            }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── Debrid 설정 ──
        get("/api/settings/debrid") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("debridEnabled", s.debridEnabled)
                    put("debridProvider", s.debridProvider)
                    put("debridApiKey", s.debridApiKey)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/debrid") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("debridEnabled") == true) json?.optBoolean("debridEnabled")?.let { repo.setDebridEnabled(it) }
            if (json?.has("debridProvider") == true) json?.optString("debridProvider")?.let { repo.setDebridProvider(it) }
            if (json?.has("debridApiKey") == true) json?.optString("debridApiKey")?.let { repo.setDebridApiKey(it) }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── Debrid 언리스트링크 ──
        post("/api/debrid/unrestrict") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val url = json?.optString("url", "") ?: ""
            if (url.isBlank()) {
                call.respondText("""{"ok":false,"error":"URL 필요"}""", ContentType.Application.Json)
                return@post
            }
            val s = serverRef.settings
            if (!s.debridEnabled || s.debridApiKey.isBlank()) {
                call.respondText("""{"ok":false,"error":"Debrid 미설정 또는 비활성화"}""", ContentType.Application.Json)
                return@post
            }
            val provider = runCatching { DebridProvider.valueOf(s.debridProvider) }.getOrNull()
            if (provider == null) {
                call.respondText("""{"ok":false,"error":"지원하지 않는 제공자: ${s.debridProvider}"}""", ContentType.Application.Json)
                return@post
            }
            try {
                val client = DebridClient(context)
                val link = client.unrestrict(url, provider, s.debridApiKey)
                call.respondText(
                    JSONObject().apply {
                        put("ok", true)
                        put("id", link.id)
                        put("filename", link.filename)
                        put("filesize", link.filesize)
                        put("directUrl", link.directUrl)
                        put("chunks", link.chunks)
                        put("streamable", link.streamable)
                    }.toString(),
                    ContentType.Application.Json
                )
            } catch (e: DebridException) {
                DebugLogger.e("Debrid", "언리스트링크 실패 url=${url.take(80)}", e)
                call.respondText(
                    JSONObject().apply { put("ok", false); put("error", e.message ?: "Debrid API 오류") }.toString(),
                    ContentType.Application.Json
                )
            } catch (e: Exception) {
                DebugLogger.e("Debrid", "언리스트링크 실패", e)
                call.respondText(
                    JSONObject().apply { put("ok", false); put("error", "네트워크 오류: ${e.message}") }.toString(),
                    ContentType.Application.Json
                )
            }
        }

        // ── Debrid 계정 확인 ──
        post("/api/debrid/check") {
            val s = serverRef.settings
            if (s.debridApiKey.isBlank()) {
                call.respondText("""{"ok":false,"error":"API 키 미설정"}""", ContentType.Application.Json)
                return@post
            }
            val provider = runCatching { DebridProvider.valueOf(s.debridProvider) }.getOrNull()
            if (provider == null) {
                call.respondText("""{"ok":false,"error":"제공자 미설정"}""", ContentType.Application.Json)
                return@post
            }
            try {
                val client = DebridClient(context)
                val info = client.checkAccount(provider, s.debridApiKey)
                call.respondText(
                    JSONObject().apply {
                        put("ok", true)
                        put("premium", info.optBoolean("premium", false))
                        put("username", info.optString("username", ""))
                        put("email", info.optString("email", ""))
                    }.toString(),
                    ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondText(
                    JSONObject().apply { put("ok", false); put("error", e.message ?: "확인 실패") }.toString(),
                    ContentType.Application.Json
                )
            }
        }

        // ── 가드 데몬 설정 (Phase 2.4) ──
        get("/api/settings/guard") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("guardEnabled", s.guardEnabled)
                    put("guardThermalLimit", s.guardThermalLimit)
                    put("guardBatteryLimit", s.guardBatteryLimit)
                    put("guardStorageLimit", s.guardStorageLimit)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/guard") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("guardEnabled") == true) json?.optBoolean("guardEnabled")?.let { repo.setGuardEnabled(it) }
            json?.optInt("guardThermalLimit", -1)?.let { if (it in 50..70) repo.setGuardThermalLimit(it) }
            json?.optInt("guardBatteryLimit", -1)?.let { if (it in 5..50) repo.setGuardBatteryLimit(it) }
            json?.optInt("guardStorageLimit", -1)?.let { if (it in 50..99) repo.setGuardStorageLimit(it) }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 가드 데몬 상태 ──
        get("/api/guard/status") {
            val ctx = context
            val thermal = try {
                val file = java.io.File("/sys/class/thermal/thermal_zone0/temp")
                if (file.exists()) (file.readText().trim().toIntOrNull() ?: 0) / 1000 else 0
            } catch (_: Exception) { 0 }

            val batteryManager = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val storageDir = java.io.File("/sdcard/Download/DroidRelay")
            val storageUsed = if (storageDir.exists()) {
                val total = storageDir.totalSpace
                val free = storageDir.freeSpace
                if (total > 0) ((total - free) * 100 / total).toInt() else 0
            } else 0

            val s = serverRef.settings
            val throttled = s.guardEnabled && (
                (thermal > s.guardThermalLimit) ||
                (batteryLevel in 0..s.guardBatteryLimit) ||
                (storageUsed > s.guardStorageLimit)
            )

            call.respondText(
                JSONObject().apply {
                    put("thermal", thermal)
                    put("thermalLimit", s.guardThermalLimit)
                    put("batteryLevel", batteryLevel)
                    put("batteryLimit", s.guardBatteryLimit)
                    put("storageUsed", storageUsed)
                    put("storageLimit", s.guardStorageLimit)
                    put("throttled", throttled)
                    put("guardEnabled", s.guardEnabled)
                }.toString(),
                ContentType.Application.Json
            )
        }

        // ── 웹훅 설정 (Phase 2.2) ──
        get("/api/settings/webhook") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("webhookEnabled", s.webhookEnabled)
                    put("webhookUrl", s.webhookUrl)
                    put("webhookSecret", if (s.webhookSecret.isNotEmpty()) "••••••••" else "")
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/webhook") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("webhookEnabled") == true) json?.optBoolean("webhookEnabled")?.let { repo.setWebhookEnabled(it) }
            if (json?.has("webhookUrl") == true) json?.optString("webhookUrl")?.let { repo.setWebhookUrl(it) }
            if (json?.has("webhookSecret") == true) json?.optString("webhookSecret")?.let { repo.setWebhookSecret(it) }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 터널 설정 (Phase 2.3) ──
        get("/api/settings/tunnel") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("tunnelEnabled", s.tunnelEnabled)
                    put("tunnelProvider", s.tunnelProvider)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/tunnel") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("tunnelEnabled") == true) json?.optBoolean("tunnelEnabled")?.let { repo.setTunnelEnabled(it) }
            if (json?.has("tunnelProvider") == true) json?.optString("tunnelProvider")?.let { repo.setTunnelProvider(it) }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 터널 상태 (Phase 2.3 확장) ──
        get("/api/tunnel/status") {
            val s = serverRef.settings
            val provider = runCatching { TunnelProvider.fromString(s.tunnelProvider) }.getOrNull()
            val statusText = if (provider != null && s.tunnelEnabled) {
                when (provider) {
                    TunnelProvider.TAILSCALE -> {
                        val ip = try {
                            NetworkInterface.getNetworkInterfaces()?.asSequence()?.flatMap { it.inetAddresses?.asSequence() ?: emptySequence() }
                                ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.hostAddress?.startsWith("100.") == true }?.hostAddress
                        } catch (_: Exception) { null }
                        if (ip != null) JSONObject().apply { put("connected", true); put("ip", ip); put("url", "http://$ip:8080") }.toString()
                        else JSONObject().apply { put("connected", false); put("reason", "Tailscale 미연결 또는 미설치") }.toString()
                    }
                    TunnelProvider.CLOUDFLARE -> JSONObject().apply { put("connected", false); put("reason", "cloudflared 바이너리 필요") }.toString()
                }
            } else JSONObject().apply { put("connected", false); put("reason", "터널 비활성화") }.toString()
            call.respondText(statusText, ContentType.Application.Json)
        }

        // ── MCP 권한 설정 (Phase 2.1 확장) ──
        get("/api/settings/mcp") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("mcpPrivacyMode", s.mcpPrivacyMode)
                    put("mcpToolsDisabled", org.json.JSONArray(s.mcpToolsDisabled.toList()))
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/mcp") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("mcpPrivacyMode") == true) json?.optBoolean("mcpPrivacyMode")?.let { repo.setMcpPrivacyMode(it) }
            if (json?.has("mcpToolsDisabled") == true) {
                val arr = json.optJSONArray("mcpToolsDisabled")
                if (arr != null) {
                    val disabled = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    // 기존 설정에서 업데이트
                    val current = repo.firstBlocking().mcpToolsDisabled
                    val toDisable = disabled - current
                    val toEnable = current - disabled
                    toDisable.forEach { repo.setMcpToolDisabled(it, true) }
                    toEnable.forEach { repo.setMcpToolDisabled(it, false) }
                }
            }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 스케줄 설정 (Phase 3 확장) ──
        get("/api/settings/schedule") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("scheduleEnabled", s.scheduleEnabled)
                    put("scheduleCron", s.scheduleCron)
                    put("scheduleWifiOnly", s.scheduleWifiOnly)
                    put("scheduleChargingOnly", s.scheduleChargingOnly)
                    put("scheduleBatteryMin", s.scheduleBatteryMin)
                    put("cronValid", CronParser.isValid(s.scheduleCron))
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/schedule") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("scheduleEnabled") == true) json?.optBoolean("scheduleEnabled")?.let { repo.setScheduleEnabled(it) }
            if (json?.has("scheduleCron") == true) json?.optString("scheduleCron")?.let { repo.setScheduleCron(it) }
            if (json?.has("scheduleWifiOnly") == true) json?.optBoolean("scheduleWifiOnly")?.let { repo.setScheduleWifiOnly(it) }
            if (json?.has("scheduleChargingOnly") == true) json?.optBoolean("scheduleChargingOnly")?.let { repo.setScheduleChargingOnly(it) }
            if (json?.has("scheduleBatteryMin") == true) json?.optInt("scheduleBatteryMin")?.let { repo.setScheduleBatteryMin(it) }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── yt-dlp 서버 설정 ──
        get("/api/settings/ytdlp") {
            val s = serverRef.settings
            call.respondText(
                JSONObject().apply {
                    put("ytdlpEnabled", s.ytdlpEnabled)
                    put("ytdlpServerUrl", s.ytdlpServerUrl)
                    put("ytdlpApiKey", s.ytdlpApiKey)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/settings/ytdlp") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val repo = SettingsRepository.get(context)
            if (json?.has("ytdlpEnabled") == true) json?.optBoolean("ytdlpEnabled")?.let { repo.setYtdlpEnabled(it) }
            if (json?.has("ytdlpServerUrl") == true) json?.optString("ytdlpServerUrl")?.let { repo.setYtdlpServerUrl(it) }
            if (json?.has("ytdlpApiKey") == true) json?.optString("ytdlpApiKey")?.let { repo.setYtdlpApiKey(it) }
            serverRef.settings = repo.firstBlocking()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // ── 외장 스토리지 감지 (Phase 3 확장) ──
        get("/api/storage/external") {
            val storages = StorageDetector.detectExternal(context)
            val arr = org.json.JSONArray()
            storages.forEach { info ->
                arr.put(JSONObject().apply {
                    put("path", info.path)
                    put("label", info.label)
                    put("totalBytes", info.totalBytes)
                    put("freeBytes", info.freeBytes)
                    put("isExternal", info.isExternal)
                })
            }
            call.respondText(
                JSONObject().apply {
                    put("storages", arr)
                    put("bestPath", StorageDetector.bestExternalPath(context) ?: "")
                }.toString(),
                ContentType.Application.Json
            )
        }

        // ── 메트릭스 (Phase 3) ──
        get("/api/metrics") {
            val jobs = JobsRepository.all()
            val torrents = TorrentRepository.all()
            val uptimeMs = System.currentTimeMillis() - RelayApp.startTime
            call.respondText(
                JSONObject().apply {
                    put("uptime_ms", uptimeMs)
                    put("downloads_total", jobs.size)
                    put("downloads_running", jobs.count { it.state == JobState.RUNNING })
                    put("downloads_queued", jobs.count { it.state == JobState.QUEUED })
                    put("downloads_done", jobs.count { it.state == JobState.DONE })
                    put("downloads_failed", jobs.count { it.state == JobState.FAILED })
                    put("torrents_total", torrents.size)
                    put("torrents_active", torrents.count { it.state == TorrentState.DOWNLOADING })
                    put("bytes_downloaded_total", jobs.sumOf { it.downloadedBytes })
                    put("bytes_uploaded_total", torrents.sumOf { it.uploadSpeed.toLong() })
                    put("speed_bps", jobs.filter { it.state == JobState.RUNNING }.sumOf { it.speedBps })
                }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/jobs") {
            val arr = JSONArray()
            JobsRepository.all().forEach { j ->
                arr.put(JSONObject().apply {
                    put("id", j.id)
                    put("url", j.url)
                    put("filename", j.filename)
                    put("state", j.state.name)
                    put("progress", j.progress.toDouble())
                    put("downloadedBytes", j.downloadedBytes)
                    put("totalBytes", j.totalBytes)
                    put("speedBps", j.speedBps)
                    put("startedAt", j.startedAt)
                    put("order", j.order)
                    put("errorMessage", j.errorMessage ?: JSONObject.NULL)
                    put("type", j.type)
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/jobs") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val url = json?.optString("url") ?: ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                DebugLogger.w("Http", "POST 거부 — 유효하지 않은 URL (E-AND-DOWN-1003) body='${body.take(80)}'")
                call.respondText(
                    "E-AND-DOWN-1003: 유효한 http(s) URL이 아닙니다",
                    ContentType.Text.Plain,
                    HttpStatusCode.UnprocessableEntity,
                )
                return@post
            }
            // Debrid 연동: 활성화된 경우 언리스트링크 시도
            val s = serverRef.settings
            val finalUrl = if (s.debridEnabled && s.debridApiKey.isNotBlank()) {
                try {
                    val provider = runCatching { DebridProvider.valueOf(s.debridProvider) }.getOrNull()
                    if (provider != null) {
                        val client = DebridClient(context)
                        val link = client.unrestrict(url.trim(), provider, s.debridApiKey)
                        DebugLogger.i("Debrid", "URL 언리스트링크 성공 id=${link.id} file=${link.filename}")
                        link.directUrl
                    } else {
                        url.trim()
                    }
                } catch (e: Exception) {
                    DebugLogger.e("Debrid", "언리스트링크 실패 — 원본 URL로 진행", e)
                    url.trim()
                }
            } else {
                url.trim()
            }

            val job = RelayApp.get(context).enqueue(finalUrl)
            DebugLogger.i("Http", "POST 수락 id=${job.id} file='${job.filename}' debrid=${s.debridEnabled}")
            call.respondText(
                JSONObject().put("id", job.id).toString(),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        post("/api/jobs/{id}/{action}") {
            val id = call.parameters["id"]!!
            val action = call.parameters["action"]
            val engine = RelayApp.get(context)
            when (action) {
                "pause" -> {
                    if (JobsRepository.get(id)?.type == "video") {
                        call.respondText("비디오 다운로드는 일시정지 미지원 (삭제로 취소)", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                    } else {
                        engine.pause(id); call.respondText("ok")
                    }
                }
                "resume" -> { engine.resume(id); call.respondText("ok") }
                else -> call.respondText("지원 없는 동작", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }
        }

        delete("/api/jobs/{id}") {
            val id = call.parameters["id"]!!
            when (JobsRepository.get(id)) {
                null -> call.respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                else -> {
                    DebugLogger.i("Http", "DELETE id=$id")
                    val type = JobsRepository.get(id)?.type
                    if (type == "video") RelayApp.getVideo(context).cancel(id) else RelayApp.get(context).cancel(id)
                    JobsRepository.remove(id)
                    call.respondText("ok")
                }
            }
        }

        // ── 비디오 API (범용 스트림) ──

        /**
         * POST /api/video/analyze — URL 분석 (유튜브: yt-dlp 서버 사용 / 스트림: StreamDetector)
         */
        post("/api/video/analyze") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val url = json?.optString("url", "")?.trim().orEmpty()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                call.respondText("E-AND-VID-0101: 유효한 http(s) URL이 아닙니다", ContentType.Text.Plain, HttpStatusCode.UnprocessableEntity)
                return@post
            }
            val settings = serverRef.settings
            try {
                val result = VideoApi.analyze(settings, url)
                call.respondText(result.toString(), ContentType.Application.Json)
            } catch (e: VideoException) {
                DebugLogger.e("VideoApi", "분석 실패 ${e.code}: ${e.message}")
                call.respondText("${e.code}: ${e.message}", ContentType.Text.Plain, HttpStatusCode.UnprocessableEntity)
            } catch (e: Exception) {
                DebugLogger.e("VideoApi", "분석 오류: ${e.message}")
                call.respondText("E-AND-VID-0100: 분석 오류: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }
        }

        /**
         * POST /api/video/create — 비디오 잡 생성 + FFmpeg 시작
         * 유튜브: {url, format, filename} — yt-dlp 서버에서 포맷 선택 후 다운로드
         * 스트림: {url, streamUrl(선택), filename(선택)} — 원본 copy
         */
        post("/api/video/create") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val url = json?.optString("url", "")?.trim().orEmpty()
            val streamUrl = json?.optString("streamUrl", "")?.trim().orEmpty()
            val formatId = json?.optString("format", "")?.trim().orEmpty()
            val wantName = json?.optString("filename", "")?.trim().orEmpty()
            val settings = serverRef.settings
            val job = try {
                VideoApi.create(
                    context, settings, url,
                    streamUrl.ifBlank { null },
                    formatId.ifBlank { null },
                    wantName.ifBlank { null },
                )
            } catch (e: VideoException) {
                DebugLogger.e("VideoApi", "생성 실패 ${e.code}: ${e.message}")
                call.respondText("${e.code}: ${e.message}", ContentType.Text.Plain, HttpStatusCode.UnprocessableEntity)
                return@post
            }
            call.respondText(
                JSONObject().put("id", job.id).toString(),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        post("/api/jobs/reorder") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val id = json?.optString("id") ?: ""
            val newOrder = json?.optInt("newOrder", -1) ?: -1
            if (id.isBlank() || newOrder < 0) {
                call.respondText("잘못된 요청", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@post
            }
            JobsRepository.reorder(id, newOrder)
            call.respondText("ok")
        }

        // ── Torrent API ──

        get("/api/torrents") {
            val engine = RelayApp.getTorrent(context)
            val arr = JSONArray()
            TorrentRepository.all().forEach { t ->
                val (piecesDone, piecesTotal) = engine.pieceInfo(t.id)
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("state", t.state.name)
                    put("progress", t.progress.toDouble())
                    put("downloadSpeed", t.downloadSpeed)
                    put("uploadSpeed", t.uploadSpeed)
                    put("totalSize", t.totalSize)
                    put("downloadedSize", t.downloadedSize)
                    put("startedAt", t.startedAt)
                    put("order", t.order)
                    put("seeds", t.seeds)
                    put("peers", t.peers)
                    put("piecesDone", piecesDone)
                    put("piecesTotal", piecesTotal)
                    put("files", JSONArray().apply {
                        t.files.forEach { f ->
                            put(JSONObject().apply {
                                put("index", f.index)
                                put("path", f.path)
                                put("size", f.size)
                                put("progress", f.progress.toDouble())
                                put("selected", f.selected)
                            })
                        }
                    })
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/torrents/add") {
            try {
                val body = call.receiveText()
                val json = try { JSONObject(body) } catch (_: Exception) { null }
                val magnet = json?.optString("magnet")?.trim() ?: ""
                val torrentFile = json?.optString("torrentFileBase64")?.trim() ?: ""

                when {
                    magnet.startsWith("magnet:") -> {
                        val job = RelayApp.getTorrent(context).addMagnet(magnet)
                        DebugLogger.i("Http", "torrent magnet 추가 id=${job.id}")
                        call.respondText(
                            JSONObject().put("id", job.id).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                    torrentFile.isNotEmpty() -> {
                        val bytes = java.util.Base64.getDecoder().decode(torrentFile)
                        val fn = json?.optString("filename", "torrent") ?: "torrent"
                        val job = RelayApp.getTorrent(context).addTorrentFile(bytes, fn)
                        DebugLogger.i("Http", "torrent 파일 추가 id=${job.id} name=$fn size=${bytes.size}")
                        call.respondText(
                            JSONObject().put("id", job.id).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                    else -> {
                        call.respondErr("magnet 또는 torrentFileBase64 필요")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("Http", "토렌트 추가 실패", e)
                call.respondErr("토렌트 추가 실패: ${e.message}")
            }
        }

        post("/api/torrents/{id}/{action}") {
            val id = call.parameters["id"]!!
            val action = call.parameters["action"]
            val engine = RelayApp.getTorrent(context)
            when (action) {
                "pause" -> { engine.pause(id); call.respondText("ok") }
                "resume" -> { engine.resume(id); call.respondText("ok") }
                else -> call.respondText("지원 없는 동작", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }
        }

        get("/api/torrents/{id}") {
            val id = call.parameters["id"]!!
            val detail = RelayApp.getTorrent(context).getTorrentDetail(id)
            if (detail.isEmpty()) {
                call.respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
            } else {
                call.respondText(detail.toJson(), ContentType.Application.Json)
            }
        }

        delete("/api/torrents/{id}") {
            val id = call.parameters["id"]!!
            when (TorrentRepository.get(id)) {
                null -> call.respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                else -> {
                    DebugLogger.i("Http", "torrent DELETE id=$id")
                    RelayApp.getTorrent(context).cancel(id)
                    call.respondText("ok")
                }
            }
        }

        post("/api/torrents/reorder") {
            val body = call.receiveText()
            val json = try { JSONObject(body) } catch (_: Exception) { null }
            val id = json?.optString("id") ?: ""
            val newOrder = json?.optInt("newOrder", -1) ?: -1
            if (id.isBlank() || newOrder < 0) {
                call.respondText("잘못된 요청", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@post
            }
            TorrentRepository.reorder(id, newOrder)
            call.respondText("ok")
        }

        // ── 보관함 API ──
        val dlRoot = java.io.File("/sdcard/Download/DroidRelay")
        val dlRootCanonical = dlRoot.canonicalFile
        val trashDir = java.io.File(dlRoot, ".trash")

        /** dlRoot 하위로 한정된 canonical File — 탈출 경로는 null */
        fun storageFile(vararg parts: String): java.io.File? {
            var f = dlRoot
            for (p in parts) { if (p.isNotEmpty()) f = java.io.File(f, p) }
            val c = f.canonicalFile
            return if (c.path.startsWith(dlRootCanonical.path)) c else null
        }

        /** 파일·폴더 이름 1개 — 경로 구분자/상대경로 금지 */
        fun safeLeafName(name: String): String? = name.takeIf {
            it.isNotBlank() && !it.contains('/') && !it.contains('\\') && it != "." && it != ".."
        }

        get("/api/storage") {
            val subPath = call.request.queryParameters["path"] ?: ""
            val dir = storageFile(subPath)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                call.respondText("[]", ContentType.Application.Json)
                return@get
            }
            val arr = org.json.JSONArray()
            dir.listFiles()?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name })
                ?.filter { it.name != ".trash" }
                ?.forEach { f ->
                val obj = org.json.JSONObject()
                obj.put("name", f.name)
                obj.put("type", if (f.isDirectory) "dir" else "file")
                obj.put("size", if (f.isFile) f.length() else 0)
                obj.put("count", if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0)
                obj.put("modified", f.lastModified())
                arr.put(obj)
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/storage/mkdir") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val subPath = json.optString("path", "")
            val name = safeLeafName(json.optString("name", ""))
            if (name == null) {
                call.respondErr("이름 없음")
                return@post
            }
            val dir = storageFile(subPath)
            if (dir == null) {
                call.respondErr("잘못된 경로")
                return@post
            }
            val target = java.io.File(dir, name)
            if (target.exists()) {
                call.respondErr("이미 존재")
            } else {
                DebugLogger.i("Http", "폴더 생성 ${target.name}")
                target.mkdirs()
                call.respondOk()
            }
        }

        post("/api/storage/rename") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val fromFile = storageFile(json.optString("from", ""))
            val toName = safeLeafName(json.optString("to", ""))
            if (fromFile == null || toName == null) {
                call.respondErr("이름 없음")
                return@post
            }
            val toFile = java.io.File(fromFile.parentFile, toName)
            if (!fromFile.exists()) {
                call.respondErr("원본 없음")
            } else if (toFile.exists()) {
                call.respondErr("대상 이미 존재")
            } else {
                DebugLogger.i("Http", "이름 변경 ${fromFile.name} → $toName")
                fromFile.renameTo(toFile)
                call.respondOk()
            }
        }

        post("/api/storage/delete") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val path = json.optString("path", "")
            val target = storageFile(path)
            if (target == null) {
                DebugLogger.w("Http", "삭제 경로 탈출 차단 path=$path")
                call.respondErr("잘못된 경로")
                return@post
            }
            if (!target.exists()) {
                call.respondErr("없음")
                return@post
            }
            // 휴지통 내부 대상은 즉시 영구삭제 (웹에서 별도 API 사용 권장)
            if (target.canonicalFile.path.startsWith(trashDir.canonicalFile.path)) {
                DebugLogger.i("Http", "영구삭제 ${target.name}")
                target.deleteRecursively()
                call.respondOk()
                return@post
            }
            // 휴지통 이동 (T-703) — 이름 충돌 시 접미사
            if (!trashDir.exists()) trashDir.mkdirs()
            var dest = java.io.File(trashDir, target.name)
            var i = 2
            while (dest.exists()) { dest = java.io.File(trashDir, "${target.name}-$i"); i++ }
            val moved = target.renameTo(dest)
            if (moved || target.isDirectory.not()) {
                if (!moved) target.copyTo(dest, overwrite = true).let { target.deleteRecursively() }
                DebugLogger.i("Http", "휴지통 이동 ${target.name}${if (dest.name != target.name) " → ${dest.name}" else ""}")
                call.respondOk()
            } else {
                DebugLogger.e("E-AND-STOR-1003", "휴지통 이동 실패 ${target.name}")
                call.respondErr("휴지통 이동 실패")
            }
        }

        // 폴더 트리 (T-608) — 깊이 3까지 재귀, .trash 제외
        get("/api/storage/tree") {
            fun walk(dir: java.io.File, depth: Int): org.json.JSONArray {
                val arr = org.json.JSONArray()
                if (depth > 3) return arr
                dir.listFiles()
                    ?.filter { it.isDirectory && it.name != ".trash" }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { d ->
                        arr.put(org.json.JSONObject().apply {
                            put("name", d.name)
                            put("path", runCatching { d.relativeTo(dlRoot).path }.getOrDefault(d.name))
                            put("children", walk(d, depth + 1))
                        })
                    }
                return arr
            }
            call.respondText(walk(dlRoot, 0).toString(), ContentType.Application.Json)
        }

        // ── 휴지통 API (T-703) ──
        get("/api/storage/trash") {
            val arr = org.json.JSONArray()
            trashDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                arr.put(org.json.JSONObject().apply {
                    put("name", f.name)
                    put("type", if (f.isDirectory) "dir" else "file")
                    put("size", if (f.isFile) f.length() else 0)
                    put("modified", f.lastModified())
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/storage/trash/restore") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val name = safeLeafName(json.optString("name", ""))
            if (name == null) { call.respondErr("이름 없음"); return@post }
            val src = java.io.File(trashDir, name)
            if (!src.exists()) { call.respondErr("없음"); return@post }
            var dest = java.io.File(dlRoot, src.name)
            var i = 2
            while (dest.exists()) { dest = java.io.File(dlRoot, "${src.name}-$i"); i++ }
            val moved = src.renameTo(dest)
            if (moved || !src.isDirectory) {
                if (!moved) src.copyTo(dest, overwrite = true).let { src.deleteRecursively() }
                DebugLogger.i("Http", "휴지통 복원 ${src.name} → 보관함 루트${if (dest.name != src.name) " (${dest.name})" else ""}")
                call.respondOk()
            } else {
                call.respondErr("복원 실패")
            }
        }

        post("/api/storage/trash/purge") {
            val body = try { JSONObject(call.receiveText()) } catch (_: Exception) { JSONObject() }
            val name = safeLeafName(body.optString("name", ""))
            when {
                name != null -> {
                    val t = java.io.File(trashDir, name)
                    if (!t.exists()) { call.respondErr("없음"); return@post }
                    t.deleteRecursively()
                    DebugLogger.i("Http", "휴지통 영구삭제 $name")
                }
                else -> {
                    val count = trashDir.listFiles()?.size ?: 0
                    trashDir.listFiles()?.forEach { it.deleteRecursively() }
                    DebugLogger.i("Http", "휴지통 비우기 ${count}건")
                }
            }
            call.respondOk()
        }

        post("/api/storage/move") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val fromName = json.optString("from", "")
            val toDir = json.optString("to", "")
            if (fromName.isBlank()) {
                call.respondErr("원본 없음")
                return@post
            }
            val src = storageFile(fromName)
            val dstDir = storageFile(toDir)
            if (src == null || dstDir == null) {
                DebugLogger.w("Http", "이동 경로 탈출 차단 from=$fromName to=$toDir")
                call.respondErr("잘못된 경로")
                return@post
            }
            val dst = java.io.File(dstDir, src.name)
            if (!src.exists()) {
                call.respondErr("원본 없음")
                return@post
            }
            if (src == dst) {
                DebugLogger.d("Http", "이동 무시(동일 위치) ${src.name}")
                call.respondOk()
                return@post
            }
            if (src.isDirectory && dst.path.startsWith(src.path + java.io.File.separator)) {
                call.respondErr("폴더를 자기 하위로 이동할 수 없습니다")
                return@post
            }
            if (!dstDir.exists()) dstDir.mkdirs()
            DebugLogger.i("Http", "이동 ${src.name} → ${dstDir.path.removePrefix(dlRootCanonical.path)}")
            // 안전 이동: rename 우선(원자적) → 실패 시 copy + 크기 검증 후 원본 삭제
            val moved = if (src.renameTo(dst)) {
                true
            } else {
                try {
                    src.copyTo(dst, overwrite = true)
                    if (dst.length() == src.length()) {
                        src.deleteRecursively()
                        true
                    } else {
                        DebugLogger.e("E-AND-STOR-1002", "이동 검증 실패 → 복사본 폐기, 원본 보존 ${src.name}")
                        dst.deleteRecursively()
                        false
                    }
                } catch (e: Exception) {
                    DebugLogger.e("E-AND-STOR-1002", "이동 실패 ${src.name}: ${e.message}")
                    dst.deleteRecursively()
                    false
                }
            }
            if (moved) {
                call.respondOk()
            } else {
                call.respondErr("이동 실패 (원본 보존)")
            }
        }

        // Raw binary 업로드 — multipart 없이 스트리밍 (대용량 파일 지원)
        post("/api/storage/raw-upload") {
            try {
                val fileName = call.request.headers["X-File-Name"] ?: "upload"
                val subPath = call.request.headers["X-File-Path"] ?: ""
                val dir = storageFile(subPath)
                if (dir == null) { call.respondErr("잘못된 경로"); return@post }
                dir.mkdirs()
                val safeName = safeLeafName(fileName) ?: "upload"
                val file = java.io.File(dir, safeName)

                // raw body → 파일 스트리밍 (메모리에 로드 없이)
                val channel = call.request.receiveChannel()
                java.io.FileOutputStream(file).use { fos ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n == -1) break
                        if (n > 0) fos.write(buf, 0, n)
                    }
                }
                DebugLogger.i("Http", "업로드 완료 ${file.name} (${file.length()}B)")
                call.respondOk()
            } catch (e: Exception) {
                DebugLogger.e("Http", "업로드 실패", e)
                call.respondErr("업로드 실패: ${e.message}")
            }
        }

        post("/api/storage/upload") {
            try {
                val isMultipart = call.request.headers["Content-Type"]?.contains("multipart/form-data") == true
                var subPath = ""
                var fileName = ""
                var fileBytes: ByteArray? = null

                if (isMultipart) {
                    val multipart = call.receiveMultipart()
                    var part: PartData? = multipart.readPart()
                    while (part != null) {
                        when (part) {
                            is PartData.FormItem -> {
                                val value = part.value
                                when (part.name) {
                                    "path" -> subPath = value
                                    "name" -> fileName = value
                                }
                            }
                            is PartData.FileItem -> {
                                fileName = fileName.ifEmpty { part.originalFileName ?: "upload" }
                                fileBytes = part.provider().toByteArray()
                            }
                            else -> {}
                        }
                        part.dispose()
                        part = multipart.readPart()
                    }
                } else {
                    val body = call.receiveText()
                    val json = org.json.JSONObject(body)
                    subPath = json.optString("path", "")
                    fileName = safeLeafName(json.optString("name", "")) ?: ""
                    val data = json.optString("data", "")
                    if (data.isNotEmpty()) {
                        fileBytes = java.util.Base64.getDecoder().decode(data)
                    }
                }

                val safeName = safeLeafName(fileName)
                if (safeName == null || fileBytes == null || fileBytes.isEmpty()) {
                    call.respondErr("파일 없음")
                    return@post
                }
                val dir = storageFile(subPath)
                if (dir == null) {
                    call.respondErr("잘못된 경로")
                    return@post
                }
                dir.mkdirs()
                val file = java.io.File(dir, safeName)
                DebugLogger.i("Http", "업로드 ${file.name} (${fileBytes.size}B)")
                file.writeBytes(fileBytes)
                call.respondOk()
            } catch (e: Exception) {
                DebugLogger.e("Http", "업로드 실패", e)
                call.respondErr("업로드 실패: ${e.message}")
            }
        }

        get("/dl-file/{name...}") {
            val name = call.parameters.getAll("name")?.joinToString("/") ?: ""
            val file = storageFile(name)
            if (file == null || !file.exists() || !file.isFile) {
                DebugLogger.w("Http", "다운로드 경로 차단 name=$name")
                call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
            } else {
                DebugLogger.i("Http", "파일 다운로드 요청 name=$name")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
                call.respondBytesWriter(contentType = ContentType.Application.OctetStream, contentLength = file.length()) {
                    file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            writeFully(buf, 0, read)
                        }
                    }
                }
            }
        }

        // ── SSE 실시간 푸시 (T-701) — 웹은 tick 수신 시 refresh, 끊기면 폴링 폴백 ──
        get("/api/events") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                try {
                    while (true) {
                        writeStringUtf8("data: tick\n\n")
                        flush()
                        delay(1000)
                    }
                } catch (_: Exception) {
                    DebugLogger.d("SSE", "클라이언트 연결 종료")
                }
            }
        }

        get("/file/{id}") {
            val id = call.parameters["id"]!!
            val job = JobsRepository.get(id)
            val file = job?.let { RelayApp.get(context).doneFile(it) }
            when {
                job == null || file == null ->
                    call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                job.state != JobState.DONE ->
                    call.respondText(
                        "아직 완료되지 않았습니다 (${job.state})",
                        ContentType.Text.Plain,
                        HttpStatusCode.Conflict,
                    )
                else -> {
                    DebugLogger.d(
                        "Http",
                        "파일 전송 시작 id=$id '${file.name}' Range=${call.request.headers[HttpHeaders.Range] ?: "-"}",
                    )
                    call.serveFile(file, id)
                }
            }
        }

        // ── 디버그 API ──
        get("/api/debug/logs") {
            val level = call.request.queryParameters["level"]
            val tag = call.request.queryParameters["tag"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
            val since = call.request.queryParameters["since"]
            var lines = DebugLogger.lines()
            if (level != null) lines = lines.filter { it.contains("[$level]") }
            if (tag != null) lines = lines.filter { it.contains("[$tag]") }
            if (since != null) lines = lines.filter { it.substringAfter("[").substringBefore("]") > since }
            val result = lines.takeLast(limit)
            call.respondText(
                JSONObject().apply {
                    put("count", result.size)
                    put("total", DebugLogger.count())
                    put("lines", JSONArray(result))
                }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/debug/api-calls") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val lines = DebugLogger.apiLines().takeLast(limit)
            call.respondText(
                JSONObject().apply {
                    put("count", lines.size)
                    put("total", DebugLogger.apiCount())
                    put("calls", JSONArray(lines))
                }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/debug/status") {
            call.respondText(
                JSONObject().apply {
                    put("enabled", DebugLogger.enabled)
                    put("logCount", DebugLogger.count())
                    put("apiCallCount", DebugLogger.apiCount())
                    put("maxLines", 300)
                    val appVersion = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "0.0.0"
                    put("version", appVersion)
                    put("uptime", System.currentTimeMillis() - RelayApp.startTime)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/debug/clear") {
            DebugLogger.clear()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        get("/api/debug/overlay") {
            val hasPermission = Settings.canDrawOverlays(context)
            call.respondText(
                JSONObject().apply {
                    put("hasPermission", hasPermission)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/debug/overlay/toggle") {
            val hasPermission = Settings.canDrawOverlays(context)
            if (!hasPermission) {
                call.respondText("""{"error":"권한 없음","running":false}""", ContentType.Application.Json)
                return@post
            }
            val wasRunning = DebugOverlayService.isRunning
            if (wasRunning) {
                DebugOverlayService.stop(context)
            } else {
                runCatching { DebugOverlayService.start(context) }
            }
            call.respondText(
                JSONObject().apply { put("running", !wasRunning) }.toString(),
                ContentType.Application.Json
            )
        }
    }

    // ── MCP JSON-RPC 2.0 (Phase 2.1) ──
    McpServer.installRoutes(context, this, serverRef)
}

/** Range(이어받기) 지원 파일 스트리밍 */
private suspend fun ApplicationCall.serveFile(file: File, jobId: String) {
    val total = file.length()
    response.header(HttpHeaders.AcceptRanges, "bytes")

    val range = RangeParser.parse(request.headers[HttpHeaders.Range], total)
    if (range.invalid) {
        response.header(HttpHeaders.ContentRange, "bytes */$total")
        respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }

    val length = range.to - range.from + 1L
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
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
