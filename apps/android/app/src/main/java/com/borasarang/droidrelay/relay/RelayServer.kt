package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
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
import org.json.JSONArray
import org.json.JSONObject

object RelayApp {
    private const val TAG = "App"
    @Volatile var engine: DownloadEngine? = null

    fun get(ctx: Context): DownloadEngine =
        engine ?: synchronized(this) {
            DebugLogger.i(TAG, "DownloadEngine 최초 생성")
            engine ?: DownloadEngine(ctx.applicationContext).also { engine = it }
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
            if (nif.name in preferred) {
                DebugLogger.d("Net", "LAN 주소 확정: $host ($nif.name)")
                return host
            }
            candidates += host
        }
    }
    val fallback = candidates.firstOrNull()
    DebugLogger.d("Net", "선호 인터페이스 없음 → 폴백: ${fallback ?: "없음"}")
    return fallback
}

class RelayServer(
    private val context: Context,
    private val port: Int = 8080,
) {
    @Volatile private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        if (server != null) {
            DebugLogger.w("Server", "이미 실행 중 — 중복 시작 무시")
            return
        }
        val ctx = context
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { relayRoutes(ctx) }
            .start(wait = false)
        DebugLogger.i("Server", "기동 완료 http://0.0.0.0:$port (LAN=${lanAddress() ?: "?"})")
    }

    fun stop() {
        runCatching { server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500) }
        server = null
        DebugLogger.i("Server", "서버 정지")
    }
}

private fun Application.relayRoutes(context: Context) {
    routing {
        get("/") {
            DebugLogger.d("Http", "${call.request.httpMethod.value} / 대시보드 요청")
            call.respondText(WebAssets.dashboardHtml, ContentType.Text.Html)
        }

        get("/api/jobs") {
            DebugLogger.d("Http", "GET /api/jobs (${JobsRepository.all().size}건)")
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
                    put("errorMessage", j.errorMessage ?: JSONObject.NULL)
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        }

        post("/api/jobs") {
            val body = call.receiveText()
            val url = try { JSONObject(body).optString("url") } catch (_: Exception) { "" }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                DebugLogger.w(
                    "Http",
                    "POST 거부 — 유효하지 않은 URL (E-AND-DOWN-1003) body='${body.take(80)}'",
                )
                call.respondText(
                    "E-AND-DOWN-1003: 유효한 http(s) URL이 아닙니다",
                    ContentType.Text.Plain,
                    HttpStatusCode.UnprocessableEntity,
                )
                return@post
            }
            val job = RelayApp.get(context).enqueue(url.trim())
            DebugLogger.i("Http", "POST 수락 id=${job.id}")
            call.respondText(
                JSONObject().put("id", job.id).toString(),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        delete("/api/jobs/{id}") {
            val id = call.parameters["id"]!!
            when (JobsRepository.get(id)) {
                null -> {
                    DebugLogger.w("Http", "DELETE 실패(대상 없음) id=$id")
                    call.respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                }
                else -> {
                    DebugLogger.i("Http", "DELETE 처리 id=$id")
                    RelayApp.get(context).cancel(id)
                    JobsRepository.remove(id)
                    call.respondText("ok")
                }
            }
        }

        get("/file/{id}") {
            val id = call.parameters["id"]!!
            val job = JobsRepository.get(id)
            val file = job?.let { RelayApp.get(context).doneFile(it) }
            when {
                job == null || file == null -> {
                    DebugLogger.w("Http", "파일 404 id=$id")
                    call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                }
                job.state != JobState.DONE -> {
                    DebugLogger.w("Http", "미완료 파일 요청 id=$id state=${job.state} → 409")
                    call.respondText(
                        "아직 완료되지 않았습니다 (${job.state})",
                        ContentType.Text.Plain,
                        HttpStatusCode.Conflict,
                    )
                }
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
        DebugLogger.w("Serve", "416 범위 불가 id=$jobId range='${request.headers[HttpHeaders.Range]}' total=$total (E-AND-DOWN-1003)")
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

/** HTTP Range 헤더 파서 (순수 로직 — 단위 테스트 대상) */
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
