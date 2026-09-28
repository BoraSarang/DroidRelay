package com.borasarang.droidrelay.relay

import java.net.URI

/**
 * 교차 출처 요청 가드 (v0.42, T-1073/T-1074).
 *
 * **왜 필요한가**
 * 라우트는 `Content-Type` 을 확인하지 않고 `receiveText()` → `JSONObject(body)` 만 수행했다.
 * 브라우저의 cross-origin **simple request** 는 preflight(CORS) 가 없다. `text/plain` 은
 * simple request 타입이라, 같은 Wi-Fi 에서 사용자가 방문한 **임의의 웹사이트** 가
 * `POST /api/jobs`(다운로드 시작) · `POST /api/storage/delete`(파일 삭제) ·
 * `POST /api/settings` 계열(설정 변경) 를 보낼 수 있었다. CORS 는 응답을 가려줄 뿐
 * 요청은 이미 실행된다. 실측으로 폴더가 만들어지는 것까지 확인했다.
 *
 * 참조: OWASP CSRF Prevention Cheat Sheet — 단순히 "JSON 만 받는다"는
 * `text/plain` 이나 Content-Type 생략으로 우회되므로, **Origin 검증이 실질 방어선**이다.
 *
 * **설계 원칙 — 네이티브 클라이언트를 막지 않는다**
 * `Origin` 이 없는 요청(curl · MCP 호스트 · WebDAV(Finder) · 데스크톱 앱)은 통과시킨다.
 * 맥 메뉴바 앱 Phase 2 가 붙을 때 여기서 막히면 안 된다.
 */
internal object CrossOriginGuard {

    /** 본문(Content-Type)을 *__에 대해__ 허용하는 타입. 그 외는 거부. */
    private val ALLOWED_CONTENT_TYPES = listOf(
        "application/json",
        "application/octet-stream", // raw-upload (스트리밍 업로드)
        "multipart/form-data",     // storage/upload
    )

    /** 본문을 받지 않는 메서드 — Content-Type 규칙을 적용하지 않는다. */
    private val BODYLESS_METHODS = setOf("GET", "HEAD", "OPTIONS", "PROPFIND", "TRACE")

