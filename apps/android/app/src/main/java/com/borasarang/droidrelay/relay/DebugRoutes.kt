package com.borasarang.droidrelay.relay

import android.content.Context
import android.provider.Settings
import com.borasarang.droidrelay.BuildConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** 메트릭스·SSE·디버그 라우트 (T-936 D1 — RelayServer에서 분리) */
internal fun Route.debugRoutes(context: Context, serverRef: RelayServer) {
    // SSE 폴링 주기 — 상태 확인 간격. 실제 tick 은 변경이 있을 때만 나간다.
    val SSE_POLL_MS = 1_000L
    val SSE_HEARTBEAT_MS = 15_000L
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

    // ── SSE 실시간 푸시 (T-701) — 웹은 tick 수신 시 refresh, 끊기면 폴링 폴백 ──
    //
    // 변경 기반 푸시로 전환. 이전 구현은 무조건 1초마다 tick 을 보내고
    // 클라이언트가 tick 마다 4개 HTTP 요청(/api/info·jobs·torrents·guard/status)을 날려
    // 대시보드 탭 1개 = 분당 264 요청 + 전체 JSON 직렬화가 무한 반복됐다.
    // 이제 실제 상태가 바뀐 경우에만 tick 을 보내고, 유휴 시에는
    // HEARTBEAT_MS 마다 연결 유지용 beat 만 보낸다(브라우저·프록시 타임아웃 방지).
    get("/api/events") {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            var lastSig = ""
            var lastBeat = 0L
            try {
                while (true) {
                    val sig = stateSignature()
                    if (sig != lastSig) {
                        lastSig = sig
                        lastBeat = System.currentTimeMillis()
                        writeStringUtf8("data: tick\n\n")
                        flush()
                    } else if (System.currentTimeMillis() - lastBeat >= SSE_HEARTBEAT_MS) {
                        lastBeat = System.currentTimeMillis()
                        writeStringUtf8("data: beat\n\n")
                        flush()
                    }
                    delay(SSE_POLL_MS)
                }
            } catch (_: Exception) {
                DebugLogger.d("SSE", "클라이언트 연결 종료")
            }
        }
    }

    // ── 디버그 API — 릴리즈 빌드에서는 차단 (시크릿·로그 노출 방지) ──
    get("/api/debug/logs") {
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@get
        }
        val level = call.request.queryParameters["level"]
        val tag = call.request.queryParameters["tag"]
        // 하한 없으면 ?limit=-1 이 takeLast 에서 IllegalArgumentException → 라우트 전체 실패
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 300).coerceIn(1, 5000)
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
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@get
        }
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 5000)
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
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@get
        }
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
                put("configVersion", SettingsMigration.CURRENT_VERSION)
                put("jobsSchema", PersistenceGuard.JOBS_SCHEMA_VERSION)
                put("torrentsSchema", PersistenceGuard.TORRENTS_SCHEMA_VERSION)
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/debug/bundle") {
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@get
        }
        try {
            DebugLogger.i("Debug", "[FEATURE] 진단번들 요청")
            val settings = runCatching { SettingsRepository.get(context).firstBlocking() }.getOrNull()
            val settingsMasked: Map<String, Any?> =
                if (settings != null) PersistenceGuard.maskSecrets(settings) else mapOf("unavailable" to true)
            val jobsRaw = runCatching {
                val f = java.io.File(context.getExternalFilesDir(null), "jobs.json")
                if (f.exists()) f.readText() else "[]"
            }.getOrDefault("<unavailable: corrupt, backup kept>")
            val torrentsRaw = runCatching {
                val f = java.io.File(context.filesDir, "torrents.json")
                if (f.exists()) f.readText() else "[]"
            }.getOrDefault("<unavailable: corrupt, backup kept>")
            val jobs = JobsRepository.all()
            val torrents = TorrentRepository.all()
            val metricsJson = JSONObject().apply {
                put("downloads_total", jobs.size)
                put("torrents_total", torrents.size)
                put("uptime_ms", System.currentTimeMillis() - RelayApp.startTime)
            }.toString()
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.0.0"
            val deviceJson = JSONObject().apply {
                put("version", appVersion)
                put("uptime", System.currentTimeMillis() - RelayApp.startTime)
                put("configVersion", SettingsMigration.CURRENT_VERSION)
                put("jobsSchema", PersistenceGuard.JOBS_SCHEMA_VERSION)
                put("torrentsSchema", PersistenceGuard.TORRENTS_SCHEMA_VERSION)
            }.toString()
            val entries = DebugBundle.buildEntries(
                logs = DebugLogger.lines().takeLast(300),
                apiCalls = DebugLogger.apiLines().takeLast(100),
                metricsJson = metricsJson,
                settingsMasked = settingsMasked,
                jobsRaw = jobsRaw,
                torrentsRaw = torrentsRaw,
                deviceJson = deviceJson,
            )
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            call.response.header(HttpHeaders.ContentDisposition, DispositionHeader.make("droidrelay-debug-$stamp.zip"))
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
            call.respondOutputStream(contentType = ContentType.Application.Zip) {
                java.io.BufferedOutputStream(this, 256 * 1024).use { buffered ->
                    java.util.zip.ZipOutputStream(buffered).use { zip ->
                        zip.setLevel(0)
                        entries.toSortedMap().forEach { (name, bytes) ->
                            zip.putNextEntry(java.util.zip.ZipEntry(name))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
                }
            }
            DebugLogger.i("Debug", "진단번들 완료 ${entries.size}엔트리")
        } catch (e: Exception) {
            DebugLogger.e("Debug", "진단번들 실패", e)
            call.respondText(
                """{"error":"번들 생성 실패"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/api/debug/clear") {
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@post
        }
        DebugLogger.clear()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    get("/api/debug/overlay") {
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@get
        }
        val hasPermission = Settings.canDrawOverlays(context)
        call.respondText(
            JSONObject().apply {
                put("hasPermission", hasPermission)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/debug/overlay/toggle") {
        if (!BuildConfig.DEBUG) {
            call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@post
        }
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

/**
 * SSE 변경 감지용 경량 서명.
 * 잡/토렌트 수와 (상태, 진행률 버킷) 만 반영 — 속도 수치는 UI 갱신에 필요하지 않고
 * 매 초 변해 시그니처가 계속 달라져 변경 감지가 무의미해진다.
 * 호출 1회당 String 1개만 할당한다.
 */
private fun stateSignature(): String {
    val jobs = JobsRepository.all()
    val torrents = TorrentRepository.all()
    if (jobs.isEmpty() && torrents.isEmpty()) return "empty"
    val sb = StringBuilder(16 + jobs.size * 6 + torrents.size * 6)
    sb.append('j').append(jobs.size)
    for (j in jobs) {
        sb.append(j.state.ordinal)
        // 진행률 0.5% 버킷 — UI 표시 해상도와 같아 불필요한 재렌더를 막는다
        sb.append((j.progress * 200).toInt())
    }
    sb.append('t').append(torrents.size)
    for (t in torrents) {
        sb.append(t.state.ordinal)
        sb.append((t.progress * 200).toInt())
    }
    return sb.toString()
}
