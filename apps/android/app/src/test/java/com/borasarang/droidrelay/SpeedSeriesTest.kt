package com.borasarang.droidrelay

import com.borasarang.droidrelay.ui.SpeedSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSeriesTest {

    @Test
    fun `표본은 최신 순서로 유지된다`() {
        val s = SpeedSeries(capacity = 3)
        s.push(1, 10)
        s.push(2, 20)
        s.push(3, 30)
        assertEquals(listOf(1L, 2L, 3L), s.downPoints())
        assertEquals(listOf(10L, 20L, 30L), s.upPoints())
    }

    @Test
    fun `용량을 넘으면 오래된 표본부터 버린다`() {
        val s = SpeedSeries(capacity = 3)
        (1..5).forEach { s.push(it.toLong(), (it * 10).toLong()) }
        assertEquals(listOf(3L, 4L, 5L), s.downPoints())
        assertEquals(listOf(30L, 40L, 50L), s.upPoints())
        assertEquals(3, s.size())
    }

    @Test
    fun `음수 속도는 0으로 방어한다`() {
        val s = SpeedSeries(capacity = 2)
        s.push(-5, -10)
        assertEquals(listOf(0L), s.downPoints())
        assertEquals(listOf(0L), s.upPoints())
    }

    @Test
    fun `피크는 다운로드와 업로드 중 큰 값이다`() {
        val s = SpeedSeries(capacity = 4)
        s.push(100, 30)
        s.push(20, 900)
        assertEquals(900L, s.peak())
    }

    @Test
    fun `빈 버퍼의 피크는 0이다`() {
        assertEquals(0L, SpeedSeries().peak())
        assertTrue(SpeedSeries().downPoints().isEmpty())
    }
}
