package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.WebAssets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대시보드 실시간 갱신 — 서버 부하 계약 (Phase 2).
 *
 * 배경: `/api/events` 가 무조건 1Hz 로 tick 을 보내고 클라이언트가 tick 마다
 * 4개 HTTP 요청을 날려, 대시보드 탭 1개 = 분당 264 요청이었다.
 * 게다가 브라우저는 백그라운드 탭의 `setInterval` 만 throttle 하고
 * **`EventSource` 는 throttle 하지 않으므로**, 사용자가 다른 탭으로 이동하거나
 * 폰을 잠가도 서버가 무한히 깨어 있었다.
 *
 * 이 테스트는 배포되는 HTML/JS 에 그 계약이 실제로 들어 있는지 고정한다.
 * (행동 검증은 `scripts/verify_dashboard_realtime.js` — Node 로 실제 블록을 실행)
 */
class DashboardRealtimeContractTest {

    private val html: String get() = WebAssets.dashboardHtml

    /** 실시간 갱신 블록만 잘라낸다 (다른 스크립트의 DOM 조작에 영향을 받지 않도록) */
    private fun realtimeBlock(): String {
        val start = html.indexOf("var __pollTimer=null;")
        assertTrue("실시간 갱신 블록 시작점을 찾을 수 없음", start >= 0)
        val pageHide = "window.addEventListener('pagehide',closeStream);"
        val end = html.indexOf(pageHide, start)
        assertTrue("pagehide 핸들러를 찾을 수 없음", end >= 0)
        val tail = html.substring(end + pageHide.length, end + pageHide.length + 200)
        val close = tail.indexOf("})();")
        assertTrue("IIFE 닫힘을 찾을 수 없음", close >= 0)
        return html.substring(start, end + pageHide.length + close + "})();".length)
    }

    // ── 가시성 게이트 ──────────────────────────────────

    @Test
    fun `탭 숨김 시 EventSource 와 폴링을 정리한다`() {
        val b = realtimeBlock()
        assertTrue("visibilitychange 핸들러 없음", b.contains("addEventListener('visibilitychange'"))
        assertTrue("pagehide 핸들러 없음", b.contains("addEventListener('pagehide'"))
        // 숨김 분기가 스트림을 닫아야 한다
        assertTrue(
            "hidden 분기에서 closeStream 호출 없음",
            Regex("document\\.hidden\\)\\{\\s*closeStream\\(\\);").containsMatchIn(b),
        )
    }

    @Test
    fun `숨김 상태에서는 재연결하지 않는다`() {
        val b = realtimeBlock()
        // connect() 첫 줄이 가드여야 한다 — 숨김 중에 연결을 다시 만들면 의미가 없다
        assertTrue(
            "connect() 에 document.hidden 가드 없음",
            Regex("function connect\\(\\)\\{\\s*if\\(document\\.hidden").containsMatchIn(b),
        )
    }

    @Test
    fun `복귀 시 재연결과 재조회를 모두 수행한다`() {
        val b = realtimeBlock()
        assertTrue(
            "복귀 분기에서 재조회+재연결 없음",
            Regex("else\\{\\s*refresh\\(\\);connect\\(\\);\\s*\\}").containsMatchIn(b),
        )
    }

    @Test
    fun `closeStream 이 폴링 타이머까지 해제한다`() {
        val b = realtimeBlock()
        assertTrue(
            "closeStream 이 setPoll(0) 호출 없음 — 백업 폴링이 살아남는다",
            Regex("function closeStream\\(\\)").let { rb ->
                rb.find(b)?.let { m ->
                    val body = b.substring(m.range.last, minOf(m.range.last + 260, b.length))
                    body.contains("setPoll(0)")
                } ?: false
            },
        )
    }

    // ── 재연결 백오프 ──────────────────────────────────

    @Test
    fun `재연결은 고정 5초가 아니라 지수 백오프를 쓴다`() {
        val b = realtimeBlock()
        assertFalse("고정 5초 재시도가 남아 있다", b.contains("setTimeout(connect,5000)"))
        assertTrue("지수 백오프 계산 없음", b.contains("Math.pow(2,__rcCount)"))
        assertTrue("백오프 상한 없음", b.contains("Math.min(60000"))
    }

    @Test
    fun `재연결 시도 타이머를 지워 고아 연결을 막는다`() {
        val b = realtimeBlock()
        assertTrue(
            "재연결 예약 전 기존 타이머 정리 없음",
            b.contains("if(__rcTimer)clearTimeout(__rcTimer);"),
        )
        // connect 진입 시 기존 es 닫기 — 이게 없으면 EventSource 가 고아가 되어
        // 서버 측 SSE 코루틴이 영구히 남는다
        assertTrue(
            "connect() 가 기존 연결을 정리하지 않는다",
            b.contains("if(__es){try{__es.close();}catch(e){}__es=null;}") ||
                b.contains("closeStream()"),
        )
    }

    // ── 폴링 완화 ──────────────────────────────────────

    @Test
    fun `SSE 연결 시 백업 폴링은 10초로 완화된다`() {
        val b = realtimeBlock()
        assertTrue("SSE open 시 10초 폴링 완화 없음", b.contains("setPoll(10000)"))
    }

    @Test
    fun `폴링 해제 시 setInterval 이 남지 않아야 한다`() {
        val b = realtimeBlock()
        // setPoll(0) 이 falsy 분기로 null 을 대입해야 다음 호출에서 clear 가 동작한다
        assertTrue(
            "setPoll 이 falsy 를 null 로 대입하지 않는다 (해제 후 재설치 시 누적)",
            Regex("__pollTimer=ms\\?setInterval\\(refresh,ms\\):null;").containsMatchIn(b),
        )
    }

    // ── 디버그 페이지도 동일 계약 ───────────────────────

    @Test
    fun `디버그 로그 페이지도 가시성 게어를 따른다`() {
        val d = WebAssets.debugHtml
        assertTrue("디버그 페이지에 visibilitychange 없음", d.contains("visibilitychange"))
        assertTrue("디버그 페이지에 pagehide 없음", d.contains("pagehide"))
    }
}
