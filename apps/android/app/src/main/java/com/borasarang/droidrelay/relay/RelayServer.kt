package com.borasarang.droidrelay.relay

import android.content.Context
import android.os.StatFs
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.server.response.header
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
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.NetworkInterface
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
    @Volatile private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile var settings: AppSettings = AppSettings()

    fun updateSettings(s: AppSettings) {
        settings = s
        DebugLogger.d("Server", "설정 스냅샷 갱신 port=${s.port} auth=${s.webAuthEnabled} limit=${s.speedLimitKbps}KB/s")
    }

    fun start() {
        if (server != null) return
        val ctx = context
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            relayRoutes(ctx, this@RelayServer)
        }
            .start(wait = false)
        DebugLogger.i("Server", "기동 완료 http://0.0.0.0:$port (LAN=${lanAddress() ?: "?"})")
    }

    fun stop() {
        runCatching { server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500) }
        server = null
        DebugLogger.i("Server", "서버 정지")
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
    // 보안 파이프라인: IP 게이트 → Basic Auth
    intercept(ApplicationCallPipeline.Plugins) {
        val s = serverRef.settings
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

    routing {
        get("/") {
            call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
            call.respondText(WebAssets.dashboardHtml, ContentType.Text.Html)
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
                    put("hasChecksum", j.expectedSha256 != null)
                    put("verified", j.verified)
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
            // 선택 체크섬 (T-704): 64자리 16진수만 허용
            val rawSha = json?.optString("sha256", "") ?: ""
            val sha256 = if (Regex("^[0-9a-fA-F]{64}$").matches(rawSha)) rawSha.lowercase() else null
            val job = RelayApp.get(context).enqueue(url.trim(), sha256)
            DebugLogger.i("Http", "POST 수락 id=${job.id} file='${job.filename}'")
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
                "pause" -> { engine.pause(id); call.respondText("ok") }
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
                    RelayApp.get(context).cancel(id)
                    JobsRepository.remove(id)
                    call.respondText("ok")
                }
            }
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
    }
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
