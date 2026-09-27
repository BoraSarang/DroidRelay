package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.CrossOriginGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 교차 출처 가드 (v0.42, T-1073/T-1074).
 *
 * 배경: 라우트가 `Content-Type` 을 확인하지 않고 `receiveText()` → `JSONObject(body)` 만
 * 수행해, 브라우저 cross-origin **simple request** 가 그대로 도달했다. `text/plain` POST 로
 * 실제로 `POST /api/storage/mkdir` 가 성공하며 폴더가 만들어졌다(실측).
 * CORS 는 응답만 가려줄 뿐 요청은 이미 실행된다.
 *
 * 대원칙: **네이티브 클라이언트를 막지 않는다.** Origin 이 없는 요청은 통과시켜야 한다
 * (curl · MCP 호스트 · WebDAV(Finder) · 향후 맥 메뉴바 앱).
 */
class CrossOriginGuardTest {

    // ── Origin 검증 ───────────────────────────────────────

    @Test
    fun `Origin 이 없으면 통과 — 네이티브 클라이언트를 막지 않는다`() {
        listOf(null, "", "   ").forEach { o ->
            assertTrue("origin=$o 가 차단되었다", CrossOriginGuard.originAllowed(o, "10.0.0.5:3000"))
        }
    }

    @Test
    fun `같은 출처는 통과`() {
        assertTrue(CrossOriginGuard.originAllowed("http://10.0.0.5:3000", "10.0.0.5:3000"))
        assertTrue(CrossOriginGuard.originAllowed("http://localhost:3000", "localhost:3000"))
        assertTrue(CrossOriginGuard.originAllowed("https://10.0.0.5:8443", "10.0.0.5:8443"))
    }

    @Test
    fun `다른 출처는 차단`() {
        assertFalse(CrossOriginGuard.originAllowed("http://evil.example.com", "10.0.0.5:3000"))
        assertFalse(CrossOriginGuard.originAllowed("http://10.0.0.6:3000", "10.0.0.5:3000"))
        assertFalse(CrossOriginGuard.originAllowed("http://10.0.0.5:9999", "10.0.0.5:3000"))
    }

    /** DNS rebinding: 공격자가 evil.com 을 폰 IP 로 해석시키면 Host 는 폰 IP 다 */
    @Test
    fun `DNS 리바인딩은 Host 로 판정해 차단된다`() {
        assertFalse(CrossOriginGuard.originAllowed("http://evil.example.com", "10.0.0.5:3000"))
        assertFalse(CrossOriginGuard.originAllowed("http://10.0.0.5:3000.evil.com", "10.0.0.5:3000"))
    }

    @Test
    fun `불투명 출처 null 은 차단한다`() {
        // 샌드박스 iframe 등 — 브라우저가 Origin: null 을 보낸다
        assertFalse(CrossOriginGuard.originAllowed("null", "10.0.0.5:3000"))
        assertFalse(CrossOriginGuard.originAllowed("NULL", "10.0.0.5:3000"))
    }

    @Test
    fun `형식이 깨진 Origin 은 fail closed 로 차단한다`() {
        listOf("http://", "://bad", "http://a b c", "not a url").forEach { o ->
            assertFalse("origin=$o 가 통과했다", CrossOriginGuard.originAllowed(o, "10.0.0.5:3000"))
        }
    }

    @Test
    fun `Origin 이 있는데 Host 가 없으면 차단한다`() {
        assertFalse(CrossOriginGuard.originAllowed("http://10.0.0.5:3000", null))
        assertFalse(CrossOriginGuard.originAllowed("http://10.0.0.5:3000", ""))
    }

    @Test
    fun `기본 포트는 생략되어도 같게 본다`() {
        assertTrue(CrossOriginGuard.originAllowed("http://example.com:80", "example.com:80"))
        assertTrue(CrossOriginGuard.originAllowed("https://example.com:443", "example.com:443"))
        // Host 에 포트가 없으면 기본 포트로 해석된다
        assertTrue(CrossOriginGuard.originAllowed("http://example.com:80", "example.com"))
        assertTrue(CrossOriginGuard.originAllowed("https://example.com:443", "example.com"))
    }

    @Test
    fun `대소문자와 IPv6 대괄호를 정규화한다`() {
        assertTrue(CrossOriginGuard.originAllowed("http://EXAMPLE.com:3000", "example.com:3000"))
        assertTrue(CrossOriginGuard.originAllowed("http://[::1]:3000", "[::1]:3000"))
        assertTrue(CrossOriginGuard.originAllowed("http://[::1]:3000", "::1:3000"))
    }

