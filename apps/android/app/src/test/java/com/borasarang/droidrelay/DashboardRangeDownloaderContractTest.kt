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
