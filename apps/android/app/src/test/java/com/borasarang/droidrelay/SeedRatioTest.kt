package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.shouldPauseAtRatio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedRatioTest {

    @Test
    fun `시딩 중 비율 도달이면 중단`() {
        assertTrue(shouldPauseAtRatio(true, 200L, 100L, 2.0f))
        assertTrue(shouldPauseAtRatio(true, 50L, 100L, 0.5f))
    }

    @Test
    fun `미달이면 계속`() {
        assertFalse(shouldPauseAtRatio(true, 100L, 100L, 2.0f))
    }

    @Test
    fun `시딩 중 아니면 무시`() {
        assertFalse(shouldPauseAtRatio(false, 500L, 100L, 2.0f))
    }

    @Test
    fun `0은 제한 없음`() {
        assertFalse(shouldPauseAtRatio(true, 9999L, 1L, 0f))
    }

    @Test
    fun `다운로드 0이면 무시`() {
        assertFalse(shouldPauseAtRatio(true, 100L, 0L, 0.5f))
    }
}
