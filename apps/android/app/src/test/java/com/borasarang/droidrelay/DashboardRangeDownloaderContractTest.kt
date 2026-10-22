package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.WebAssets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 웹 대시보드 Range 루프 다운로더 (T-1128) 계약.
 *
 * 배경: `serveFile` 은 응답당 32MB 로 자른다(`MAX_SERVE_SLICE`, A90 OOM 방지).
 * 브라우저 다운로드 매니저는 이 206 을 완료로 저장해 18GB 파일이 **정확히 32MB 로
 * 잘린 채 "완료"** 로 나온다(사용자 제보 실측). → 대시보드가 직접 Range 루프로
 * 이어받는다. 서버 무변경이라 OOM 수정(9eb870c)은 유지된다.
 *
 * 이 테스트군은 그 계약이 깨지지 않게 붙잡는다:
 * 1. 다운로더 함수가 대시보드에 실려 있다
 * 2. 파일·잡 수신 버튼이 다운로더를 거친다 (직접 `<a download>` 와호출 금지)
 * 3. 폴더 ZIP(📦)은 Range 루프를 쓰지 않는다 — `/dl-folder` 는 요청마다 ZIP 을
 *    다시 빌드하므로 슬라이스 루프가 걸리면 빌드가 수십~수백 번 폭주한다
 * 4. Blob 폴백 상한(DL_MEM_MAX)이 유지된다 — HTTP LAN(보안 컨텍스트 아님)에서는
 *    File System Access 가 없어 메모리 누적으로만 받으므로 대용량 가드가 필수
 * 5. 대용량 안내 문구가 **브라우저 능력별로 갈린다** — showSaveFilePicker 는
 *    Chromium 데스크톱 전용이라 Safari·Firefox·모바일 브라우저에 "HTTPS 켜기" 는
 *    처방이 아니다(켜도 그대로 막힌다). 또 showSaveFilePicker 노출 자체가
 *    **보안 컨텍스트 조건**이라 HTTP 판정이 미지원 판정보다 앞서야 한다(T-1145)
 */
class DashboardRangeDownloaderContractTest {

    private val html: String get() = WebAssets.dashboardHtml

    // ── 1. 다운로더 실림 ─────────────────────────────────────────

    @Test
    fun `dlRange 다운로더가 대시보드에 있다`() {
        assertTrue(html.contains("function dlRange(url,name)"))
        assertTrue(html.contains("function dlLoop(url,name,writable)"))
    }

    @Test
    fun `Range 헤더로 슬라이스를 이어받는다`() {
        assertTrue("'Range':'bytes='+offset" in html)
        // 이어받기 조건: 전체 크기 미달이면 다음 슬라이스 요청
        assertTrue(html.contains("offset<total"))
    }

    @Test
    fun `File System Access 미지원 브라우저는 Blob 폴백 + 대용량 가드를 쓴다`() {
        assertTrue(html.contains("window.showSaveFilePicker&&window.isSecureContext"))
        assertTrue("var DL_MEM_MAX=" in html)
        // 가드 없이 메모리에 누적하지 않는다
        assertTrue(html.contains("throw new Error('TOO_BIG')"))
    }

    // ── 1b. 대용량 안내 문구 (브라우저 능력별) ──────────────────────

    @Test
    fun `대용량 안내는 HTTP(비보안 컨텍스트)를 미지원 판정보다 먼저 한다`() {
        // 2026-10-22 실측(Chrome 155 · S22 대시보드): 같은 브라우저에서
        //   https://example.com        → isSecureContext=true,  showSaveFilePicker=function
        //   http://192.168.86.49:3000  → isSecureContext=false, showSaveFilePicker=undefined
        // File System Access 노출 자체가 보안 컨텍스트에 걸린다. 그래서 미지원 판정을 앞에 두면
        // **이미 Chrome 데스크톱인데 "Chrome·Edge 로 열라"** 는 헛걸음 처방이 나간다(T-1129d 실측).
        assertTrue(html.contains("function tooBigAdvice()"))
        val fn = html.substringAfter("function tooBigAdvice()").substringBefore("\n}")
        val insecure = fn.indexOf("!window.isSecureContext")
        val unsupported = fn.indexOf("!window.showSaveFilePicker")
        assertTrue("보안 컨텍스트 판정 없음: $fn", insecure >= 0)
        assertTrue("미지원 판정 없음: $fn", unsupported >= 0)
        assertTrue("HTTP 판정이 미지원 판정보다 먼저여야 한다: $fn", insecure < unsupported)

        val httpText = fn.substring(insecure, unsupported)
        assertTrue("HTTP(비보안) 안내에 HTTPS 처방 없음: $httpText", "HTTPS" in httpText)
        // Safari(macOS/iOS)·Firefox 는 **HTTPS 를 켜도** File System Access 가 없다 —
        // 그 브라우저에 "HTTPS 켜기" 를 처방하면 무의미한 왕복이 된다.
        val unsupportedText = fn.substring(unsupported)
        assertFalse("미지원 브라우저에 HTTPS 처방 금지: $unsupportedText", "HTTPS 를 켜면" in unsupportedText)
    }

    @Test
    fun `대용량 실패는 안내 문구를 거쳐 말한다`() {
        assertTrue(
            "TOO_BIG 실패가 tooBigAdvice 를 거치지 않음",
            "' 파일을 저장할 수 없습니다 — '+tooBigAdvice()" in html,
        )
    }

    // ── 2. 배선 ────────────────────────────────────────────────

    @Test
    fun `보관함 파일 다운로드가 dlRange 를 거친다`() {
        val line = html.lines().first { "dl-file/" in it && "btn-dl" in it }
        assertTrue("dlRange 호출 없음: $line", "dlRange(this.href,this.download)" in line)
        assertTrue("preventDefault 없음: $line", "event.preventDefault()" in line)
    }

    @Test
    fun `잡 수신 버튼이 dlRange 를 거친다`() {
        val line = html.lines().first { "/file/'+j.id+'" in it }
        assertTrue("dlRange 호출 없음: $line", "dlRange(this.href,this.download)" in line)
    }

    // ── 3. 폴더 ZIP은 범위 밖 (재빌드 폭주 방지) ────────────────────

    @Test
    fun `폴더 ZIP 다운로드는 Range 루프를 쓰지 않는다`() {
        val line = html.lines().first { "dl-folder/" in it && "btn-dl" in it }
        assertFalse("폴더 ZIP에 dlRange 적용 금지(요청마다 ZIP 재빌드 폭주): $line", "dlRange" in line)
        assertTrue("기존 앵커 다운로드 유지: $line", "download=" in line)
    }

    // ── 4. 인라인 핸들러 안전 (DashboardInlineHandlerContractTest 지뢰 회피) ──

    @Test
    fun `다운로드 인라인 핸들러에 자유 텍스트를 직접 넣지 않는다`() {
        // this.href / this.download 경유만 허용 — 파일명을 인라인으로 끼우면
        // 속성 깨짐 + 계약 테스트 R1/R2 위반
        for (line in html.lines()) {
            if ("dlRange(" !in line) continue
            if ("onclick=" !in line) continue // 함수 정의 줄은 제외
            assertFalse("파일명 직접 삽입 금지: $line", "dlRange(this.href,'" in line)
            assertTrue("this.href 경유만 허용: $line", "dlRange(this.href,this.download)" in line)
        }
    }
}
