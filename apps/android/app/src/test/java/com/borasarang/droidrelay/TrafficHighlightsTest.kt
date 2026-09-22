package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.TrafficBucket
import com.borasarang.droidrelay.relay.TrafficDay
import com.borasarang.droidrelay.relay.TrafficHighlightsCalc
import com.borasarang.droidrelay.relay.TrafficSummary
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficHighlightsTest {

    private fun ms(y: Int, mo: Int, d: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, 12, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun day(date: String, down: Long, up: Long = 0L): TrafficDay =
        TrafficDay(date = date, downTorrent = down, upTorrent = up)

    private fun summaryOf(days: List<TrafficDay>, monthDown: Long = -1): TrafficSummary {
        val total = TrafficBucket(
            downTorrent = days.sumOf { it.downTorrent },
            upTorrent = days.sumOf { it.upTorrent },
        )
        val month = if (monthDown < 0) total else TrafficBucket(downTorrent = monthDown)
        return TrafficSummary(month = month, total = total)
    }

    @Test
    fun `최다 다운로드일과 Top3`() {
        val days = listOf(
            day("2026-09-18", 100),
            day("2026-09-19", 300),
            day("2026-09-20", 200),
            day("2026-09-21", 0),
        )
        val h = TrafficHighlightsCalc.compute(days, summaryOf(days), ms(2026, 9, 21))
        assertEquals("2026-09-19", h.bestDay?.date)
        assertEquals(300L, h.bestDay?.downTotal())
        assertEquals(listOf("2026-09-19", "2026-09-20", "2026-09-18"), h.top3.map { it.date })
    }

    @Test
    fun `기록 없으면 best null`() {
        val days = listOf(day("2026-09-20", 0), day("2026-09-21", 0))
        val h = TrafficHighlightsCalc.compute(days, summaryOf(days), ms(2026, 9, 21))
        assertNull(h.bestDay)
        assertEquals(0, h.top3.size)
    }

    @Test
    fun `월예측은 경과일 비례`() {
        // 9월(30일) 10일 경과, 월누적 1000 → 1000/10*30 = 3000
        val days = (1..10).map { day("2026-09-%02d".format(it), 100) }
        val s = TrafficSummary(
            month = TrafficBucket(downTorrent = 1000),
            total = TrafficBucket(downTorrent = 1000),
        )
        val h = TrafficHighlightsCalc.compute(days, s, ms(2026, 9, 10))
        assertEquals(3000L, h.monthForecastDown)
        // 주간평균 = 마지막 7일 평균 = 100
        assertEquals(100L, h.weekAvgDown)
    }

    @Test
    fun `다운 0이면 비율 null`() {
        val days = listOf(day("2026-09-21", 0))
        val s = TrafficSummary()
        val h = TrafficHighlightsCalc.compute(days, s, ms(2026, 9, 21))
        assertNull(h.shareRatio)
        assertNull(h.torrentRatio)
        assertEquals(0.0, h.httpPct, 0.001)
    }

    @Test
    fun `streak 단절과 최장기록`() {
        val days = listOf(
            day("2026-09-17", 10),
            day("2026-09-18", 20),
            day("2026-09-19", 0),
            day("2026-09-20", 30),
            day("2026-09-21", 40),
        )
        val h = TrafficHighlightsCalc.compute(days, summaryOf(days), ms(2026, 9, 21))
        assertEquals(2, h.currentStreak)
        assertEquals(2, h.longestStreak)
        assertEquals(4, h.activeDays30)
    }

    @Test
    fun `타입 비중 합계 100`() {
        val s = TrafficSummary(
            total = TrafficBucket(downHttp = 50, downVideo = 30, downTorrent = 20),
        )
        val h = TrafficHighlightsCalc.compute(listOf(), s, ms(2026, 9, 21))
        assertEquals(50.0, h.httpPct, 0.001)
        assertEquals(30.0, h.videoPct, 0.001)
        assertEquals(20.0, h.torrentPct, 0.001)
        // 공유비율 = up/down
        val s2 = TrafficSummary(total = TrafficBucket(downTorrent = 1000, upTorrent = 36))
        val h2 = TrafficHighlightsCalc.compute(listOf(), s2, ms(2026, 9, 21))
        assertEquals(0.036, h2.shareRatio!!, 0.0001)
    }
}
