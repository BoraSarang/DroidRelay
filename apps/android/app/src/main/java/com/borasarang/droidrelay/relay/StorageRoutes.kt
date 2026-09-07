package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import org.json.JSONObject

/** 보관함 경로 가드 — dlRoot 하위로 한정 (T-936 D1, routing 지역함수에서 승격) */
internal object StorageGuard {
    val dlRoot = java.io.File("/sdcard/Download/DroidRelay")
    val dlRootCanonical: java.io.File get() = dlRoot.canonicalFile
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
}

/** 보관함·외장스토리지·업로드·다운로드 라우트 (T-936 D1 — RelayServer에서 분리) */
internal fun Route.storageRoutes(context: Context, serverRef: RelayServer) {
    // ── 외장 스토리지 감지 (Phase 3 확장) ──
    get("/api/storage/external") {
        val storages = StorageDetector.detectExternal(context)
        val arr = org.json.JSONArray()
        storages.forEach { info ->
            arr.put(JSONObject().apply {
                put("path", info.path)
                put("label", info.label)
                put("totalBytes", info.totalBytes)
                put("freeBytes", info.freeBytes)
                put("isExternal", info.isExternal)
            })
        }
        call.respondText(
            JSONObject().apply {
                put("storages", arr)
                put("bestPath", StorageDetector.bestExternalPath(context) ?: "")
            }.toString(),
            ContentType.Application.Json
        )
    }

    // ── 보관함 API ──
    get("/api/storage") {
        val subPath = call.request.queryParameters["path"] ?: ""
        val dir = StorageGuard.storageFile(subPath)
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
        val name = StorageGuard.safeLeafName(json.optString("name", ""))
        if (name == null) {
            call.respondErr("이름 없음")
            return@post
        }
        val dir = StorageGuard.storageFile(subPath)
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
        val fromFile = StorageGuard.storageFile(json.optString("from", ""))
        val toName = StorageGuard.safeLeafName(json.optString("to", ""))
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
        val target = StorageGuard.storageFile(path)
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
        if (target.canonicalFile.path.startsWith(StorageGuard.trashDir.canonicalFile.path)) {
            DebugLogger.i("Http", "영구삭제 ${target.name}")
            target.deleteRecursively()
            call.respondOk()
            return@post
        }
        // 휴지통 이동 (T-703) — 이름 충돌 시 접미사
        val trashDir = StorageGuard.trashDir
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
            DebugLogger.e("Stor", "휴지통 이동 실패 ${target.name} (E-AND-STOR-1003)")
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
                        put("path", runCatching { d.relativeTo(StorageGuard.dlRoot).path }.getOrDefault(d.name))
                        put("children", walk(d, depth + 1))
                    })
                }
            return arr
        }
        call.respondText(walk(StorageGuard.dlRoot, 0).toString(), ContentType.Application.Json)
    }

    // ── 휴지통 API (T-703) ──
    get("/api/storage/trash") {
        val arr = org.json.JSONArray()
        StorageGuard.trashDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
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
        val name = StorageGuard.safeLeafName(json.optString("name", ""))
        if (name == null) { call.respondErr("이름 없음"); return@post }
        val src = java.io.File(StorageGuard.trashDir, name)
        if (!src.exists()) { call.respondErr("없음"); return@post }
        var dest = java.io.File(StorageGuard.dlRoot, src.name)
        var i = 2
        while (dest.exists()) { dest = java.io.File(StorageGuard.dlRoot, "${src.name}-$i"); i++ }
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
        val name = StorageGuard.safeLeafName(body.optString("name", ""))
        when {
            name != null -> {
                val t = java.io.File(StorageGuard.trashDir, name)
                if (!t.exists()) { call.respondErr("없음"); return@post }
                t.deleteRecursively()
                DebugLogger.i("Http", "휴지통 영구삭제 $name")
            }
            else -> {
                val count = StorageGuard.trashDir.listFiles()?.size ?: 0
                StorageGuard.trashDir.listFiles()?.forEach { it.deleteRecursively() }
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
        val src = StorageGuard.storageFile(fromName)
        val dstDir = StorageGuard.storageFile(toDir)
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
        DebugLogger.i("Http", "이동 ${src.name} → ${dstDir.path.removePrefix(StorageGuard.dlRootCanonical.path)}")
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
                    DebugLogger.e("Stor", "이동 검증 실패 → 복사본 폐기, 원본 보존 ${src.name} (E-AND-STOR-1002)")
                    dst.deleteRecursively()
                    false
                }
            } catch (e: Exception) {
                    DebugLogger.e("Stor", "이동 실패 ${src.name}: ${e.message} (E-AND-STOR-1002)")
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
            val fileName = runCatching { java.net.URLDecoder.decode(call.request.headers["X-File-Name"] ?: "upload", "UTF-8") }.getOrDefault("upload")
            val subPath = runCatching { java.net.URLDecoder.decode(call.request.headers["X-File-Path"] ?: "", "UTF-8") }.getOrDefault("")
            val dir = StorageGuard.storageFile(subPath)
            if (dir == null) { call.respondErr("잘못된 경로"); return@post }
            dir.mkdirs()
            val safeName = StorageGuard.safeLeafName(fileName) ?: "upload"
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
            DebugLogger.i("Http", "업로드 완료 ${file.name} → ${file.path.removePrefix(StorageGuard.dlRootCanonical.path)} (${file.length()}B)")
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
                fileName = StorageGuard.safeLeafName(json.optString("name", "")) ?: ""
                val data = json.optString("data", "")
                if (data.isNotEmpty()) {
                    fileBytes = java.util.Base64.getDecoder().decode(data)
                }
            }

            val safeName = StorageGuard.safeLeafName(fileName)
            if (safeName == null || fileBytes == null || fileBytes.isEmpty()) {
                call.respondErr("파일 없음")
                return@post
            }
            val dir = StorageGuard.storageFile(subPath)
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

    get("/dl-folder/{name...}") {
        val name = call.parameters.getAll("name")?.joinToString("/") ?: ""
        val dir = StorageGuard.storageFile(name)
        if (dir == null || !dir.exists() || !dir.isDirectory) {
            DebugLogger.w("Http", "폴더 다운로드 경로 차단 name=$name")
            call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        val zipName = (dir.name.ifBlank { "download" }) + ".zip"
        DebugLogger.i("Http", "폴더 다운로드 요청 name=$name")
        call.response.header(HttpHeaders.ContentDisposition, DispositionHeader.make(zipName))
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
        // 실시간 ZIP 스트리밍 — 임시 파일 없이 대용량 대응 (T-938)
        // T-939: 영상 등 기압축 파일 재압축 방지 — DEFLATED+level 0 패스스루(사전 스캔 불필요) + 256KB 버퍼
        val startedAt = System.currentTimeMillis()
        var fileCount = 0
        var totalBytes = 0L
        call.respondOutputStream(contentType = ContentType.Application.Zip) {
            val root = dir.canonicalFile
            java.io.BufferedOutputStream(this, 256 * 1024).use { buffered ->
                java.util.zip.ZipOutputStream(buffered).use { zip ->
                    zip.setLevel(0)
                    fun addDir(d: java.io.File, prefix: String) {
                        d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                            // canonical 재검증 — 심볼릭링크 탈출 차단
                            val c = runCatching { f.canonicalFile }.getOrNull() ?: return@forEach
                            if (!c.path.startsWith(root.path)) return@forEach
                            val entryName = prefix + f.name
                            if (f.isDirectory) {
                                zip.putNextEntry(java.util.zip.ZipEntry("$entryName/"))
                                zip.closeEntry()
                                addDir(f, "$entryName/")
                            } else if (f.isFile) {
                                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                                f.inputStream().use { input ->
                                    val buf = ByteArray(256 * 1024)
                                    var n: Int
                                    while (input.read(buf).also { n = it } != -1) zip.write(buf, 0, n)
                                }
                                zip.closeEntry()
                                fileCount++
                                totalBytes += f.length()
                            }
                        }
                    }
                    addDir(root, "")
                }
            }
        }
        val elapsedSec = (System.currentTimeMillis() - startedAt).coerceAtLeast(1) / 1000.0
        val totalMb = totalBytes / (1024.0 * 1024.0)
        DebugLogger.i("Http", "폴더 다운로드 완료 name=$name 파일 ${fileCount}개 원본 ${"%.1f".format(totalMb)}MB 소요 ${"%.1f".format(elapsedSec)}초 (${"%.1f".format(totalMb / elapsedSec)}MB/s)")
    }

    get("/dl-file/{name...}") {
        val name = call.parameters.getAll("name")?.joinToString("/") ?: ""
        val file = StorageGuard.storageFile(name)
        if (file == null || !file.exists() || !file.isFile) {
            DebugLogger.w("Http", "다운로드 경로 차단 name=$name")
            call.respondText("404 없음", ContentType.Text.Plain, HttpStatusCode.NotFound)
        } else {
            DebugLogger.i("Http", "파일 다운로드 요청 name=$name")
            call.response.header(HttpHeaders.ContentDisposition, DispositionHeader.make(file.name))
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(HttpHeaders.CacheControl, "no-store, must-revalidate")
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
}
