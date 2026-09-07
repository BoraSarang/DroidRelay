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

/** 만료 공유 링크 라우트 (T-951). /s/ 경로는 토큰 자체가 권한이라 BasicAuth 예외. */
internal fun Route.shareRoutes(context: Context, serverRef: RelayServer) {
    post("/api/share") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val path = json?.optString("path", "") ?: ""
        val hours = json?.optInt("hours", 24) ?: 24
        val file = StorageGuard.storageFile(path)
        if (file == null || !file.exists() || !file.isFile) {
            DebugLogger.w("Share", "공유 발급 경로 차단 path=$path")
            call.respondErr("공유할 파일을 찾을 수 없습니다")
            return@post
        }
        val link = ShareRepository.create(context, path, hours)
        call.respondText(
            JSONObject().apply {
                put("token", link.token)
                put("url", "/s/${link.token}")
                put("expiresAt", link.expiresAt)
            }.toString(),
            ContentType.Application.Json,
        )
    }

    get("/api/share") {
        val arr = JSONArray()
        ShareRepository.all(context).forEach { l ->
            arr.put(JSONObject().apply {
                put("token", l.token)
                put("path", l.path)
                put("url", "/s/${l.token}")
                put("expiresAt", l.expiresAt)
            })
        }
        call.respondText(arr.toString(), ContentType.Application.Json)
    }

    delete("/api/share/{token}") {
        val token = call.parameters["token"] ?: ""
        if (ShareRepository.delete(context, token)) call.respondText("ok")
        else call.respondText("없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }

    get("/s/{token}") {
        val token = call.parameters["token"] ?: ""
        val link = ShareRepository.get(context, token)
        if (link == null) {
            call.respondText("만료되었거나 없는 링크입니다", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        val file = StorageGuard.storageFile(link.path)
        if (file == null || !file.exists() || !file.isFile) {
            call.respondText("파일이 없습니다", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        DebugLogger.i("Share", "공유 다운로드 token=${token.take(4)}… '${file.name}'")
        call.serveFile(file, "share", inline = false, contentType = null)
    }
}
