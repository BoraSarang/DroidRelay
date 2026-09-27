package com.borasarang.droidrelay.relay

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
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

// ── JSON-RPC 응답 헬퍼 (v0.42) ──
// 이전에는 라우트 안에서 JSONObject 를 조립해 respondText 로 바로 냈고, 오류도
// 200 + `-32603` 으로 돌려줬다. 스펙은 상태 코드와 오류 코드를 구분하도록 요구한다.

private suspend fun ApplicationCall.respondRpcResult(id: Any?, result: JSONObject) {
    val response = JSONObject().apply {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("result", result)
    }
    respondText(response.toString(), ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondRpcError(
    id: Any?,
    code: Int,
    label: String,
    message: String,
    status: HttpStatusCode,
) {
    val response = JSONObject().apply {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
            put("data", JSONObject().apply { put("label", label) })
        })
    }
    respondText(response.toString(), ContentType.Application.Json, status)
}

/**
 * MCP (Model Context Protocol) 서버 내장.
 * JSON-RPC 2.0 · Streamable HTTP 전송 계층, 도구별 권한 설정.
 *
 * 핵심 도구:
 * - file_list: 보관함 파일 목록
 * - file_read: 파일 내용 읽기 (텍스트) — 프라이버시 모드에서 제외
 * - download_add / download_add_batch: 다운로드 추가
 * - download_list / download_control: 목록·제어
 * - storage_list / storage_mkdir / storage_move: 보관함 조작
 * - torrent_add: magnet 등록
 * - video_analyze / video_create: 스트림·영상
 * - stats_summary: 트래픽 요약
 */
object McpServer {

    private const val TAG = "MCP"

    /**
     * 지원하는 프로토콜 버전 (Streamable HTTP).
     *
     * `2026-07-28` 은 **의도적으로 지원하지 않는다.** 그 개정은 SEP-2243 의
     * `Mcp-Method` / `Mcp-Name` 헤더와 `subscriptions/listen` 장기 스트림을 요구하는데,
     * 구현하지 않은 버전을 advertise 하면 그것 자체가 규격 위반이 된다.
     * 스펙이 허용하는 대로 지원 목록을 명시해 클라이언트가 폴백하도록 한다.
     */
    private val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-11-25")
    private const val LATEST_VERSION = "2025-11-25"
    private const val VERSION_HEADER = "MCP-Protocol-Version"
    private const val META_VERSION_KEY = "io.modelcontextprotocol/protocolVersion"

    /** JSON-RPC 오류 코드 */
    private const val ERR_METHOD_NOT_FOUND = -32601
    private const val ERR_HEADER_MISMATCH = -32000
    private const val ERR_UNSUPPORTED_VERSION = -32001
    private const val ERR_INTERNAL = -32603

