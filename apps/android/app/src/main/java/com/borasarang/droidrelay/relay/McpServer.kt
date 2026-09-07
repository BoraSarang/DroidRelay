package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MCP (Model Context Protocol) 서버 내장.
 * JSON-RPC 2.0 프로토콜, 도구별 권한 설정.
 *
 * 핵심 도구:
 * - file_list: 보관함 파일 목록
 * - file_read: 파일 내용 읽기 (텍스트)
 * - download_add: 다운로드 추가
 * - download_list: 다운로드 목록
 * - download_control: 다운로드 일시정지/재개/취소
 */
object McpServer {

    private const val TAG = "MCP"

    /** 등록된 도구 목록 */
    private val tools = listOf(
        McpTool(
            name = "file_list",
            description = "보관함 파일 목록 조회",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"상대 경로"}}}""",
            requiredPermission = "file_list",
        ),
        McpTool(
            name = "file_read",
            description = "텍스트 파일 내용 읽기",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"파일 경로"}},"required":["path"]}""",
            requiredPermission = "file_read",
        ),
        McpTool(
            name = "download_add",
            description = "다운로드 추가",
            inputSchema = """{"type":"object","properties":{"url":{"type":"string","description":"다운로드 URL"}},"required":["url"]}""",
            requiredPermission = "download_add",
        ),
        McpTool(
            name = "download_list",
            description = "다운로드 목록 조회",
            inputSchema = """{"type":"object","properties":{}}""",
            requiredPermission = "download_list",
        ),
        McpTool(
            name = "download_control",
            description = "다운로드 제어 (일시정지/재개/취소)",
            inputSchema = """{"type":"object","properties":{"id":{"type":"string"},"action":{"type":"string","enum":["pause","resume","cancel"]}},"required":["id","action"]}""",
            requiredPermission = "download_control",
        ),
    )

    /**
     * Ktor 라우팅에 MCP 엔드포인트 추가.
     */
    fun installRoutes(context: Context, application: io.ktor.server.application.Application, serverRef: RelayServer) {
        DebugLogger.i(TAG, "MCP 라우트 설치 (/mcp, /mcp/call)")
        application.routing {
            post("/mcp") {
                handleMcpRequest(context, call, serverRef)
            }
            post("/mcp/call") {
                handleMcpToolCall(context, call, serverRef)
            }
        }
    }

    /**
     * MCP 요청 처리 (JSON-RPC 2.0).
     */
    private suspend fun handleMcpRequest(context: Context, call: ApplicationCall, serverRef: RelayServer) {
        val t0 = System.currentTimeMillis()
        try {
            val body = call.receiveText()
            val json = JSONObject(body)
            val method = json.optString("method", "")
            val id = json.opt("id")
            val params = json.optJSONObject("params") ?: JSONObject()
            DebugLogger.d(TAG, "요청 진입 method=$method id=$id params=${params.toString().take(200)}")

            val result = when (method) {
                "initialize" -> handleInitialize(params)
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(context, params, serverRef)
                else -> error("지원하지 않는 메서드: $method")
            }

            val response = JSONObject().apply {
                put("jsonrpc", "2.0")
                if (id != null) put("id", id)
                put("result", result)
            }
            call.respondText(response.toString(), ContentType.Application.Json)
            DebugLogger.d(TAG, "요청 완료 method=$method ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "MCP 요청 처리 실패 ${System.currentTimeMillis() - t0}ms", e)
            val errorResponse = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("error", JSONObject().apply {
                    put("code", -32603)
                    put("message", e.message ?: "내부 오류")
                })
            }
            call.respondText(errorResponse.toString(), ContentType.Application.Json)
        }
    }

    /**
     * MCP 도구 직접 호출 처리.
     */
    private suspend fun handleMcpToolCall(context: Context, call: ApplicationCall, serverRef: RelayServer) {
        val t0 = System.currentTimeMillis()
        try {
            val body = call.receiveText()
            val json = JSONObject(body)
            val toolName = json.optString("name", "")
            val arguments = json.optJSONObject("arguments") ?: JSONObject()
            DebugLogger.d(TAG, "도구 호출 name=$toolName args=${arguments.toString().take(200)}")

            val tool = tools.find { it.name == toolName }
            if (tool == null) {
                call.respondText(
                    JSONObject().apply { put("error", "도구 없음: $toolName") }.toString(),
                    ContentType.Application.Json
                )
                return
            }

            val result = executeTool(context, toolName, arguments, serverRef)
            DebugLogger.d(TAG, "도구 완료 name=$toolName ${System.currentTimeMillis() - t0}ms")
            call.respondText(
                JSONObject().apply {
                    put("ok", true)
                    put("result", result)
                }.toString(),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "MCP 도구 호출 실패", e)
            call.respondText(
                JSONObject().apply { put("ok", false); put("error", e.message) }.toString(),
                ContentType.Application.Json
            )
        }
    }

    private fun handleInitialize(params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
            })
            put("serverInfo", JSONObject().apply {
                put("name", "DroidRelay")
                put("version", "0.9.0")
            })
        }
    }

    private fun handleToolsList(): JSONObject {
        val toolsArray = JSONArray()
        tools.forEach { tool ->
            toolsArray.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", JSONObject(tool.inputSchema))
            })
        }
        return JSONObject().apply {
            put("tools", toolsArray)
        }
    }

    private suspend fun handleToolsCall(context: Context, params: JSONObject, serverRef: RelayServer): JSONObject {
        val name = params.optString("name", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val result = executeTool(context, name, arguments, serverRef)
        return JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", result.toString())
                })
            })
        }
    }

    private fun executeTool(context: Context, name: String, args: JSONObject, serverRef: RelayServer): Any {
        // 권한 체크 (Phase 2.1 확장)
        val settings = serverRef.settings
        if (name in settings.mcpToolsDisabled) {
            DebugLogger.w(TAG, "도구 비활성화: $name")
            error("도구 비활성화됨: $name")
        }
        if (settings.mcpPrivacyMode && name in listOf("file_read")) {
            DebugLogger.w(TAG, "프라이버시 모드 차단: $name")
            error("프라이버시 모드에서 허용되지 않는 도구: $name")
        }

        DebugLogger.d(TAG, "도구 실행 name=$name args=${args.toString().take(150)}")
        return when (name) {
            "file_list" -> fileList(args)
            "file_read" -> fileRead(args)
            "download_add" -> downloadAdd(context, args, serverRef)
            "download_list" -> downloadList()
            "download_control" -> downloadControl(args)
            else -> error("도구 없음: $name")
        }
    }

    private fun fileList(args: JSONObject): JSONArray {
        val subPath = args.optString("path", "")
        val dlRoot = File("/sdcard/Download/DroidRelay")
        val dir = if (subPath.isNotBlank()) File(dlRoot, subPath) else dlRoot

        if (!dir.exists() || !dir.isDirectory) {
            DebugLogger.d(TAG, "fileList: 디렉토리 없음 path=$subPath")
            return JSONArray()
        }

        val files = dir.listFiles()
        DebugLogger.d(TAG, "fileList: path=$subPath 파일=${files?.size ?: 0}개")
        val arr = JSONArray()
        files?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name }
        )?.forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("type", if (f.isDirectory) "dir" else "file")
                put("size", if (f.isFile) f.length() else 0)
                put("modified", f.lastModified())
            })
        }
        return arr
    }

    private fun fileRead(args: JSONObject): String {
        val path = args.optString("path", "")
        if (path.isBlank()) error("path 필요")

        val dlRoot = File("/sdcard/Download/DroidRelay")
        val file = File(dlRoot, path)
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(dlRoot.canonicalPath)) error("경로 탈출 차단")

        if (!file.exists()) error("파일 없음: $path")
        if (!file.isFile) error("파일이 아님: $path")
        if (file.length() > 1_048_576) error("파일이 너무 큼 (>1MB)")

        return file.readText()
    }

    private fun downloadAdd(context: Context, args: JSONObject, serverRef: RelayServer): JSONObject {
        val url = args.optString("url", "")
        if (url.isBlank()) error("url 필요")

        val s = serverRef.settings
        val debridActive = s.debridEnabled && s.debridApiKey.isNotBlank()
        DebugLogger.d(TAG, "downloadAdd: url=${url.take(80)} debrid=$debridActive")
        val finalUrl = if (debridActive) {
            try {
                val provider = runCatching { DebridProvider.valueOf(s.debridProvider) }.getOrNull()
                if (provider != null) {
                    val client = DebridClient(context)
                    val link = kotlinx.coroutines.runBlocking {
                        client.unrestrict(url, provider, s.debridApiKey)
                    }
                    link.directUrl
                } else url
            } catch (_: Exception) { url }
        } else url

        val job = JobsRepository.findDuplicateUrl(finalUrl)?.also {
            DebugLogger.i(TAG, "downloadAdd 중복 → 기존 id=${it.id} 반환")
        } ?: RelayApp.engine?.enqueue(finalUrl)
            ?: error("엔진 미초기화")

        DebugLogger.i(TAG, "downloadAdd 완료 id=${job.id} file=${job.filename}")
        return JSONObject().apply {
            put("id", job.id)
            put("filename", job.filename)
        }
    }

    private fun downloadList(): JSONArray {
        val arr = JSONArray()
        JobsRepository.all().forEach { j ->
            arr.put(JSONObject().apply {
                put("id", j.id)
                put("filename", j.filename)
                put("state", j.state.name)
                put("progress", (j.progress * 100).toInt())
                put("speedBps", j.speedBps)
            })
        }
        return arr
    }

    private fun downloadControl(args: JSONObject): JSONObject {
        val id = args.optString("id", "")
        val action = args.optString("action", "")
        if (id.isBlank() || action.isBlank()) error("id와 action 필요")

        val engine = RelayApp.engine ?: error("엔진 미초기화")
        when (action) {
            "pause" -> engine.pause(id)
            "resume" -> engine.resume(id)
            "cancel" -> {
                engine.cancel(id)
                JobsRepository.remove(id)
            }
            else -> error("지원하지 않는 동작: $action")
        }

        return JSONObject().apply {
            put("ok", true)
            put("action", action)
        }
    }
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String,
    val requiredPermission: String,
)
