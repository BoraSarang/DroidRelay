package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.TorrentState
import com.borasarang.droidrelay.relay.shouldAutoResume
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 무조건 시도 원칙: 미완료 PAUSED는 엔진이 스스로 살려낸다. */
class TorrentRetryTest {

    @Test
    fun `미완료 PAUSED는 재시도 대상`() {
        assertTrue(shouldAutoResume(TorrentState.PAUSED, 0f))
        assertTrue(shouldAutoResume(TorrentState.PAUSED, 0.088f))
        assertTrue(shouldAutoResume(TorrentState.PAUSED, 0.999f))
    }

    @Test
    fun `시드비율로 멈춘 완료분은 재시도 제외`() {
        assertFalse(shouldAutoResume(TorrentState.PAUSED, 1f))
    }

    @Test
    fun `PAUSED 아니면 재시도 대상 아님`() {
        assertFalse(shouldAutoResume(TorrentState.DOWNLOADING, 0.5f))
        assertFalse(shouldAutoResume(TorrentState.QUEUED, 0f))
        assertFalse(shouldAutoResume(TorrentState.STALLED, 0.1f))
        assertFalse(shouldAutoResume(TorrentState.FAILED, 0.1f))
        assertFalse(shouldAutoResume(TorrentState.DONE, 1f))
        assertFalse(shouldAutoResume(TorrentState.SEEDING, 1f))
        assertFalse(shouldAutoResume(TorrentState.FETCHING_METADATA, 0f))
    }
}