    /** 프록시·HTTPS 를 거친 요청이 막히면 안 된다 (스킴은 비교하지 않는다) */
    @Test
    fun `스킴이 달라도 호스트가 같으면 통과한다`() {
        assertTrue(CrossOriginGuard.originAllowed("https://10.0.0.5:3000", "10.0.0.5:3000"))
    }

    // ── Sec-Fetch-Site 보조 판정 ──────────────────────────

    @Test
    fun `변경 메서드의 cross-site 은 차단한다`() {
        assertFalse(CrossOriginGuard.fetchSiteAllowed("cross-site", "POST"))
        assertFalse(CrossOriginGuard.fetchSiteAllowed("cross-site", "DELETE"))
        assertFalse(CrossOriginGuard.fetchSiteAllowed("CROSS-SITE", "POST"))
    }

    @Test
    fun `같은 사이트·없음은 통과한다`() {
        assertTrue(CrossOriginGuard.fetchSiteAllowed(null, "POST"))
        assertTrue(CrossOriginGuard.fetchSiteAllowed("same-origin", "POST"))
        assertTrue(CrossOriginGuard.fetchSiteAllowed("same-site", "POST"))
    }

    @Test
    fun `읽기 메서드는 Sec-Fetch-Site 을 적용하지 않는다`() {
        listOf("GET", "HEAD", "OPTIONS", "PROPFIND").forEach { m ->
            assertTrue("$m 가 차단되었다", CrossOriginGuard.fetchSiteAllowed("cross-site", m))
        }
    }

    // ── Content-Type 화이트리스트 ─────────────────────────

    @Test
    fun `허용된 Content-Type 만 통과한다`() {
        listOf(
            "application/json",
            "application/json; charset=utf-8",
            "application/octet-stream",
            "multipart/form-data; boundary=x",
        ).forEach { ct ->
            assertTrue("[$ct] 가 차단되었다", CrossOriginGuard.contentTypeAllowed(ct, "/api/jobs", "POST"))
        }
    }

    /** 이게 실측으로 재현된 벡터 */
    @Test
    fun `simple request 타입인 text-plain 은 차단한다`() {
        assertFalse(CrossOriginGuard.contentTypeAllowed("text/plain", "/api/jobs", "POST"))
        assertFalse(CrossOriginGuard.contentTypeAllowed("text/plain;charset=UTF-8", "/api/storage/delete", "POST"))
        assertFalse(CrossOriginGuard.contentTypeAllowed("application/x-www-form-urlencoded", "/api/settings/reset", "POST"))
    }

    /** fetch(body: Blob) 로 타입 없이 보내면 simple request 가 된다 */
    @Test
    fun `Content-Type 이 비어 있어도 거부한다`() {
        assertFalse(CrossOriginGuard.contentTypeAllowed(null, "/api/jobs", "POST"))
        assertFalse(CrossOriginGuard.contentTypeAllowed("", "/api/jobs", "POST"))
        assertFalse(CrossOriginGuard.contentTypeAllowed("   ", "/api/jobs", "POST"))
    }

    @Test
    fun `읽기 메서드는 Content-Type 을 보지 않는다`() {
        listOf("GET", "HEAD", "OPTIONS", "PROPFIND").forEach { m ->
            assertTrue(CrossOriginGuard.contentTypeAllowed(null, "/api/jobs", m))
        }
    }

    /** WebDAV 클라이언트(Finder)는 Content-Type 을 자유롭게 쓴다 */
    @Test
    fun `dav 경로는 제외된다`() {
        assertFalse(CrossOriginGuard.isGuardedPath("/dav/a/b.txt"))
        assertTrue(CrossOriginGuard.contentTypeAllowed("text/plain", "/dav/a/b.txt", "PUT"))
        assertTrue(CrossOriginGuard.contentTypeAllowed(null, "/dav/a/b.txt", "PUT"))
    }

    @Test
    fun `api 와 mcp 경로만 보호한다`() {
        listOf("/api/jobs", "/api/storage/move", "/api", "/mcp", "/mcp/call").forEach { p ->
            assertTrue("$p 가 보호 대상이 아니다", CrossOriginGuard.isGuardedPath(p))
        }
        listOf("/", "/debug", "/dl-file/x", "/stream/y", "/s/abc", "/thumb/z").forEach { p ->
            assertFalse("$p 가 보호 대상이다", CrossOriginGuard.isGuardedPath(p))
        }
    }
}
