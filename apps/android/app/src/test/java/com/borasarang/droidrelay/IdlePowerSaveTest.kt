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
    fun `전부 비어있으면 유휴`() {
        assertTrue(isIdleForAutoStop(emptyList(), emptyList(), 0))
    }

    @Test
    fun `완료·실패·중지만 있으면 유휴`() {
        assertTrue(
            isIdleForAutoStop(
                listOf(JobState.DONE, JobState.FAILED, JobState.PAUSED, JobState.CANCELED),
                listOf(TorrentState.DONE, TorrentState.FAILED, TorrentState.PAUSED, TorrentState.STALLED),
                0,
            ),
        )
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
