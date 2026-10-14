package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.MAX_SERVE_SLICE
import com.borasarang.droidrelay.relay.clampServeEnd
import org.junit.Assert.assertEquals
import org.junit.Test

class ServeSliceTest {

    @Test
    fun `작은 파일은 그대로`() {
        val total = 10_000L
        assertEquals(total - 1, clampServeEnd(0, total - 1, total))
    }

    @Test
    fun `상한 정확히는 그대로`() {
        val total = MAX_SERVE_SLICE * 3
        assertEquals(MAX_SERVE_SLICE - 1, clampServeEnd(0, total - 1, total))
    }

    @Test
    fun `큰 파일 첫 요청은 상한에서 잘린다`() {
        val total = 3_700_000_000L
        assertEquals(MAX_SERVE_SLICE - 1, clampServeEnd(0, total - 1, total))
    }

    @Test
    fun `이어받기 두 번째 슬라이스`() {
        val total = 3_700_000_000L
        val from = MAX_SERVE_SLICE
        assertEquals(from + MAX_SERVE_SLICE - 1, clampServeEnd(from, total - 1, total))
    }

    @Test
    fun `꼬리 슬라이스는 끝까지`() {
        val total = MAX_SERVE_SLICE + 1_000L
        assertEquals(total - 1, clampServeEnd(MAX_SERVE_SLICE, total - 1, total))
    }

    @Test
    fun `이미 잘린 Range 요청은 그대로`() {
        val total = 3_700_000_000L
        assertEquals(1_000L, clampServeEnd(0, 1_000L, total))
    }
}
