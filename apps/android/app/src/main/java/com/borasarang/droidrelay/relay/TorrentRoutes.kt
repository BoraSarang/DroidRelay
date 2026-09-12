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
            val torrentUrl = json?.optString("torrentUrl")?.trim() ?: ""

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
                    if (torrentUrl.startsWith("http://") || torrentUrl.startsWith("https://")) {
                        val job = RelayApp.getTorrent(context).addTorrentUrl(torrentUrl)
                        DebugLogger.i("Http", "torrent URL 추가 id=${job.id} url=${torrentUrl.take(80)}")
                        call.respondText(
                            JSONObject().put("id", job.id).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    } else {
                        call.respondErr("magnet 또는 torrentFileBase64/torrentUrl 필요")
                    }
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
            "files" -> {
                if (TorrentRepository.get(id) == null) {
                    call.respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    return@post
                }
                val json = try { JSONObject(call.receiveText()) } catch (_: Exception) { null }
                val arr = json?.optJSONArray("selected")
                if (arr == null) { call.respondErr("selected 배열 필요"); return@post }
                val selected = (0 until arr.length()).mapNotNull { runCatching { arr.getInt(it) }.getOrNull() }.toSet()
                if (engine.setFileSelection(id, selected)) call.respondText("ok")
                else call.respondErr("파일 목록 없음 — 메타데이터 수신 후 시도")
            }
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

    get("/api/search") {
        val q = call.request.queryParameters["q"]?.trim() ?: ""
        if (q.isBlank()) {
            call.respondText("[]", ContentType.Application.Json)
            return@get
        }
        try {
            val results = RelayApp.getTorrent(context).search(q)
            val arr = JSONArray()
            results.forEach { r ->
                arr.put(JSONObject().apply {
                    put("title", r.title)
                    put("size", r.size)
                    put("seeders", r.seeders)
                    put("peers", r.peers)
                    put("magnet", r.magnet ?: JSONObject.NULL)
                    put("url", r.url ?: JSONObject.NULL)
                    put("indexer", r.indexer)
                })
            }
            call.respondText(arr.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            DebugLogger.w("Http", "토렌트 검색 실패 q=${q.take(40)}: ${e.message}")
            call.respondText(
                JSONObject().put("error", e.message ?: "검색 실패").toString(),
                ContentType.Application.Json,
                HttpStatusCode.BadGateway,
            )
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

    get("/api/torrents/trackers") {
        val list = TrackerListProvider.getCached(context)
        val probe = TrackerProbe.getProbeCached(context)
        val engine = RelayApp.getTorrent(context)
        call.respondText(
            JSONObject().apply {
                put("count", list.size)
                put("trackers", JSONArray(list))
                put("cacheAgeMs", TrackerListProvider.cacheAgeMs(context))
                put("source", TrackerListProvider.SOURCE_URL)
                put("probing", engine.probingTrackers)
                put("probeAgeMs", TrackerProbe.probeAgeMs(context))
                put("probeOk", probe.values.count { it.result.reachable })
                put("probed", JSONObject().apply {
                    probe.forEach { (u, c) ->
                        put(u, JSONObject().apply {
                            put("reachable", c.result.reachable)
                            put("rttMs", c.result.rttMs)
                        })
                    }
                })
            }.toString(),
            ContentType.Application.Json,
        )
    }

    post("/api/torrents/trackers/refresh") {
        val list = TrackerListProvider.refresh(context)
        DebugLogger.i("Http", "[FEATURE] 트래커 수동 동기 ${list.size}개")
        RelayApp.getTorrent(context).probeTrackers()
        call.respondText(
            JSONObject().apply {
                put("count", list.size)
                put("trackers", JSONArray(list))
            }.toString(),
            ContentType.Application.Json,
        )
    }

    post("/api/torrents/trackers/probe") {
        val started = RelayApp.getTorrent(context).probeTrackers()
        call.respondText(
            JSONObject().apply { put("started", started) }.toString(),
            ContentType.Application.Json,
        )
    }
}
