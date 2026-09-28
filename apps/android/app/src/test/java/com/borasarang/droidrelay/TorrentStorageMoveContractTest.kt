package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.CrossOriginGuard
import com.borasarang.droidrelay.relay.MAX_STORAGE_MOVE_ATTEMPTS
import com.borasarang.droidrelay.relay.METADATA_PLACEHOLDER
import com.borasarang.droidrelay.relay.StorageGuard
import com.borasarang.droidrelay.relay.WebAssets
import com.borasarang.droidrelay.relay.storageDirOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 토렌트 완료 → 보관함 이동 + 삭제 (v0.43, T-1085).
 *
 * ## 실측 재현 (두 건 모두 v0.42 에서 시작)
 *
 * **1. 완료해도 보관함으로 안 옮겨진다**
 * `startStatusPolling` 은 완료 **전이**(직전 상태 → DONE/SEEDING)를 보고
 * `moveToStorage()` 를 불렀다. 그런데 `TORRENT_FINISHED` 알림 핸들러가
 * `state = DONE` 을 **먼저** 기록하고, 폴링은 5초 간격이다. 완료 직후 5초 안에
 * 알림이 먼저 도착하므로 폴링이 볼 때 `prevState == DONE` 이고,
 * 같은 폴링의 `if (prevState == ... DONE ...) return@forEach` 에 막혀
 * **이동 코드에 한 번도 도달하지 못했다.** 결과적으로 파일이 앱 전용
 * 디렉터리(`getExternalFilesDir/torrents`)에 남고 사용자는
 * "보관함에 없다"고 본다. 알림 유실을 대비하려고 만든 코드가
 * 알림이 **잘 오는** 정상 경로에서_move_를 죽인 것이다.
 *
 * **2. 웹에서 삭제가 안 된다**
 * `CrossOriginGuard.contentTypeAllowed` 가 "Content-Type 이 비면 거부"였다.
 * `DELETE /api/torrents/{id}` 는 본문이 없어 브라우저가 Content-Type 을
 * 아예 보내지 않으므로 **415** 를 받고 라우트에 도달하지 못했다.
 * 같은 이유로 `POST /api/jobs/{id}/pause` · `torrentAct` · `RSS/디브리드 점검` 등
 * 본문 없는 제어 요청이 전부 막혔다. 맥 메뉴바 `RelayClient.control()` 도 동일.
 *
 * 이 테스트는 두 결함이 되살아나지 않는지 막는다.
 */
class TorrentStorageMoveContractTest {

    // ── 1. Content-Type 규칙이 본문 없는 요청을 막지 않는다 ──────

    /**
     * 이게 실측 재현이다. `fetch(url, {method:'DELETE'})` 는 Content-Type 을
     * 보내지 않는다 → 앞버전은 415 → 토렌트 삭제 안 됨.
     */
    @Test
    fun `본문 없는 DELETE 는 Content-Type 없이 통과한다`() {
        assertTrue(
            CrossOriginGuard.contentTypeAllowed(null, "/api/torrents/abc", "DELETE", hasBody = false),
        )
        assertTrue(
            CrossOriginGuard.contentTypeAllowed("", "/api/jobs/abc", "POST", hasBody = false),
        )
    }

    @Test
    fun `본문 없는 제어 요청 전체가 통과한다`() {
        listOf(
            "/api/jobs/abc" to "pause",
            "/api/torrents/abc" to "DELETE",
            "/api/rss/0/check" to "POST",
            "/api/debrid/check" to "POST",
        ).forEach { (path, method) ->
            assertTrue(
                "$method $path 가 차단되었다",
                CrossOriginGuard.contentTypeAllowed(null, path, method, hasBody = false),
            )
        }
    }

    /** 본문 없는 요청에는 Content-Type 규칙을 적용하지 않는다 — 방향이 뒤집히면 안 된다 */
    @Test
    fun `본문 없는 요청은 text-plain 이어도 통과한다`() {
        // 본문이 없으므로 파싱 대상이 없다 — Content-Type 값은 무의미하다
        assertTrue(CrossOriginGuard.contentTypeAllowed("text/plain", "/api/torrents/x", "DELETE", hasBody = false))
    }

    // ── 2. 본문 있는 요청의 검사 강도는 그대로다 ────────────────

    /** 이 가드가 막으려던 실제 CSRF 벡터 — 반드시 계속 막혀야 한다 */
    @Test
    fun `본문 있는 text-plain simple request 는 여전히 차단된다`() {
        assertFalse(CrossOriginGuard.contentTypeAllowed("text/plain", "/api/storage/delete", "POST", hasBody = true))
        assertFalse(CrossOriginGuard.contentTypeAllowed("text/plain", "/api/jobs", "POST", hasBody = true))
        assertFalse(CrossOriginGuard.contentTypeAllowed("application/x-www-form-urlencoded", "/api/settings/reset", "POST", hasBody = true))
    }

    @Test
    fun `본문 있는데 Content-Type 이 없으면 차단된다`() {
        assertFalse(CrossOriginGuard.contentTypeAllowed(null, "/api/jobs", "POST", hasBody = true))
        assertFalse(CrossOriginGuard.contentTypeAllowed("   ", "/api/jobs", "POST", hasBody = true))
    }

    @Test
    fun `본문 있는 허용 타입은 통과한다`() {
        listOf("application/json", "application/json; charset=utf-8", "application/octet-stream")
            .forEach { ct ->
                assertTrue("[$ct] 가 차단되었다", CrossOriginGuard.contentTypeAllowed(ct, "/api/jobs", "POST", hasBody = true))
            }
    }

