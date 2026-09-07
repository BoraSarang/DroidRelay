package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONArray
import org.json.JSONObject

/** 다운로드 잡 + 비디오 라우트 (T-936 D1 — RelayServer에서 분리) */
internal fun Route.jobRoutes(context: Context, serverRef: RelayServer) {
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
                put("segmentsTotal", j.segmentsTotal)
                put("segmentsDone", j.segmentsDone)
                put("totalDurationMs", j.totalDurationMs)
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
        // 중복 가드 — 동일 URL이 이미 등록(진행/일시정지/완료)되면 409 (T-947)
        JobsRepository.findDuplicateUrl(url.trim())?.let { dup ->
            DebugLogger.w("Http", "중복 URL 추가 시도 → 거부 id=${dup.id}")
            call.respondText(
                JSONObject().put("error", "E-AND-DOWN-1006: 이미 등록된 다운로드입니다").put("id", dup.id).toString(),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return@post
        }
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
     * POST /api/video/analyze — URL 분석 (StreamDetector)
     */
    post("/api/video/analyze") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val url = json?.optString("url", "")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            call.respondText("E-AND-VID-0101: 주소가 올바르지 않거나 지원하지 않는 URL입니다", ContentType.Text.Plain, HttpStatusCode.UnprocessableEntity)
            return@post
        }
        try {
            val result = VideoApi.analyze(url)
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
     * 스트림: {url, streamUrl(선택), filename(선택)} — 원본 copy
     */
    post("/api/video/create") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val url = json?.optString("url", "")?.trim().orEmpty()
        val streamUrl = json?.optString("streamUrl", "")?.trim().orEmpty()
        val wantName = json?.optString("filename", "")?.trim().orEmpty()
        val job = try {
            VideoApi.create(
                context, url,
                streamUrl.ifBlank { null },
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
