package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.method
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 보관함 읽기 전용 WebDAV — VLC/nPlayer 외부 재생용 (T-944). 쓰기는 제외. */
internal fun Route.davRoutes(context: Context, serverRef: RelayServer) {
    options("/dav/{path...}") {
        call.response.header("DAV", "1")
        call.response.header(HttpHeaders.Allow, "OPTIONS, PROPFIND, GET, HEAD")
        DebugLogger.i("Dav", "[FEATURE] WebDAV 읽기 시작")
        call.respondText("ok")
    }

    route("/dav/{path...}") {
        method(HttpMethod("PROPFIND")) {
            handle {
                val name = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val target = StorageGuard.storageFile(name)
                if (target == null || !target.exists()) {
                    DebugLogger.w("Dav", "PROPFIND 경로 차단 name=$name")
                    call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    return@handle
                }
                val depth = call.request.headers["Depth"]?.trim() ?: "1"
                val files = mutableListOf(target)
                if (depth != "0" && target.isDirectory) {
                    target.listFiles()
                        ?.filter { it.name != ".trash" }
                        ?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name })
                        ?.let { files.addAll(it) }
                }
                val xml = buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                    append("<D:multistatus xmlns:D=\"DAV:\">")
                    val rootCanon = StorageGuard.dlRootCanonical.path
                    files.forEach { f ->
                        // canonical 기준 상대경로 — lexical relativeTo의 ".." 탈출 방지
                        val fCanon = runCatching { f.canonicalFile }.getOrNull() ?: return@forEach
                        if (fCanon.path != rootCanon && !fCanon.path.startsWith("$rootCanon/")) return@forEach
                        val rel = fCanon.path.removePrefix(rootCanon).trimStart('/')
                        val href = "/dav/" + rel.split("/").filter { it.isNotEmpty() }
                            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        append("<D:response><D:href>$href</D:href><D:propstat><D:prop>")
                        append("<D:displayname>${xmlEsc(f.name)}</D:displayname>")
                        if (f.isDirectory) append("<D:resourcetype><D:collection/></D:resourcetype>")
                        else append("<D:resourcetype/><D:getcontentlength>${f.length()}</D:getcontentlength>")
                        append("<D:getcontenttype>${StreamContentType.forName(f.name)}</D:getcontenttype>")
                        append("<D:getlastmodified>${httpDate(f.lastModified())}</D:getlastmodified>")
                        append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                    }
                    append("</D:multistatus>")
                }
                call.response.header("DAV", "1")
                call.respondText(xml, ContentType.Text.Xml, HttpStatusCode.MultiStatus)
            }
        }
    }

    get("/dav/{path...}") {
        val name = call.parameters.getAll("path")?.joinToString("/") ?: ""
        val file = StorageGuard.storageFile(name)
        if (file == null || !file.exists() || !file.isFile) {
            DebugLogger.w("Dav", "GET 경로 차단 name=$name")
            call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
        } else {
            DebugLogger.d("Dav", "WebDAV 전송 name=$name")
            call.serveFile(file, "dav", inline = true, contentType = StreamContentType.forName(file.name))
        }
    }
}

private fun xmlEsc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun httpDate(ms: Long): String {
    val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("GMT")
    return fmt.format(java.util.Date(ms))
}
