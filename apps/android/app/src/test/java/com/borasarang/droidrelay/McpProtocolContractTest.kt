package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.McpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP Streamable HTTP 규격 정합 (v0.42, T-1075/T-1076).
 *
 * 배경: 조사에서 **규격 4항목 전부 위반**이 확인됐다(실측).
 * | 규격 요구 | 이전 실측 |
 * |---|---|
 * | `MCP-Protocol-Version` 헤더 필수 | 헤더 없이 200 |
 * | `Origin` 검증 (DNS rebinding) | `evil.example.com` → 200 |
 * | notification 은 202 no body | `-32603` 오류 응답 |
 * | 미지원 메서드는 404 `-32601` | 200 + `-32603` |
 *
 * 특히 `notifications/initialized` 를 거부하면 클라이언트가 `initialize` 직후
 * 반드시 보내는 메시지를 못 받아 **연결 자체가 막힌다.** 도구를 늘리기 전에 고쳐야 했다.
 *
 * 이 테스트는 소스 계약을 고정한다. (행동 검증은 실기기 curl — 세션 로그 참조)
 */
class McpProtocolContractTest {

    private val source: String by lazy {
        val path = System.getProperty("mcpSourcePath")
            ?: "src/main/java/com/borasarang/droidrelay/relay/McpServer.kt"
        java.io.File(path).takeIf { it.exists() }?.readText()
            ?: error("McpServer.kt 를 찾을 수 없습니다: $path (working dir=${System.getProperty("user.dir")})")
    }

    // ── 버전 협상 ──────────────────────────────────────────

    @Test
    fun `지원 버전 목록이 명시되어 있다`() {
        assertTrue("지원 버전 목록이 없다", source.contains("SUPPORTED_VERSIONS"))
        assertTrue("2025-06-18 을 지원해야 한다", source.contains("\"2025-06-18\""))
        assertTrue("2025-11-25 를 지원해야 한다", source.contains("\"2025-11-25\""))
    }

    /** 구현하지 않은 2026-07-28 을 advertise 하면 그 자체가 규격 위반 */
    @Test
    fun `구현하지 않은 판을 지원으로 광고하지 않는다`() {
        val start = source.indexOf("private val SUPPORTED_VERSIONS")
        val line = source.substring(start, source.indexOf("\n", start))
        assertFalse("2026-07-28 을 지원 목록에 넣었다 (SEP-2243 미구현)", line.contains("2026-07-28"))
    }

    @Test
    fun `버전 헤더와 body meta 불일치를 거부한다`() {
        assertTrue("헤더를 읽지 않는다", source.contains("VERSION_HEADER]"))
        assertTrue("_meta 版本 키를 읽지 않는다", source.contains("META_VERSION_KEY"))
        assertTrue("불일치 검사가 없다", source.contains("ERR_HEADER_MISMATCH"))
        assertTrue("400 을 반환하지 않는다", source.contains("HeaderMismatch"))
    }

    @Test
    fun `미지원 버전은 지원 목록과 함께 400 으로 알린다`() {
        assertTrue("미지원 버전 오류 코드가 없다", source.contains("ERR_UNSUPPORTED_VERSION"))
        assertTrue("지원 목록을 메시지에 담지 않는다", source.contains("지원 버전: \${SUPPORTED_VERSIONS.joinToString()}"))
    }

    @Test
    fun `initialize 는 협상 자리로 통과시킨다`() {
        assertTrue("initialize 가 미지원 버전에도 거부된다", source.contains("declared !in SUPPORTED_VERSIONS && method != \"initialize\""))
    }

    /**
     * initialize 는 클라이언트가 원하는 버전을 `params.protocolVersion` 으로 보낸다.
     * `_meta` 만 보면 declared 가 항상 null 이 되어 협상이 무의미해진다 (실측으로 발견).
     */
    @Test
    fun `initialize 의 요청 버전을 params 에서 읽는다`() {
        assertTrue(
            "params.protocolVersion 을 읽지 않는다",
            source.contains("params.optString(\"protocolVersion\", \"\")"),
        )
        assertTrue(
            "버전 폴백 체인이 없다",
            source.contains("val declared = headerVersion ?: metaVersion ?: requestedVersion"),
        )
    }

    // ── 알림 · 메서드 처리 ────────────────────────────────

    @Test
    fun `알림은 202 Accepted + body 없음`() {
        assertTrue("알림 판정이 없다", source.contains("isNotification = !hasId || method.startsWith(\"notifications/\")"))
        assertTrue("202 로 응답하지 않는다", source.contains("call.respond(HttpStatusCode.Accepted)"))
    }

    @Test
    fun `id 가 없는 요청도 알림으로 본다`() {
        // JSON-RPC 알림은 id 가 없다. id 없는 요청에 id=null 을 그대로 되풀이하면 안 된다
        assertTrue("hasId 판정이 없다", source.contains("json.has(\"id\") && !json.isNull(\"id\")"))
    }

