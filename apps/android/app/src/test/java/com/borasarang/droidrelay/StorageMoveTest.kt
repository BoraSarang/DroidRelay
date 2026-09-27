package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.StorageMove
import com.borasarang.droidrelay.relay.WebAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 보관함 이동 충돌 가드 (v0.41, T-1072).
 *
 * 배경: 웹 보관함에서 파일을 드래그해 이동할 때, 목적지에 같은 이름이 있으면
 * 사용자 확인 없이 **기존 파일이 덮어써졌다**. 원인은 서버가 이동을
 * `File.renameTo()` 로 수행하는데, 이것이 POSIX `rename(2)` 라 대상 파일이 있어도
 * 조용히 대체하기 때문이었다. `copyTo(overwrite = true)` 폴백도 마찬가지.
 *
 * 같은 저장소의 다른 경로( 이름 변경 · 휴지통 · 휴지통 복원 )는 이미 가드가 있었다 —
 * **이동 경로만 유일하게 무방어**였다. 이 테스트는 그 회귀를 막는다.
 */
class StorageMoveTest {

    // ── 판정 (순수 함수) ───────────────────────────────────

    @Test
    fun `대상이 없으면 그대로 진행한다`() {
        val d = StorageMove.decide(srcName = "a.mp4", dstExists = false)
        assertTrue(d is StorageMove.Decision.Proceed)
    }

    @Test
    fun `대상이 있으면 덮어쓰기 승인 전까지 충돌이다`() {
        val d = StorageMove.decide(srcName = "a.mp4", dstExists = true, dstSize = 1024, dstModified = 555L)
        assertTrue(d is StorageMove.Decision.Conflict)
        val c = d as StorageMove.Decision.Conflict
        assertEquals("a.mp4", c.name)
        assertEquals(1024L, c.size)
        assertEquals(555L, c.modified)
    }

    @Test
    fun `덮어쓰기 승인이면 충돌이 아니다`() {
        val d = StorageMove.decide(srcName = "a.mp4", dstExists = true, overwrite = true)
        assertTrue(d is StorageMove.Decision.Proceed)
    }

    @Test
    fun `폴더도 충돌로 판정한다`() {
        val d = StorageMove.decide(srcName = "dir", dstExists = true, dstIsDir = true) as StorageMove.Decision.Conflict
        assertTrue(d.isDir)
    }

    /** renameTo 가 조용히 덮어쓰는 이 버그의 핵심 — 승인 없이는 절대 진행되면 안 된다 */
    @Test
    fun `덮어쓰기 플래그가 없으면 어떤 경우에도 진행되지 않는다`() {
        listOf(true, false).forEach { isDir ->
            val d = StorageMove.decide(srcName = "x", dstExists = true, dstIsDir = isDir, overwrite = false)
            assertFalse("isDir=$isDir 인데 승인이 없는데 진행되었다", d is StorageMove.Decision.Proceed)
        }
    }

    // ── 충돌 응답 JSON ────────────────────────────────────

    @Test
    fun `충돌 응답이 conflict 플래그와 대상 정보를 담는다`() {
        val f = StorageMove.conflictFields(
            StorageMove.Decision.Conflict("a.mp4", isDir = false, size = 2048, modified = 999L),
        )
        assertEquals(true, f["conflict"])
        assertEquals("a.mp4", f["name"])
        assertEquals(false, f["isDir"])
        assertEquals(2048L, f["size"])
        assertEquals(999L, f["modified"])
        assertTrue("사용자 메시지가 없다", (f["error"] as String).isNotBlank())
    }

    @Test
    fun `충돌 응답은 일반 오류와 구분된다`() {
        // UI 가 "확인 후 재요청 가능" 과 "실패" 를 나눠야 하므로 conflict 플래그가 구분자다
        val f = StorageMove.conflictFields(StorageMove.Decision.Conflict("a", false, 1, 1))
        assertTrue(f.containsKey("conflict"))
        val plain = mapOf<String, Any>("error" to "이동 실패 (원본 보존)")
        assertFalse(plain.containsKey("conflict"))
    }

