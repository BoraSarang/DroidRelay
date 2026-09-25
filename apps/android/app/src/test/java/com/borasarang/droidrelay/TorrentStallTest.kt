package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.SettingsConstraints
import com.borasarang.droidrelay.relay.SpeedLimits
import com.borasarang.droidrelay.relay.isStalledTorrent
import com.borasarang.droidrelay.relay.stallTimedOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentStallTest {

    @Test
    fun `꺼져 있으면 정체 판정 없음`() {
        assertFalse(isStalledTorrent(false, 0L, 2, 0))
        assertFalse(isStalledTorrent(false, 0L, 2, 5))
    }

    @Test
    fun `속도가 기준 미만이면 정체`() {
        assertTrue(isStalledTorrent(true, 100L, 2, 5))
        assertTrue(isStalledTorrent(true, 2047L, 2, 5))
    }

    @Test
    fun `속도가 기준 이상이면 정체 아님`() {
        assertFalse(isStalledTorrent(true, 2048L, 2, 5))
        assertFalse(isStalledTorrent(true, 10_000L, 2, 5))
    }

    @Test
    fun `시더가 없으면 속도와 무관하게 정체`() {
        assertTrue(isStalledTorrent(true, 10_000L, 2, 0))
    }

    @Test
    fun `기준 0이면 속도 조건만 꺼지고 시더 0은 유지`() {
        assertFalse(isStalledTorrent(true, 0L, 0, 3))
        assertTrue(isStalledTorrent(true, 99_999L, 0, 0))
    }

    @Test
    fun `정체 지속 시간 경과 판정`() {
        assertTrue(stallTimedOut(1_000L, 61_000L, 60))
        assertFalse(stallTimedOut(1_000L, 60_999L, 60))
        assertFalse(stallTimedOut(1_000L, 1_001L, 0))
    }

    @Test
    fun `속도 프리셋은 앱·웹이 같은 목록을 쓴다`() {
        assertEquals(listOf(0, 256, 512, 1024, 2048, 5120, 10240, 20480), SpeedLimits.KBPS)
        assertEquals("무제한", SpeedLimits.label(0))
        assertEquals("256KB/s", SpeedLimits.label(256))
        assertEquals("1MB/s", SpeedLimits.label(1024))
        assertEquals("무제한", SpeedLimits.labelBps(0))
        assertEquals("256KB/s", SpeedLimits.labelBps(262144))
        assertEquals("1MB/s", SpeedLimits.labelBps(1048576))
    }

    @Test
    fun `Bps 옵션은 KBps 프리셋의 1024배`() {
        SpeedLimits.bpsOptions().forEachIndexed { i, (bps, label) ->
            assertEquals(SpeedLimits.KBPS[i] * 1024L, bps)
            assertEquals(SpeedLimits.label(SpeedLimits.KBPS[i].toLong()), label)
        }
    }

    @Test
    fun `정체 기본값은 켜짐 2KB-s 60초`() {
        assertTrue(SettingsConstraints.DEFAULT_TORRENT_STALL_ENABLED)
        assertEquals(2, SettingsConstraints.DEFAULT_TORRENT_STALL_THRESHOLD_KBPS)
        assertEquals(60, SettingsConstraints.DEFAULT_TORRENT_STALL_TIMEOUT_SEC)
    }

    @Test
    fun `목록에 없는 현재값은 폴백으로 붙는다`() {
        val opts = SpeedLimits.kbpsOptions(777)
        assertTrue(opts.any { it.first == 777 })
        assertTrue(opts.any { it.first == 1024 })
        assertEquals(opts.size, SpeedLimits.KBPS.size + 1)
    }
}