    @Test
    fun `미지원 메서드는 404 + -32601`() {
        assertTrue("메서드 오류 코드가 없다", source.contains("ERR_METHOD_NOT_FOUND = -32601"))
        assertTrue("404 가 아니다", source.contains("HttpStatusCode.NotFound"))
    }

    @Test
    fun `구식 오류 코드는 더 이상 쓰지 않는다`() {
        // -32603 은 내부 오류용이고, "지원하지 않는 메서드" 에 쓰면 안 된다
        val handler = source.substring(
            source.indexOf("private suspend fun handleMcpRequest"),
            source.indexOf("private suspend fun handleMcpToolCall"),
        )
        assertFalse("메서드 처리가 -32603 을 쓴다", handler.contains("-32603"))
    }

    // ── 응답 형태 ─────────────────────────────────────────

    @Test
    fun `도구 실패는 isError 결과로 돌려준다`() {
        assertTrue("isError 를 싣지 않는다", source.contains("put(\"isError\", false)") || source.contains("put(\"isError\", true)"))
        assertTrue("content 배열이 없다", source.contains("put(\"content\", JSONArray()"))
        assertTrue("도구 실패를 runCatching 으로 감싸지 않는다", source.contains("runCatching { executeTool"))
    }

    @Test
    fun `initialize 결과가 규격 필드를 갖는다`() {
        assertTrue("protocolVersion 이 없다", source.contains("put(\"protocolVersion\", negotiated)"))
        assertTrue("capabilities.tools.listChanged 가 없다", source.contains("put(\"listChanged\", false)"))
        assertTrue("serverInfo 가 없다", source.contains("put(\"serverInfo\""))
        assertFalse("서버 버전이 하드코딩되어 있다", source.contains("put(\"version\", \"0.9.0\")"))
        assertTrue("앱 버전을 읽지 않는다", source.contains("getPackageInfo(context.packageName, 0).versionName"))
    }

    @Test
    fun `응답 헬퍼가 상태 코드를 구분한다`() {
        assertTrue("result 헬퍼가 없다", source.contains("respondRpcResult"))
        assertTrue("error 헬퍼가 없다", source.contains("respondRpcError"))
        assertTrue("jsonrpc 2.0 을 싣지 않는다", source.contains("put(\"jsonrpc\", \"2.0\")"))
    }

    // ── 도구 목록 ─────────────────────────────────────────

    @Test
    fun `도구가 12개로 확장되었다`() {
        val tools = McpServer.installedToolNames()
        assertEquals("도구 수 변경", 12, tools.size)
        listOf(
            "download_add_batch", "storage_list", "storage_mkdir", "storage_move",
            "torrent_add", "video_analyze", "stats_summary",
        ).forEach { assertTrue("$it 도구 없음", it in tools) }
    }

    @Test
    fun `모든 도구가 유효한 JSON Schema 를 갖는다`() {
        McpServer.installedToolSchemas().forEach { (name, schema) ->
            val obj = org.json.JSONObject(schema)
            assertTrue("$name schema 에 type 이 없다", obj.has("type"))
            assertEquals("$name schema type", "object", obj.getString("type"))
        }
    }

    @Test
    fun `최종 도구 호출 대상이 목록과 일치한다`() {
        val tools = McpServer.installedToolNames().toSet()
        val dispatch = source.substring(
            source.indexOf("return when (name) {"),
            source.indexOf("// ── 추가 도구"),
        )
        val handled = Regex("\"([a-z_]+)\" ->").findAll(dispatch).map { it.groupValues[1] }.toSet()
        val missing = tools - handled
        assertTrue("목록에만 있고 실행 분기가 없는 도구: $missing", missing.isEmpty())
    }

    @Test
    fun `storage_move 는 라우트와 같은 충돌 판정을 쓴다`() {
        // T-1072 데이터 손실 버그의 가드를 MCP 경로에도 적용한다
        assertTrue("StorageMove 판정을 쓰지 않는다", source.contains("StorageMove.decide("))
        assertTrue("conflict 를 예외로 알리지 않는다", source.contains("StorageMove.Decision.Conflict"))
        assertTrue("overwrite 플래그가 없다", source.contains("overwrite = args.optBoolean(\"overwrite\", false)"))
    }

    @Test
    fun `위험 도구는 프라이버시 예외로 표시된다`() {
        assertTrue("file_read 에 프라이버시 예외 표기가 없다", source.contains("requiresPrivacyExemption = true"))
        val dispatch = source.substring(
            source.indexOf("return when (name) {"),
            source.indexOf("// ── 추가 도구"),
        )
        assertTrue("file_read 실행 분기가 없다", dispatch.contains("\"file_read\" -> fileRead(args)"))
    }
}
