package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONArray
import org.json.JSONObject

/** 토렌트 라우트 (T-936 D1 — RelayServer에서 분리) */
internal fun Route.torrentRoutes(context: Context, serverRef: RelayServer) {
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
                    // 중복 가드: 이미 동일 infohash가 있으면 409 (이슈 2 — 같은 토렌트로 표시 문제)
                    val eng = RelayApp.getTorrent(context)
                    if (eng.isDuplicateMagnet(magnet)) {
                        DebugLogger.w("Http", "torrent 중복 magnet 추가 시도 → 거부")
                        call.respondText(
                            JSONObject().put("error", "이미 다운로드 중인 토렌트입니다").toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Conflict,
                        )
                    } else {
                        val job = eng.addMagnet(magnet)
                        DebugLogger.i("Http", "torrent magnet 추가 id=${job.id}")
                        call.respondText(
                            JSONObject().put("id", job.id).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
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
}
