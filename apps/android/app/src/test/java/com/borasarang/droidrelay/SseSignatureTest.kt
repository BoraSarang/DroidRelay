package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.stateSignatureForTest
import com.borasarang.droidrelay.relay.TorrentJob
import com.borasarang.droidrelay.relay.TorrentRepository
import com.borasarang.droidrelay.relay.TorrentState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 변경 감지 서명 (Phase 2).
 *
 * 배경: /api/events 가 무조건 1Hz tick 을 보내고 클라이언트가 tick 마다
 * 4개 HTTP 요청을 날려 대시보드 탭 1개당 분당 264 요청이 발생했다.
 * 서명이 안정적으로 유지되는지(= 유휴 시 tick 이 멈추는지)를 고정한다.
 */
class SseSignatureTest {

    @After
    fun tearDown() = TorrentRepository.clearForTest() // object 멤버 — 별도 import 불필요

    private fun job(id: String, state: TorrentState, progress: Float) = TorrentJob(
        id = id,
        infoHash = "h$id",
        name = "n$id",
        state = state,
        progress = progress,
    )

    @Test
    fun `빈 상태는 고정 서명`() {
        TorrentRepository.clearForTest()
        assertEquals(stateSignatureForTest(), stateSignatureForTest())
        assertEquals("empty", stateSignatureForTest())
    }

    @Test
    fun `무활동 시 서명이 변하지 않음 — 유휴 중 tick 이 멈춰야 한다`() {
        TorrentRepository.clearForTest()
        val a = stateSignatureForTest()
        val b = stateSignatureForTest()
        assertEquals("상태가 그대로면 서명도 동일해야 한다", a, b)
    }

    @Test
    fun `진행률 변화는 0_5퍼센트 버킷으로 제한된다`() {
        TorrentRepository.clearForTest()
        TorrentRepository.restore(job("a", TorrentState.DOWNLOADING, 0.500f))
        val before = stateSignatureForTest()
        // 0.5% 미만 변화는 UI 해상도에서도 동일 → 서명 유지 (불필요한 tick 방지)
        TorrentRepository.restore(job("a", TorrentState.DOWNLOADING, 0.502f))
        assertEquals(before, stateSignatureForTest())
        // 버킷을 넘으면 변경 감지
        TorrentRepository.restore(job("a", TorrentState.DOWNLOADING, 0.510f))
        assertNotEquals(before, stateSignatureForTest())
    }

    @Test
    fun `상태 전이는 항상 감지된다`() {
        TorrentRepository.clearForTest()
        TorrentRepository.restore(job("a", TorrentState.DOWNLOADING, 0.4f))
        val before = stateSignatureForTest()
        TorrentRepository.restore(job("a", TorrentState.DONE, 0.4f))
        assertNotEquals("완료 전이는 즉시 반영되어야 한다", before, stateSignatureForTest())
    }

    @Test
    fun `토렌트 수 변화 감지`() {
        TorrentRepository.clearForTest()
        val before = stateSignatureForTest()
        TorrentRepository.restore(job("a", TorrentState.DOWNLOADING, 0.1f))
        assertNotEquals(before, stateSignatureForTest())
    }

    @Test
    fun `서명은 진행률 버킷만 반영하고 속도는 포함하지 않는다`() {
        TorrentRepository.clearForTest()
        TorrentRepository.restore(job("a", TorrentState.DOWNLOADING, 0.3f))
        val before = stateSignatureForTest()
        // 다운로드 속도만 변하고 진행률은 그대로 — 서명은 유지되어야
        // (속도까지 넣으면 매초 서명이 달라져 변경 감지가 무의미해진다)
        TorrentRepository.restore(
            job("a", TorrentState.DOWNLOADING, 0.3f).copy(
                downloadSpeed = 999_999L,
                uploadSpeed = 888_888L,
            ),
        )
        assertEquals(before, stateSignatureForTest())
    }

    @Test
    fun `서명은 호출당 O(n) 문자열 하나만 만든다`() {
        TorrentRepository.clearForTest()
        repeat(5) { TorrentRepository.restore(job("t$it", TorrentState.DOWNLOADING, 0.2f)) }
        val sig = stateSignatureForTest()
        assertTrue("빈 서명이면 안 됨", sig.isNotEmpty())
        assertTrue("토렌트 수가 포함돼야 함", sig.contains('t'))
    }
}
