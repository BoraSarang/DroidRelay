package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.JobState
import com.borasarang.droidrelay.relay.JobsRepository
import com.borasarang.droidrelay.relay.ThrottleInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLimitTest {

    @Test
    fun `둘 다 무제한이면 무제한`() {
        assertEquals(0L, ThrottleInterceptor.effectiveLimit(0L, 0L))
    }

    @Test
    fun `한쪽만 있으면 그쪽`() {
        assertEquals(512L, ThrottleInterceptor.effectiveLimit(0L, 512L))
        assertEquals(1024L, ThrottleInterceptor.effectiveLimit(1024L, 0L))
    }

    @Test
    fun `둘 다 있으면 최소`() {
        assertEquals(512L, ThrottleInterceptor.effectiveLimit(1024L, 512L))
        assertEquals(512L, ThrottleInterceptor.effectiveLimit(512L, 1024L))
    }

    @Test
    fun `음수는 무제한 취급`() {
        assertEquals(0L, ThrottleInterceptor.effectiveLimit(-5L, -1L))
        assertEquals(512L, ThrottleInterceptor.effectiveLimit(-1L, 512L))
    }

    @Test
    fun `Repo 작업별 제한 저장 흐름`() {
        val job = JobsRepository.add("https://t.local/limit.bin", "limit.bin")
        assertEquals(0L, JobsRepository.get(job.id)?.maxDownBps ?: -1L)
        JobsRepository.update(job.id) { it.copy(maxDownBps = 524288L) }
        assertEquals(524288L, JobsRepository.get(job.id)?.maxDownBps ?: -1L)
        JobsRepository.remove(job.id)
    }

    @Test
    fun `기본값은 무제한`() {
        val job = JobsRepository.add("https://t.local/limit2.bin", "limit2.bin")
        assertEquals(JobState.QUEUED, JobsRepository.get(job.id)?.state)
        assertEquals(0L, job.maxDownBps)
        JobsRepository.remove(job.id)
        assertTrue(JobsRepository.get(job.id) == null)
    }
}
