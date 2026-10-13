package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.JobState
import com.borasarang.droidrelay.relay.TorrentState
import com.borasarang.droidrelay.relay.isIdleForAutoStop
import com.borasarang.droidrelay.relay.powerSaveEffective
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePowerSaveTest {

    @Test
    fun `DONE만 있거나 비어있으면 유휴`() {
        assertTrue(isIdleForAutoStop(emptyList(), emptyList(), 0))
        assertTrue(isIdleForAutoStop(emptyList(), listOf(TorrentState.DONE), 0))
        assertTrue(
            isIdleForAutoStop(
                listOf(JobState.DONE, JobState.FAILED, JobState.PAUSED, JobState.CANCELED),
                listOf(TorrentState.DONE),
                0,
            ),
        )
    }

    @Test
    fun `무조건 시도 원칙 - DONE 아닌 토렌트가 있으면 유휴 아님`() {
        // PAUSED·STALLED·FAILED도 대기 작업이다. 엔진이 살려내거나 사용자가 지울 때까지 서버 유지.
        listOf(
            TorrentState.DOWNLOADING,
            TorrentState.FETCHING_METADATA,
            TorrentState.QUEUED,
            TorrentState.SEEDING,
            TorrentState.STALLED,
            TorrentState.PAUSED,
            TorrentState.FAILED,
        ).forEach {
            assertFalse("state=$it", isIdleForAutoStop(emptyList(), listOf(it), 0))
        }
    }

    @Test
    fun `실행·대기 작업이 있으면 유휴 아님`() {
        assertFalse(isIdleForAutoStop(listOf(JobState.RUNNING), emptyList(), 0))
        assertFalse(isIdleForAutoStop(listOf(JobState.QUEUED), emptyList(), 0))
    }

    @Test
    fun `토렌트 활동이 있으면 유휴 아님`() {
        assertFalse(isIdleForAutoStop(emptyList(), listOf(TorrentState.DOWNLOADING), 0))
        assertFalse(isIdleForAutoStop(emptyList(), listOf(TorrentState.FETCHING_METADATA), 0))
        assertFalse(isIdleForAutoStop(emptyList(), listOf(TorrentState.QUEUED), 0))
    }

    @Test
    fun `시딩 중에는 유휴 아님`() {
        assertFalse(isIdleForAutoStop(emptyList(), listOf(TorrentState.SEEDING), 0))
    }

    @Test
    fun `진행 중 전송이 있으면 유휴 아님`() {
        assertFalse(isIdleForAutoStop(emptyList(), emptyList(), 1))
    }

    @Test
    fun `절전 유효값은 설정과 보유 여부의 AND`() {
        assertTrue(powerSaveEffective(true, true))
        assertFalse(powerSaveEffective(true, false))
        assertFalse(powerSaveEffective(false, true))
        assertFalse(powerSaveEffective(false, false))
    }
}
