package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.RangeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeParserTest {

    private val total = 10_000L

    @Test
    fun `헤더 없으면 전체 응답`() {
        val r = RangeParser.parse(null, total)
        assertFalse(r.partial); assertFalse(r.invalid)
        assertEquals(0L, r.from); assertEquals(total - 1, r.to)
    }

    @Test
    fun `bytes 접두어 없으면 전체 응답`() {
        val r = RangeParser.parse("items=0-5", total)
        assertFalse(r.partial)
    }

    @Test
    fun `일반 범위 0-499`() {
        val r = RangeParser.parse("bytes=0-499", total)
        assertTrue(r.partial); assertFalse(r.invalid)
        assertEquals(0L, r.from); assertEquals(499L, r.to)
    }

    @Test
    fun `개방형 끝 500- 는 끝까지`() {
        val r = RangeParser.parse("bytes=500-", total)
        assertTrue(r.partial)
        assertEquals(500L, r.from); assertEquals(total - 1, r.to)
    }

    @Test
    fun `서픽스 -500 은 마지막 500바이트`() {
        val r = RangeParser.parse("bytes=-500", total)
        assertTrue(r.partial)
        assertEquals(total - 500, r.from); assertEquals(total - 1, r.to)
    }

    @Test
    fun `끝 초과는 total로 클램프된다`() {
        val r = RangeParser.parse("bytes=9000-999999", total)
        assertTrue(r.partial)
        assertEquals(9000L, r.from); assertEquals(total - 1, r.to)
    }

    @Test
    fun `시작이 total 이상이면 416 invalid`() {
        val r = RangeParser.parse("bytes=10000-20000", total)
        assertTrue(r.invalid); assertFalse(r.partial)
    }

    @Test
    fun `역방향 범위는 416 invalid`() {
        val r = RangeParser.parse("bytes=800-300", total)
        assertTrue(r.invalid)
    }

    @Test
    fun `가비지 헤더는 전체 응답으로 폴백`() {
        val r = RangeParser.parse("bytes=abc", total)
        assertFalse(r.partial); assertFalse(r.invalid)
        assertEquals(0L, r.from)
    }

    @Test
    fun `멀티레인지는 첫 항목만 사용`() {
        val r = RangeParser.parse("bytes=0-99,200-299", total)
        assertTrue(r.partial)
        assertEquals(0L, r.from); assertEquals(99L, r.to)
    }
}
