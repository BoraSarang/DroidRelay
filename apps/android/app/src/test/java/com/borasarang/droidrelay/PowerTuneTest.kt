package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.AppSettings
import com.borasarang.droidrelay.relay.PowerTune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTuneTest {

    @Test
    fun `저전력 꺼지면 동시성 그대로`() {
        assertEquals(2, PowerTune.effectiveConcurrency(2, false))
        assertEquals(4, PowerTune.effectiveConcurrency(4, false))
    }

    @Test
    fun `저전력 켜지면 동시성 1 강제`() {
        assertEquals(1, PowerTune.effectiveConcurrency(4, true))
        assertEquals(1, PowerTune.effectiveConcurrency(1, true))
    }

    @Test
    fun `저전력 꺼지면 토렌트 활성 그대로`() {
        assertEquals(3, PowerTune.effectiveTorrentMaxActive(3, false))
    }

    @Test
    fun `저전력 켜지면 토렌트 활성 최대 1`() {
        assertEquals(1, PowerTune.effectiveTorrentMaxActive(3, true))
        assertEquals(1, PowerTune.effectiveTorrentMaxActive(1, true))
    }

    @Test
    fun `스냅8Gen1 클럭맵에서 little 4개 선택`() {
        // A510 4 < A710 3 < X2 1
        val freqs = mapOf(
            0 to 1_785_000L, 1 to 1_785_000L, 2 to 1_785_000L, 3 to 1_785_000L,
            4 to 2_496_000L, 5 to 2_496_000L, 6 to 2_496_000L,
            7 to 2_995_000L,
        )
        assertEquals(0x0FL, PowerTune.selectLittleMask(freqs))
    }

    @Test
    fun `빈 맵이면 폴백 마스크`() {
        assertEquals(PowerTune.LITTLE_MASK_FALLBACK, PowerTune.selectLittleMask(emptyMap()))
    }

    @Test
    fun `코어가 little 수보다 적으면 전체 마스크`() {
        assertEquals(0x3L, PowerTune.selectLittleMask(mapOf(0 to 1_000L, 1 to 2_000L)))
    }

    @Test
    fun `JVM에서는 affinity 없이 false — 크래시 금지`() {
        // android.os.Process가 없어 리플렉션 실패 → false (크래시 금지)
        assertFalse(PowerTune.applyToCurrentThread(true))
    }

    @Test
    fun `신규 필드 기본값 — 저전력 OFF·웹서버 ON`() {
        val s = AppSettings()
        assertFalse(s.lowPowerMode)
        assertTrue(s.webServerEnabled)
    }
}