    // ── 웹 계약: 두 드롭 경로가 모두 가드를 탄다 ──────────

    @Test
    fun `드롭 경로 2곳이 모두 storageMove 를 쓴다`() {
        val html = WebAssets.dashboardHtml
        assertTrue("storageMove 헬퍼가 없다", html.contains("function storageMove(from,to,onDone){"))
        assertTrue("브레드크럼 드롭이 헬퍼를 쓰지 않는다", html.contains("storageMove(src,targetPath,"))
        assertTrue("목록 드롭이 헬퍼를 쓰지 않는다", html.contains("storageMove(src,k,refreshStorage);"))
    }

    @Test
    fun `이동 API 직접 호출은 헬퍼 안에 하나뿐이다`() {
        // 두 개 이상이면 가드를 우회하는 경로가 남아 있는 것이다
        val html = WebAssets.dashboardHtml
        val direct = Regex("""fetch\('/api/storage/move'""").findAll(html).count()
        assertEquals("storageMove 밖의 /api/storage/move 직접 호출이 있다", 1, direct)
        val start = html.indexOf("function storageMove(from,to,onDone){")
        val body = html.substring(start, html.indexOf("\nfunction updateToolbar", start))
        assertTrue(
            "헬퍼가 overwrite 플래그를 전송하지 않는다",
            body.contains("body:JSON.stringify({from:from,to:to,overwrite:!!overwrite})"),
        )
    }

    @Test
    fun `덮어쓰기 확인은 위험 버튼 다이얼로그로 띄운다`() {
        val html = WebAssets.dashboardHtml
        assertTrue("충돌 분기가 없다", html.contains("if(d&&d.conflict){"))
        assertTrue("확인 타이틀이 없다", html.contains("confirmPopup('같은 이름이 이미 있습니다',"))
        assertTrue("위험 표시(danger)가 없다", html.contains("function(){post(true);},true,'덮어쓰기');"))
        assertTrue("되돌릴 수 없음을 안내하지 않는다", html.contains("되돌릴 수 없습니다"))
        // 확인 전에는 목록을 다시 그리지 않는다 (아무 변화 없음)
        assertTrue("확정 전에 onDone 이 호출된다", html.contains("확인 전에는 목록을 다시 그리지 않는다"))
    }

    @Test
    fun `확인 버튼이 행동을 말하고 위험 스타일을 쓴다`() {
        val html = WebAssets.dashboardHtml
        // 파괴적 동작의 버튼이 "확인" 이면 무엇을 승인하는지 알 수 없다
        assertTrue("버튼 라벨을 넘길 수 없다", html.contains("function confirmPopup(title,msg,onOk,danger,okLabel){"))
        assertTrue("라벨 기본값이 없다", html.contains("esc(okLabel||'확인')"))
        assertTrue("덮어쓰기 버튼 라벨이 없다", html.contains(",true,'덮어쓰기');"))
    }

    @Test
    fun `작은 파일 크기를 바이트로 보여준다`() {
        // fmt() 은 1KB 미만을 "0 KB" 로 만든다 — 충돌 안내에서 0 KB 는 무의미하다
        val html = WebAssets.dashboardHtml
        assertTrue("바이트 표시 분기가 없다", html.contains("d.size<1024?d.size+' B':fmt(d.size)"))
    }

    @Test
    fun `최초 요청은 overwrite 없이 나간다`() {
        // 덮어쓰기가 기본값이면 서버 가드가 무의미해진다 — 최초 요청은 반드시 승인 없이
        val html = WebAssets.dashboardHtml
        val start = html.indexOf("function storageMove(from,to,onDone){")
        assertTrue("storageMove 를 찾을 수 없음", start >= 0)
        val end = html.indexOf("\nfunction updateToolbar", start)
        val body = html.substring(start, end)
        val firstPost = body.indexOf("  post(false);")
        assertTrue("최초 요청에 overwrite 가 없다", firstPost > 0)
        // 재요신은 confirmPopup 안에서만 true 로 나간다
        assertTrue("덮어쓰기 재요청 경로가 없다", body.contains("function(){post(true);}"))
    }
}
