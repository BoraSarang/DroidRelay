package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.Job
import com.borasarang.droidrelay.relay.JobState
import com.borasarang.droidrelay.ui.DownloadFilter
import com.borasarang.droidrelay.ui.DownloadFilterCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilterTest {

    private fun job(id: String, state: JobState) = Job(id = id, url = "https://example.com/$id", filename = "$id.bin", state = state)

    private val jobs = listOf(
        job("run", JobState.RUNNING),
        job("q", JobState.QUEUED),
        job("pause", JobState.PAUSED),
        job("done", JobState.DONE),
        job("fail", JobState.FAILED),
        job("cancel", JobState.CANCELED),
    )

    @Test
    fun `전체 칩은 실패·취소를 포함한 모든 작업을 보여준다`() {
        assertEquals(6, DownloadFilterCalc.count(jobs, DownloadFilter.ALL))
        assertEquals(jobs, DownloadFilterCalc.apply(jobs, DownloadFilter.ALL))
    }

    @Test
    fun `받는 중 칩은 RUNNING 만 포함한다`() {
        assertEquals(listOf("run"), DownloadFilterCalc.apply(jobs, DownloadFilter.ACTIVE).map { it.id })
    }

    @Test
    fun `대기 칩은 QUEUED 와 PAUSED 를 묶는다`() {
        assertEquals(listOf("q", "pause"), DownloadFilterCalc.apply(jobs, DownloadFilter.QUEUED).map { it.id })
    }

    @Test
    fun `완료 칩은 DONE 만 포함한다`() {
        assertEquals(listOf("done"), DownloadFilterCalc.apply(jobs, DownloadFilter.DONE).map { it.id })
    }

    @Test
    fun `실패 칩은 FAILED 와 CANCELED 를 묶는다`() {
        assertEquals(listOf("fail", "cancel"), DownloadFilterCalc.apply(jobs, DownloadFilter.FAILED).map { it.id })
    }

    @Test
    fun `실패가 없으면 실패 칩을 숨긴다`() {
        val healthy = jobs.filter { it.state != JobState.FAILED && it.state != JobState.CANCELED }
        val chips = DownloadFilterCalc.chips(healthy)
        assertEquals(4, chips.size)
        assertFalse(chips.contains(DownloadFilter.FAILED))
    }

    @Test
    fun `실패가 있으면 실패 칩을 노출한다`() {
        val chips = DownloadFilterCalc.chips(jobs)
        assertEquals(5, chips.size)
        assertTrue(chips.contains(DownloadFilter.FAILED))
    }
}
