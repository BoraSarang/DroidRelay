package com.borasarang.droidrelay.relay

import android.content.Context
import android.provider.Settings
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
                put("configVersion", SettingsMigration.CURRENT_VERSION)
                put("jobsSchema", PersistenceGuard.JOBS_SCHEMA_VERSION)
                put("torrentsSchema", PersistenceGuard.TORRENTS_SCHEMA_VERSION)
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/debug/bundle") {
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
