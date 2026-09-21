package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.TorrentCounters
import com.borasarang.droidrelay.relay.TrafficDay
import com.borasarang.droidrelay.relay.TrafficLedger
import java.io.File
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrafficLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        TrafficLedger.resetForTest()
        TorrentCounters.resetForTest()
    }

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 12): Long {
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, h, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun ledgerFile(): File = File(tmp.root, TrafficLedger.FILE_NAME)

    @Test
    fun `일자키는 yyyy-MM-dd`() {
        assertEquals("2026-09-21", TrafficLedger.dayKey(ms(2026, 9, 21)))
        assertEquals("2026-01-05", TrafficLedger.dayKey(ms(2026, 1, 5)))
    }

    @Test
    fun `자정 경계에서 일자 분리`() {
        TrafficLedger.configure(ledgerFile())
        TrafficLedger.addDownHttp(100, ms(2026, 9, 21, 23))
        TrafficLedger.addDownHttp(50, ms(2026, 9, 22, 0))
        val days = TrafficLedger.daily(2, ms(2026, 9, 22, 1))
        assertEquals(100L, days[0].downHttp)
        assertEquals(50L, days[1].downHttp)
    }

    @Test
    fun `다운업 breakdown 누적`() {
        TrafficLedger.configure(ledgerFile())
        val t = ms(2026, 9, 21)
        TrafficLedger.addDownHttp(100, t)
        TrafficLedger.addDownVideo(200, t)
        TrafficLedger.addDownTorrent(300, t)
        TrafficLedger.addUpServe(400, t)
        TrafficLedger.addUpTorrent(500, t)
        val s = TrafficLedger.summary(t)
        assertEquals(600L, s.today.downTotal())
        assertEquals(900L, s.today.upTotal())
        assertEquals(100L, s.today.downHttp)
        assertEquals(500L, s.today.upTorrent)
    }

    @Test
    fun `이번달과 누적 분리`() {
        TrafficLedger.configure(ledgerFile())
        TrafficLedger.addDownHttp(100, ms(2026, 8, 15))
        TrafficLedger.addDownHttp(200, ms(2026, 9, 10))
        TrafficLedger.addDownHttp(300, ms(2026, 9, 21))
        val s = TrafficLedger.summary(ms(2026, 9, 21))
        assertEquals(300L, s.today.downTotal())
        assertEquals(500L, s.month.downTotal())
        assertEquals(600L, s.total.downTotal())
    }

    @Test
    fun `daily 빈 날짜는 0으로 채움`() {
        TrafficLedger.configure(ledgerFile())
        TrafficLedger.addDownHttp(70, ms(2026, 9, 21))
        val days = TrafficLedger.daily(3, ms(2026, 9, 21))
        assertEquals(3, days.size)
        assertEquals("2026-09-19", days[0].date)
        assertEquals(0L, days[0].downTotal())
        assertEquals(70L, days[2].downTotal())
    }

    @Test
    fun `400일 초과분 제거`() {
        val all = LinkedHashMap<String, TrafficDay>()
        (1..405).forEach { i ->
            val key = "2025-%02d-%02d".format((i % 12) + 1, (i % 28) + 1) + "-$i"
            all["k%04d".format(i)] = TrafficDay(date = key, downHttp = i.toLong())
        }
        val pruned = TrafficLedger.prune(all)
        assertEquals(400, pruned.size)
    }

    @Test
    fun `저장 후 로드 round-trip`() {
        TrafficLedger.configure(ledgerFile())
        val t = ms(2026, 9, 21)
        TrafficLedger.addDownHttp(123, t)
        TrafficLedger.addUpServe(456, t)
        TrafficLedger.flush()
        assertTrue(ledgerFile().exists())
        // 새 인스턴스처럼 다시 로드
        TrafficLedger.configure(ledgerFile())
        val s = TrafficLedger.summary(t)
        assertEquals(123L, s.today.downHttp)
        assertEquals(456L, s.today.upServe)
    }

    @Test
    fun `손상 파일은 백업 후 빈 복구`() {
        val f = ledgerFile()
        f.writeText("깨진 내용 {{{")
        TrafficLedger.configure(f)
        assertTrue(File(f.parentFile, "${f.name}.bak").exists())
        val s = TrafficLedger.summary(ms(2026, 9, 21))
        assertEquals(0L, s.total.downTotal())
    }

    @Test
    fun `토렌트 첫 관측은 베이스라인만`() {
        val (d, u) = TorrentCounters.diff("t1", 1000, 2000)
        assertEquals(0L, d)
        assertEquals(0L, u)
    }

    @Test
    fun `토렌트 증가분만 가산`() {
        TorrentCounters.diff("t2", 1000, 2000)
        val (d, u) = TorrentCounters.diff("t2", 1500, 2300)
        assertEquals(500L, d)
        assertEquals(300L, u)
    }

    @Test
    fun `토렌트 역행은 리셋 후 0`() {
        TorrentCounters.diff("t3", 1000, 2000)
        val (d, u) = TorrentCounters.diff("t3", 100, 50)
        assertEquals(0L, d)
        assertEquals(0L, u)
        // 새 베이스라인에서 다시 증가
        val (d2, u2) = TorrentCounters.diff("t3", 300, 150)
        assertEquals(200L, d2)
        assertEquals(100L, u2)
    }

    @Test
    fun `음수 가산 방어`() {
        TrafficLedger.configure(ledgerFile())
        val t = ms(2026, 9, 21)
        TrafficLedger.addDownHttp(-50, t)
        assertEquals(0L, TrafficLedger.summary(t).today.downTotal())
    }
}