    /** 등록된 도구 목록 (v0.42: 5 → 12개) */
    private val tools = listOf(
        McpTool(
            name = "file_list",
            description = "보관함 파일 목록 조회",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"상대 경로"}}}""",
            requiredPermission = "file_list",
        ),
        McpTool(
            name = "file_read",
            description = "텍스트 파일 내용 읽기 (1MB 이하). 프라이버시 모드에서는 비활성화됩니다",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"파일 경로"}}},"required":["path"]}""",
            requiredPermission = "file_read",
            requiresPrivacyExemption = true,
        ),
        McpTool(
            name = "download_add",
            description = "다운로드 추가 (URL 1개)",
            inputSchema = """{"type":"object","properties":{"url":{"type":"string","description":"다운로드 URL"}}},"required":["url"]}""",
            requiredPermission = "download_add",
        ),
        McpTool(
            name = "download_add_batch",
            description = "다운로드 여러 개 추가. urls 에 개행/공백을 포함해 한 번에 전달할 수 있다",
            inputSchema = """{"type":"object","properties":{"urls":{"type":"string","description":"URL 목록 (개행/공백 구분 허용)"},"url":{"type":"string","description":"URL 1개 (urls 가 없을 때)"}},"required":["urls"]}""",
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
        McpTool(
            name = "storage_list",
            description = "보관함 디렉터리 내용 조회 (폴더·파일·크기·수정시각)",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"상대 경로"}}}""",
            requiredPermission = "file_list",
        ),
        McpTool(
            name = "storage_mkdir",
            description = "보관함에 폴더 생성",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"부모 상대 경로 (빈 문자열=루트)"},"name":{"type":"string","description":"생성할 폴더명"}}},"required":["name"]}""",
            requiredPermission = "storage_write",
        ),
        McpTool(
            name = "storage_move",
            description = "보관함 항목 이동/이름변경. 목적지에 같은 이름이 있으면 conflict 로 보고한다 (덮어쓰려면 overwrite=true)",
            inputSchema = """{"type":"object","properties":{"from":{"type":"string","description":"출발 상대 경로"},"to":{"type":"string","description":"목적지 디렉터리 상대 경로 (빈 문자열=루트)"},"overwrite":{"type":"boolean","description":"같은 이름이 있어도 덮어쓸지"}},"required":["from","to"]}""",
            requiredPermission = "storage_write",
        ),
        McpTool(
            name = "torrent_add",
            description = "토렌트 추가 (magnet 링크)",
            inputSchema = """{"type":"object","properties":{"magnet":{"type":"string","description":"magnet: 링크"}},"required":["magnet"]}""",
            requiredPermission = "torrent_add",
        ),
        McpTool(
            name = "video_analyze",
            description = "스트림/영상 URL 분석 (종류·해상도 후보·제목)",
            inputSchema = """{"type":"object","properties":{"url":{"type":"string","description":"스트림 페이지 또는 m3u8/mpd 직접 주소"}}},"required":["url"]}""",
            requiredPermission = "video_analyze",
        ),
        McpTool(
            name = "stats_summary",
            description = "트래픽 요약 (오늘·이번달·누적 업/다운)",
            inputSchema = """{"type":"object","properties":{}}""",
            requiredPermission = "stats_summary",
        ),
    )

    /** 등록된 도구 이름 (계약 테스트용) */
    fun installedToolNames(): List<String> = tools.map { it.name }

    /** 도구명 → inputSchema (계약 테스트용) */
    fun installedToolSchemas(): List<Pair<String, String>> = tools.map { it.name to it.inputSchema }

    /** 지원하는 프로토콜 버전 (계약 테스트용) */
    fun supportedVersions(): List<String> = SUPPORTED_VERSIONS

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
     * MCP 요청 처리 (JSON-RPC 2.0, Streamable HTTP).
     *
     * v0.42 (T-1075/T-1076) 규격 정합:
     * - 알림(`id` 없음 또는 `notifications/`) 은 **202 Accepted + body 없음**
     * - 미지원 메서드는 **404 + `-32601`** (이전엔 200 + `-32603`)
     * - `MCP-Protocol-Version` 헤더와 body `_meta` 불일치 → **400 `HeaderMismatch`**
     * - 미지원 버전 → **400 `UnsupportedProtocolVersionError`** (지원 목록 명시)
     * - 도구 실행 실패는 프로토콜 오류가 아니라 `isError:true` 결과로 반환
     */
    private suspend fun handleMcpRequest(context: Context, call: ApplicationCall, serverRef: RelayServer) {
        val t0 = System.currentTimeMillis()
        val body = runCatching { call.receiveText() }.getOrElse {
            call.respondText("요청 본문을 읽을 수 없습니다", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        val json = runCatching { JSONObject(body) }.getOrElse {
            call.respondText("JSON 파싱 실패", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        val method = json.optString("method", "")
        val hasId = json.has("id") && !json.isNull("id")
        val id = json.opt("id")
        val params = json.optJSONObject("params") ?: JSONObject()
        val isNotification = !hasId || method.startsWith("notifications/")
        DebugLogger.d(TAG, "요청 진입 method=$method id=$id 알림=$isNotification params=${params.toString().take(160)}")

        // ── 알림: 202 Accepted, body 없음 (스펙 MUST) ──
        if (isNotification) {
            call.respond(HttpStatusCode.Accepted)
            DebugLogger.d(TAG, "알림 수신(무응답) method=$method ${System.currentTimeMillis() - t0}ms")
            return
        }

        // ── 버전 협상 ──
        val headerVersion = call.request.headers[VERSION_HEADER]?.trim()?.takeIf { it.isNotEmpty() }
        val metaVersion = params.optJSONObject("_meta")?.optString(META_VERSION_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        if (headerVersion != null && metaVersion != null && headerVersion != metaVersion) {
            DebugLogger.w(TAG, "버전 불일치 헤더=$headerVersion meta=$metaVersion")
            call.respondRpcError(
                id, ERR_HEADER_MISMATCH, "HeaderMismatch",
                "버전 헤더와 body _meta 가 다릅니다 (header=$headerVersion meta=$metaVersion)",
                HttpStatusCode.BadRequest,
            )
            return
        }
        // 선언된 버전: HTTP 헤더 → body `_meta` → (initialize 한정) params.protocolVersion
        // 순서로 본다. `initialize` 는 클라이언트가 원하는 버전을 `params.protocolVersion` 으로
        // 보내는 **유일한 자리**여서 마지막 폴백으로 넣어야 협상이 의미를 갖는다.
        val requestedVersion = params.optString("protocolVersion", "").trim().takeIf { it.isNotEmpty() }
        val declared = headerVersion ?: metaVersion ?: requestedVersion
        // initialize 는 클라이언트가 원하는 버전을 negotiate 하는 자리이므로 미지원 값도 통과시킨다.
        if (declared != null && declared !in SUPPORTED_VERSIONS && method != "initialize") {
            DebugLogger.w(TAG, "미지원 버전 거부 요청=$declared 지원=$SUPPORTED_VERSIONS")
            call.respondRpcError(
                id, ERR_UNSUPPORTED_VERSION, "UnsupportedProtocolVersionError",
                "지원하지 않는 프로토콜 버전입니다. 지원 버전: ${SUPPORTED_VERSIONS.joinToString()}",
                HttpStatusCode.BadRequest,
            )
            return
        }

        val result = when (method) {
            "initialize" -> handleInitialize(context, params, declared)
            "tools/list" -> handleToolsList(serverRef)
            "tools/call" -> handleToolsCall(context, params, serverRef)
            "ping" -> JSONObject()
            else -> {
                DebugLogger.w(TAG, "미지원 메서드 method=$method")
                call.respondRpcError(
                    id, ERR_METHOD_NOT_FOUND, "Method not found",
                    "지원하지 않는 메서드입니다: $method",
                    HttpStatusCode.NotFound,
                )
                return
            }
        }
        call.respondRpcResult(id, result)
        DebugLogger.d(TAG, "요청 완료 method=$method ${System.currentTimeMillis() - t0}ms")
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

    private fun handleInitialize(context: Context, params: JSONObject, declared: String?): JSONObject {
        // 클라이언트가 원하는 버전을 지원하면 그대로, 아니면 최신 지원 버전을 준다.
        val negotiated = if (declared != null && declared in SUPPORTED_VERSIONS) declared else LATEST_VERSION
        val requested = params.optString("protocolVersion", "")
        DebugLogger.i(TAG, "initialize 협상 client=$requested → $negotiated (지원=$SUPPORTED_VERSIONS)")
        return JSONObject().apply {
            put("protocolVersion", negotiated)
            put("capabilities", JSONObject().apply {
                // listChanged 를 false 로 두면 클라이언트가 변경 통지를 요구하지 않는다
                put("tools", JSONObject().apply { put("listChanged", false) })
            })
            put("serverInfo", JSONObject().apply {
                put("name", "DroidRelay")
                put("version", appVersion(context))
                put("protocolVersions", JSONArray(SUPPORTED_VERSIONS))
            })
            put("instructions", "DroidRelay 폰의 다운로드·토렌트·보관함을 제어한다. download_add 로 URL 을 등록하고 download_list 로 진행을 확인한다.")
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun handleToolsList(serverRef: RelayServer): JSONObject {
        val disabled = serverRef.settings.mcpToolsDisabled
        val privacy = serverRef.settings.mcpPrivacyMode
        val toolsArray = JSONArray()
        // 비활성화된 도구는 목록에서 숨긴다 — 클라이언트가 존재하지만 쓸 수 없는 상태를 만들지 않는다
        tools.forEach { tool ->
            if (tool.name in disabled) return@forEach
            if (privacy && tool.requiresPrivacyExemption) return@forEach
            toolsArray.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", JSONObject(tool.inputSchema))
            })
        }
        return JSONObject().apply { put("tools", toolsArray) }
    }

    /**
     * 도구 호출. **도구 실행 실패는 프로토콜 오류가 아니라 `isError:true` 결과**로 돌려준다
     * (스펙: CallToolResult 의 isError). 클라이언트가 "호출 자체가 실패" 와
     * "도구가 실패" 를 구분할 수 있어야 한다.
     */
    private suspend fun handleToolsCall(context: Context, params: JSONObject, serverRef: RelayServer): JSONObject {
        val name = params.optString("name", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val outcome = runCatching { executeTool(context, name, arguments, serverRef) }
        return JSONObject().apply {
            outcome.fold(
                onSuccess = { ok ->
                    put("content", JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", ok.toString()) })
                    })
                    put("isError", false)
                },
                onFailure = { e ->
                    DebugLogger.w(TAG, "도구 실패 name=$name: ${e.message}")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", e.message ?: "도구 실행 실패")
                        })
                    })
                    put("isError", true)
                },
            )
        }
    }

    private suspend fun executeTool(context: Context, name: String, args: JSONObject, serverRef: RelayServer): Any {
        // 권한 체크 (Phase 2.1 확장)
        val settings = serverRef.settings
        if (name in settings.mcpToolsDisabled) {
            DebugLogger.w(TAG, "도구 비활성화: $name")
            error("도구 비활성화됨: $name")
        }
        // 프라이버시 모드 — requiresPrivacyExemption 은 목록에서 숨기므로,
        // 여기서는 "숨겨졌는데 직접 부르는" 경로를 막는 2차 방어선 역할을 한다.
        if (settings.mcpPrivacyMode && tools.firstOrNull { it.name == name }?.requiresPrivacyExemption == true) {
            DebugLogger.w(TAG, "프라이버시 모드 차단: $name")
            error("프라이버시 모드에서 허용되지 않는 도구: $name")
        }

        DebugLogger.d(TAG, "도구 실행 name=$name args=${args.toString().take(150)}")
        return when (name) {
            "file_list" -> fileList(args)
            "file_read" -> fileRead(args)
            "download_add" -> downloadAdd(context, args, serverRef)
            "download_add_batch" -> downloadAddBatch(context, args, serverRef)
            "download_list" -> downloadList()
            "download_control" -> downloadControl(args)
            "storage_list" -> fileList(args)
            "storage_mkdir" -> storageMkdir(args)
            "storage_move" -> storageMove(args)
            "torrent_add" -> torrentAdd(context, args)
            "video_analyze" -> videoAnalyze(args)
            "stats_summary" -> statsSummary()
            else -> error("도구 없음: $name")
        }
    }

    // ── 추가 도구 (v0.42, T-1077) ──────────────────────────

    /** 여러 URL 일괄 등록 — 웹 UI 의 `add()` 와 동일한 구분 규칙(개행/공백/쉼표) */
    private suspend fun downloadAddBatch(context: Context, args: JSONObject, serverRef: RelayServer): JSONObject {
        val raw = args.optString("urls", "").ifBlank { args.optString("url", "") }
        if (raw.isBlank()) error("urls 또는 url 필요")
        val urls = raw.split(Regex("[\\s,]+")).filter { it.matches(Regex("^https?://", RegexOption.IGNORE_CASE)) }
        if (urls.isEmpty()) error("유효한 http(s) URL 이 없습니다")
        val added = JSONArray()
        val failed = JSONArray()
        urls.forEach { u ->
            runCatching { downloadAdd(context, JSONObject().put("url", u), serverRef) }
                .fold(onSuccess = { added.put(it) }, onFailure = { failed.put(u.take(120)) })
        }
        DebugLogger.i(TAG, "download_add_batch 요청=${urls.size} 성공=${added.length()} 실패=${failed.length()}")
        return JSONObject().apply {
            put("requested", urls.size)
            put("added", added)
            put("failed", failed)
        }
    }

    private fun storageMkdir(args: JSONObject): JSONObject {
        val parent = args.optString("path", "")
        val name = StorageGuard.safeLeafName(args.optString("name", "")) ?: error("이름 없음")
        val dir = StorageGuard.storageFile(parent) ?: error("잘못된 경로")
        if (!dir.exists() || !dir.isDirectory) error("디렉터리 없음: $parent")
        val target = File(dir, name)
        if (target.exists()) error("이미 존재: $name")
        if (!target.mkdirs()) error("폴더 생성 실패: $name")
        DebugLogger.i(TAG, "storage_mkdir ${dir.path}/$name")
        return JSONObject().apply {
            put("ok", true)
            put("name", name)
            put("path", if (parent.isBlank()) name else "$parent/$name")
        }
    }

    /**
     * 보관함 이동 — 라우트(`/api/storage/move`) 와 **동일한 판정**을 쓴다.
     * 이름 충돌 시 예외로 알린다(클라이언트가 isError 로 받음). `overwrite=true` 면 진행.
     * T-1072 데이터 손실 버그의 가드와 같은 코드 경로라 MCP 경로가 안전하다.
     */
    private fun storageMove(args: JSONObject): JSONObject {
        val from = args.optString("from", "")
        val to = args.optString("to", "")
        if (from.isBlank()) error("from 필요")
        val src = StorageGuard.storageChild(from) ?: error("잘못된 경로")
        val dstDir = StorageGuard.storageFile(to) ?: error("잘못된 경로")
        if (!src.exists()) error("원본 없음: $from")
        if (src == dstDir) error("이동할 수 없습니다 (동일 위치)")
        if (src.isDirectory && dstDir.path.startsWith(src.path + File.separator)) {
            error("폴더를 자기 하위로 이동할 수 없습니다")
        }
        if (!dstDir.exists()) dstDir.mkdirs()
        val dst = File(dstDir, src.name)

        val decision = StorageMove.decide(
            srcName = src.name,
            dstExists = dst.exists(),
            dstIsDir = dst.isDirectory,
            dstSize = if (dst.isFile) dst.length() else 0L,
            dstModified = dst.lastModified(),
            overwrite = args.optBoolean("overwrite", false),
        )
        if (decision is StorageMove.Decision.Conflict) {
            DebugLogger.w(TAG, "storage_move 충돌 ${src.name} → ${dstDir.path}")
            throw IllegalStateException(
                "「${decision.name}」 이(가) 이미 있습니다 (${decision.size} B). " +
                    "덮어쓰려면 overwrite=true 를 다시 지정하세요.",
            )
        }
        if (!src.renameTo(dst)) {
            if (src.copyTo(dst, overwrite = true).length() != dst.length()) {
                dst.deleteRecursively()
                error("이동 검증 실패 (원본 보존)")
            }
            src.deleteRecursively()
        }
        DebugLogger.i(TAG, "storage_move ${src.name} → ${dstDir.path}")
        return JSONObject().apply {
            put("ok", true)
            put("name", src.name)
            put("to", if (to.isBlank()) "" else to)
        }
    }

    private fun torrentAdd(context: Context, args: JSONObject): JSONObject {
        val magnet = args.optString("magnet", "").trim()
        if (!magnet.startsWith("magnet:")) error("magnet 링크가 필요합니다")
        val eng = RelayApp.getTorrent(context)
        if (eng.isDuplicateMagnet(magnet)) error("이미 다운로드 중인 토렌트입니다")
        val job = eng.addMagnet(magnet)
        DebugLogger.i(TAG, "torrent_add magnet 추가 id=${job.id}")
        return JSONObject().apply {
            put("ok", true)
            put("id", job.id)
            put("name", job.name)
        }
    }

    /** 스트림·영상 분석 — 라우트(`/api/video/analyze`) 와 같은 엔진을 직접 호출한다 */
    private suspend fun videoAnalyze(args: JSONObject): JSONObject {
        val url = args.optString("url", "").trim()
        if (url.isBlank()) error("url 필요")
        if (!url.startsWith("http")) error("http(s) URL 만 지원합니다")
        val result = VideoApi.analyze(url)
        if (result.has("error")) error("분석 실패: ${result.optString("error")}")
        return result
    }

    private fun statsSummary(): JSONObject {
        val s = TrafficLedger.summary()
        return JSONObject().apply {
            put("today", mcpBucket(s.today))
            put("month", mcpBucket(s.month))
            put("total", mcpBucket(s.total))
        }
    }

    private fun mcpBucket(b: TrafficBucket?): JSONObject {
        b ?: return JSONObject()
        return JSONObject().apply {
            put("down", b.downTotal())
            put("up", b.upTotal())
            put("downHttp", b.downHttp)
            put("downVideo", b.downVideo)
            put("downTorrent", b.downTorrent)
            put("upServe", b.upServe)
            put("upTorrent", b.upTorrent)
        }
    }

    private fun fileList(args: JSONObject): JSONArray {
        val subPath = args.optString("path", "")
        // 경로 탈출 차단 — StorageGuard 미사용 시 "../../.." 로 루트 전체 목록이 노출된다
        val dir = StorageGuard.storageFile(subPath)
        if (dir == null) {
            DebugLogger.w(TAG, "fileList: 경로 탈출 차단 path=$subPath")
            error("경로 탈출 차단")
        }

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

        val dlRoot = StorageGuard.dlRoot
        val file = File(dlRoot, path)
        val canonical = file.canonicalFile
        // 경로 구분자 없이 startsWith 만 쓰면 "DroidRelay.bak" 같은 접두 동명이 통과한다 (StorageGuard 와 불일치)
        val root = StorageGuard.dlRootCanonical.path
        if (canonical.path != root && !canonical.path.startsWith(root + File.separator)) error("경로 탈출 차단")

        if (!file.exists()) error("파일 없음: $path")
        if (!file.isFile) error("파일이 아님: $path")
        if (file.length() > 1_048_576) error("파일이 너무 큼 (>1MB)")

        return file.readText()
    }

    private suspend fun downloadAdd(context: Context, args: JSONObject, serverRef: RelayServer): JSONObject {
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
                    // runBlocking 으로 감싸면 DebridClient 의 suspend/IO 설계가 무의미해지고
                    // Netty 이벤트루프가 40초(접속 15 + 읽기 30) 동안 블로킹된다.
                    val link = client.unrestrict(url, provider, s.debridApiKey)
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
    /**
     * `mcpPrivacyMode` 에서 제외되는 도구인가.
     * true 인 도구는 목록에서도 숨기고, 호출해도 거부한다 —
     * LLM 에 파일 내용을 그대로 흘리는 경로라 기본으로 닫아 두는 편이 낫다.
     */
    val requiresPrivacyExemption: Boolean = false,
)