    /** Content-Length 판정 — 0 또는 부재는 "본문 없음" */
    @Test
    fun `본문 존재 판정이 Content-Length 를 따른다`() {
        assertFalse(CrossOriginGuard.hasBody(null, null))
        assertFalse(CrossOriginGuard.hasBody("0", null))
        assertFalse(CrossOriginGuard.hasBody("", null))
        assertFalse(CrossOriginGuard.hasBody("abc", null))
        assertTrue(CrossOriginGuard.hasBody("1", null))
        assertTrue(CrossOriginGuard.hasBody("12345", null))
    }

    /** chunked 는 길이를 알 수 없으므로 "있음"으로 간주 — 보수적 방향 */
    @Test
    fun `Transfer-Encoding 이 있으면 본문 있는 것으로 본다`() {
        assertTrue(CrossOriginGuard.hasBody(null, "chunked"))
        assertTrue(CrossOriginGuard.hasBody("0", "chunked"))
    }

    // ── 3. 대시보드 계약 — 본문 없는 요청이 실제로 존재한다 ──────

    /**
     * 서버를 고쳐도 대시보드가 Content-Type 을 붙이도록 바꾸면 회귀한다.
     * 지금 형태(본문 없음)를 그대로 고정한다.
     */
    @Test
    fun `대시보드 torrent 삭제 호출은 본문 없이 DELETE 다`() {
        val html = WebAssets.dashboardHtml
        assertTrue(
            "토렌트 DELETE 호출이 사라졌다",
            html.contains("fetch('/api/torrents/'+id,{method:'DELETE'})"),
        )
    }

    @Test
    fun `대시보드 torrent 일시정지·재개 호출은 본문 없이 POST 다`() {
        val html = WebAssets.dashboardHtml
        assertTrue("torrentAct 가 사라졌다", html.contains("fetch('/api/torrents/'+id+'/'+a,{method:'POST'})"))
        assertTrue("작업 pause/resume 이 사라졌다", html.contains("fetch('/api/jobs/'+id+'/'+a,{method:'POST'})"))
    }

    // ── 4. 이동 판정 상수 ──────────────────────────────────────

    /** 상한이 있어야 영구 실패가 5초마다 로그를 찍지 않는다 */
    @Test
    fun `보관함 이동 재시도 상한이 유한하다`() {
        assertTrue(MAX_STORAGE_MOVE_ATTEMPTS in 1..10)
    }

    // ── 5. 작업 디렉터리 = 보관함 아래 숨김 폴더 (v0.43 추가분) ──

    /**
     * 이게 40초 정체의 근본 원인이다. `getExternalFilesDir` 는 `/sdcard/Download` 과
     * 다른 마운트라 rename 이 EXDEV 로 실패 → 전량 복사.
     */
    @Test
    fun `작업 디렉터리는 보관함 경로 아래에 있다`() {
        val tmp = storageDirOf("/sdcard/Download/DroidRelay")
        assertEquals("/sdcard/Download/DroidRelay/.torrents", tmp.path)
    }

    @Test
    fun `작업 디렉터리 이름은 숨김 이름 목록에 포함된다`() {
        assertTrue(StorageGuard.isHidden(StorageGuard.TORRENT_TMP_NAME))
        assertTrue(StorageGuard.isHidden(StorageGuard.TRASH_NAME))
    }

    /**
     * **데이터 손실 가드** — 진행 중인 대용량 파일이 용량 계산에 잡히면,
     * `enforceQuota` 가 `sortedBy { lastModified }` 로 **오래된 사용자 파일**을
     * 대신 휴지통으로 보낸다. 그래서 작업 디렉터리는 반드시 숨겨야 한다.
     */
    @Test
    fun `작업 디렉터리는 일반 파일명처럼 취급되지 않는다`() {
        assertFalse(StorageGuard.isHidden("제목없는 폴더"))
        assertFalse(StorageGuard.isHidden("movie.mp4"))
        assertFalse(StorageGuard.isHidden(".hidden-by-user"))
    }

    /**
     * 관리 API 가 워킹 디렉터리에 손댈 수 없다 — 조회·삭제·이동 전부 차단.
     *
     * 계약: **루트 직속** 숨김 디렉터리와 그 하위가 차단된다. 실제로 워킹 디렉터리는
     * `dlRoot/.torrents` 하나뿐이므로 이것으로 충분하다 — 하위 경로는 전부 이 접두사
     * 아래에 있어 함께 막힌다. `영상/.torrents` 처럼 **중첩**된 동명 폴더는 사용자가 만든
     * 정상 폴더라 막지 않는다(`.trash` 의 기존 동작과 동일).
     */
    @Test
    fun `보관함 API 는 숨김 디렉터리를 경로로 받지 않는다`() {
        val sep = java.io.File.separator
        assertNull(StorageGuard.storageFile(StorageGuard.TORRENT_TMP_NAME))
        assertNull(StorageGuard.storageFile(StorageGuard.TORRENT_TMP_NAME + sep))
        assertNull(StorageGuard.storageFile(StorageGuard.TORRENT_TMP_NAME + sep + "아무 torrent 이름"))
        assertNull(StorageGuard.storageFile(StorageGuard.TORRENT_TMP_NAME + sep + "a" + sep + "b"))
        assertNull(StorageGuard.storageFile(StorageGuard.TRASH_NAME))
        // 일반 경로는 여전히 동작해야 한다
        assertNotNull(StorageGuard.storageFile(""))
        assertNotNull(StorageGuard.storageFile("영상"))
    }

    /** 메타데이터 전 이름은 실제 파일명이 아니다 — 이 값으로 이동을 시도하면 안 된다 */
    @Test
    fun `메타데이터 대입자위는 이동 대상이 아니다`() {
        assertEquals("추출 중...", METADATA_PLACEHOLDER)
    }
}
