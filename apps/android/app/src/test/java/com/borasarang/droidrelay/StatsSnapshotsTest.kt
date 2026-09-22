package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.StatsSnapshots
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsSnapshotsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        StatsSnapshots.resetForTest()
    }

    @Test
    fun `피어 피크 유지`() {
        StatsSnapshots.configure(tmp.root)
        StatsSnapshots.recordPeers(3, 10, 1000L)
        StatsSnapshots.recordPeers(7, 5, 1000L + 6 * 60_000L)
        assertEquals(7L, StatsSnapshots.peakSeeds)
        assertEquals(10L, StatsSnapshots.lastPeers.let { StatsSnapshots.peakPeers })
        assertEquals(5L, StatsSnapshots.lastPeers)
    }

    @Test
    fun `부트 카운트 누적`() {
        StatsSnapshots.configure(tmp.root)
        StatsSnapshots.recordBoot(1000L)
        StatsSnapshots.recordBoot(2000L)
        assertEquals(2L, StatsSnapshots.bootCount)
        assertEquals(1000L, StatsSnapshots.firstBootAt)
        assertEquals(2000L, StatsSnapshots.lastBootAt)
    }

    @Test
    fun `저장공간 당일 quota 누적`() {
        StatsSnapshots.configure(tmp.root)
        StatsSnapshots.recordStorage(1000L, 2000L, 2L, 1_700_000_000_000L)
        StatsSnapshots.recordStorage(900L, 2100L, 3L, 1_700_000_000_000L)
        val stor = StatsSnapshots.storage()
        assertEquals(1, stor.size)
        assertEquals(5L, stor[0].quotaMoved)
        assertEquals(900L, stor[0].dirSize)
    }

    @Test
    fun `단절스로틀 카운트`() {
        StatsSnapshots.configure(tmp.root)
        StatsSnapshots.recordNet(false, 1000L)
        StatsSnapshots.recordNet(true, 2000L)
        StatsSnapshots.recordThrottle(true, "열", 3000L)
        assertEquals(1, StatsSnapshots.netLossCount())
        assertEquals(1, StatsSnapshots.throttleCount())
    }

    @Test
    fun `재로드 round-trip`() {
        StatsSnapshots.configure(tmp.root)
        StatsSnapshots.recordBoot(1000L)
        StatsSnapshots.recordPeers(4, 9, 1000L)
        StatsSnapshots.recordNet(false, 1000L)
        assertEquals(true, File(tmp.root, StatsSnapshots.UPTIME_FILE).exists())
        StatsSnapshots.configure(tmp.root)
        assertEquals(1L, StatsSnapshots.bootCount)
        assertEquals(4L, StatsSnapshots.peakSeeds)
        assertEquals(1, StatsSnapshots.netLossCount())
    }
}
