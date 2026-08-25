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
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
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
        private set

    fun updateSettings(s: AppSettings) {
        settings = s
        DebugLogger.d("Server", "설정 스냅샷 갱신 port=${s.port} auth=${s.webAuthEnabled} limit=${s.speedLimitKbps}KB/s")
    }

    fun start() {
        if (server != null) return
        val ctx = context
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { relayRoutes(ctx, this@RelayServer) }
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
        get("/") { call.respondText(WebAssets.dashboardHtml, ContentType.Text.Html) }

        get("/api/info") {
            val dir = RelayApp.get(context).workDir
            val stat = runCatching { StatFs(dir.path) }.getOrNull()
            val jobs = JobsRepository.all()
            val info = JSONObject().apply {
                put("ip", lanAddress() ?: JSONObject.NULL)
                put("port", serverRef.port)
                put("version", "0.2")
                put("storageFree", stat?.availableBytes ?: JSONObject.NULL)
                put("storageTotal", stat?.totalBytes ?: JSONObject.NULL)
                put("running", jobs.count { it.state == JobState.RUNNING })
                put("speedTotalBps", jobs.filter { it.state == JobState.RUNNING }.sumOf { it.speedBps })
            }
            call.respondText(info.toString(), ContentType.Application.Json)
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
                    put("errorMessage", j.errorMessage ?: JSONObject.NULL)
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/jobs") {
            val body = call.receiveText()
            val url = try { JSONObject(body).optString("url") } catch (_: Exception) { "" }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                DebugLogger.w("Http", "POST 거부 — 유효하지 않은 URL (E-AND-DOWN-1003) body='${body.take(80)}'")
                call.respondText(
                    "E-AND-DOWN-1003: 유효한 http(s) URL이 아닙니다",
                    ContentType.Text.Plain,
                    HttpStatusCode.UnprocessableEntity,
                )
                return@post
            }
            val job = RelayApp.get(context).enqueue(url.trim())
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

        // ── Torrent API ──

        get("/api/torrents") {
            val arr = JSONArray()
            TorrentRepository.all().forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("state", t.state.name)
                    put("progress", t.progress.toDouble())
                    put("downloadSpeed", t.downloadSpeed)
                    put("uploadSpeed", t.uploadSpeed)
                    put("totalSize", t.totalSize)
                    put("downloadedSize", t.downloadedSize)
                    put("seeds", t.seeds)
                    put("peers", t.peers)
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
                    val filename = json?.optString("filename", "torrent") ?: "torrent"
                    val job = RelayApp.getTorrent(context).addTorrentFile(bytes, filename)
                    DebugLogger.i("Http", "torrent 파일 추가 id=${job.id} name=$filename")
                    call.respondText(
                        JSONObject().put("id", job.id).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                else -> {
                    call.respondText(
                        "magnet 또는 torrentFileBase64 필요",
                        ContentType.Text.Plain,
                        HttpStatusCode.UnprocessableEntity,
                    )
                }
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

        // ── 보관함 API ──
        val dlRoot = java.io.File("/sdcard/Download/DroidRelay")

        get("/api/storage") {
            val subPath = call.request.queryParameters["path"] ?: ""
            val dir = if (subPath.isEmpty()) dlRoot else java.io.File(dlRoot, subPath)
            if (!dir.exists() || !dir.isDirectory) {
                call.respondText("[]", ContentType.Application.Json)
                return@get
            }
            val arr = org.json.JSONArray()
            dir.listFiles()?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name })?.forEach { f ->
                val obj = org.json.JSONObject()
                obj.put("name", f.name)
                obj.put("type", if (f.isDirectory) "dir" else "file")
                obj.put("size", if (f.isFile) f.length() else 0)
                obj.put("count", if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0)
                arr.put(obj)
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/storage/mkdir") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val subPath = json.optString("path", "")
            val name = json.optString("name", "")
            if (name.isBlank()) {
                call.respondText("""{"error":"이름 없음"}""", ContentType.Application.Json)
                return@post
            }
            val dir = if (subPath.isEmpty()) dlRoot else java.io.File(dlRoot, subPath)
            val target = java.io.File(dir, name)
            if (target.exists()) {
                call.respondText("""{"error":"이미 존재"}""", ContentType.Application.Json)
            } else {
                target.mkdirs()
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
        }

        post("/api/storage/rename") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val fromName = json.optString("from", "")
            val toName = json.optString("to", "")
            if (fromName.isBlank() || toName.isBlank()) {
                call.respondText("""{"error":"이름 없음"}""", ContentType.Application.Json)
                return@post
            }
            val fromFile = java.io.File(dlRoot, fromName)
            val toFile = java.io.File(dlRoot, toName)
            if (!fromFile.exists()) {
                call.respondText("""{"error":"원본 없음"}""", ContentType.Application.Json)
            } else if (toFile.exists()) {
                call.respondText("""{"error":"대상 이미 존재"}""", ContentType.Application.Json)
            } else {
                fromFile.renameTo(toFile)
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
        }

        post("/api/storage/delete") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val path = json.optString("path", "")
            val target = java.io.File(dlRoot, path)
            if (!target.exists()) {
                call.respondText("""{"error":"없음"}""", ContentType.Application.Json)
            } else {
                target.deleteRecursively()
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
        }

        post("/api/storage/move") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val fromName = json.optString("from", "")
            val toDir = json.optString("to", "")
            if (fromName.isBlank()) {
                call.respondText("""{"error":"원본 없음"}""", ContentType.Application.Json)
                return@post
            }
            val src = java.io.File(dlRoot, fromName)
            val dstDir = if (toDir.isEmpty()) dlRoot else java.io.File(dlRoot, toDir)
            val dst = java.io.File(dstDir, src.name)
            if (!src.exists()) {
                call.respondText("""{"error":"원본 없음"}""", ContentType.Application.Json)
            } else {
                src.copyTo(dst, overwrite = true)
                src.deleteRecursively()
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
        }

        post("/api/storage/upload") {
            val body = call.receiveText()
            val json = org.json.JSONObject(body)
            val subPath = json.optString("path", "")
            val name = json.optString("name", "")
            val data = json.optString("data", "")
            if (name.isBlank() || data.isBlank()) {
                call.respondText("""{"error":"파일 없음"}""", ContentType.Application.Json)
                return@post
            }
            val dir = if (subPath.isEmpty()) dlRoot else java.io.File(dlRoot, subPath)
            dir.mkdirs()
            val file = java.io.File(dir, name)
            file.writeBytes(java.util.Base64.getDecoder().decode(data))
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        get("/dl-file/{name...}") {
            val name = call.parameters.getAll("name")?.joinToString("/") ?: ""
            val file = java.io.File(dlRoot, name)
            if (!file.exists() || !file.isFile) {
                call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
            } else {
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
    n < 1_073_741_824 -> String.format("%.1fMB", n / 1_048_576.0)
    else -> String.format("%.2fGB", n / 1_073_741_824.0)
}