    /** 본문을 받지 않는 메서드 — Origin/Sec-Fetch-Site 규칙을 적용하지 않는다. */
    private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "PROPFIND", "TRACE")

    /**
     * Content-Type 규칙을 적용하는 경로.
     * `/dav/` 아래는 WebDAV 클라이언트(Finder·davfs2)가 Content-Type 을 자유롭게 쓰므로 제외한다.
     * (현재 DAV 는 읽기 전용이라 본문을 받지 않지만,将来 PUT 이 추가돼도 통과해야 한다)
     */
    fun isGuardedPath(path: String): Boolean =
        path == "/mcp" || path.startsWith("/mcp/") ||
            path == "/api" || path.startsWith("/api/")

    /**
     * Origin 검증.
     *
     * - `origin` 이 없으면 **통과** — 네이티브 클라이언트·curl·MCP·WebDAV
     * - `Origin: null` (불투명 출처: 샌드박스 iframe 등) 이면 **거부** — fail closed
     * - 파싱 실패하면 **거부** — fail closed
     * - 그 외에는 출처의 host:port 가 요청의 `Host` 와 같아야 한다
     *
     * 스킴은 비교하지 않는다. 프록시/HTTPS 를 거친 요청이 통과해야 하며,
     * 원격 출처가 LAN IP 로 DNS 리바인딩되는 것만 막으면 된다.
     */
    fun originAllowed(origin: String?, requestHost: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        if (requestHost.isNullOrBlank()) return false
        val o = origin.trim()
        if (o.equals("null", ignoreCase = true)) return false

        val uri = runCatching { URI(o) }.getOrNull() ?: return false
        uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        val originAuthority = normalizeHost(host, uri.port)
        val (reqHost, reqPort) = splitAuthority(requestHost.trim())
        val requestAuthority = normalizeHost(reqHost, reqPort)
        return originAuthority == requestAuthority
    }

    /**
     * `Sec-Fetch-Site` 보조 판정.
     * 이 헤더는 **브라우저가 붙이고 JS 로 위조할 수 없다.** `cross-site` 인 변경 요청은 무조건 거부.
     * 헤더가 없으면 비브라우저 클라이언트로 보고 통과.
     */
    fun fetchSiteAllowed(site: String?, method: String): Boolean {
        if (site.isNullOrBlank()) return true
        if (method.uppercase() in SAFE_METHODS) return true
        return !site.trim().equals("cross-site", ignoreCase = true)
    }

    /**
     * 본문 존재 여부.
     *
     * `Content-Length` 가 없거나 0 이면 본문 없는 요청이다. `Transfer-Encoding` 이 있으면
     * 길이를 알 수 없으므로 본문이 **있다**고 본다 — 일부 클라이언트는 빈 몸을 chunked 로 보낸다.
     */
    fun hasBody(contentLength: String?, transferEncoding: String?): Boolean {
        if (!transferEncoding.isNullOrBlank()) return true
        val len = contentLength?.trim()?.toLongOrNull() ?: return false
        return len > 0L
    }

    /**
     * Content-Type 화이트리스트.
     * 본문을 받는 메서드 + [isGuardedPath] + **실제로 본문이 있을 때만** 검사한다.
     *
     * **왜 본문이 있을 때만인가** (v0.43, T-1085)
     * 대시보드의 제어 요청 다수가 본문 없이 호출한다 —
     * `POST /api/jobs/{id}/pause` · `DELETE /api/torrents/{id}` · `POST /api/rss/{id}/check`.
     * 브라우저는 본문이 없으면 `Content-Type` 헤더를 아예 보내지 않는데, 앞버전은
     * "비면 거부"로 이것을 **415 로 막았다.** 토렌트 삭제·일시정지·재개가 전부 죽은 상태였다.
     * 맥 메뉴바 앱 `RelayClient.control()` 도 `URLRequest` 에 본문·Content-Type 을 붙이지 않아
     * 같은 이유로 415 를 받고 있었다(응답을 버리므로 사용자에게는 "아무 일도 없다").
     *
     * **본문이 있는 요청의 검사 강도는 그대로다.** 이 규칙이 막으려던 벡터는
     * `text/plain` 본문으로 `JSONObject` 파싱을 혼란시키는 것이고 그런 요청은 반드시 본문이 있다.
     * 반대로 본문이 없는 요청은 `receiveText()` 가 빈 문자열을 돌려주고 어느 라우트도
     * 상태를 바꾸지 못한다(JSON 파싱 실패).
     *
     * 본문 없는 cross-origin POST/DELETE 는 [originAllowed] 와 [fetchSiteAllowed] 가 막는다 —
     * 브라우저가 `Origin`·`Sec-Fetch-Site` 를 **항상** 붙이고 JS 로 위조할 수 없기 때문이다.
     * 이 파일의 대원칙("Origin 검증이 실질 방어선")과도 일관된다.
     */
    fun contentTypeAllowed(
        contentType: String?,
        path: String,
        method: String,
        hasBody: Boolean = true,
    ): Boolean {
        val m = method.uppercase()
        if (m in BODYLESS_METHODS) return true
        if (!isGuardedPath(path)) return true
        if (!hasBody) return true
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (ct.isEmpty()) return false
        return ALLOWED_CONTENT_TYPES.any { ct == it }
    }

    // ── 내부 ──────────────────────────────────────────────

    /**
     * host:port 정규화.
     * **기본 포트는 제거한다** — 브라우저는 80/443 인 경우 포트를 생략해 보낸다.
     * (생략하지 않으면 `http://host:80` 과 `Host: host` 가 불일치해 정상 요청이 막힌다)
     * DNS 리바인딩 방어의 핵심은 **host** 비교이므로, 기본 포트를 버려도 안전하다.
     */
    private fun normalizeHost(host: String, port: Int?): String {
        var h = host.trim().lowercase()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1) // [::1] → ::1
        if (port != null && port > 0 && port != 80 && port != 443) return "$h:$port"
        return h
    }

    /** `host:port` 분리. IPv6 대괄호 표기를 처리한다. */
    private fun splitAuthority(authority: String): Pair<String, Int?> {
        val a = authority.trim().lowercase()
        if (a.startsWith("[")) {
            val close = a.indexOf(']')
            if (close > 0) {
                val host = a.substring(1, close)
                val rest = a.substring(close + 1)
                val port = rest.removePrefix(":").takeIf { it.isNotEmpty() }?.toIntOrNull()
                return host to port
            }
        }
        val idx = a.lastIndexOf(':')
        if (idx > 0 && a.indexOf(':') == idx) {
            return a.substring(0, idx) to a.substring(idx + 1).toIntOrNull()
        }
        return a to null
    }
}
