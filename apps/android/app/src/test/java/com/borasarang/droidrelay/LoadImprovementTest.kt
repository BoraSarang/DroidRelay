package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.PEER_DETAIL_CAP
import com.borasarang.droidrelay.relay.VideoDownloadManager
import com.borasarang.droidrelay.relay.torrentPollIntervalMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadImprovementTest {

    @Test
    fun `핸들과 목록이 모두 비면 30초 폴링`() {
        assertEquals(30_000L, torrentPollIntervalMs(false, false))
    }

    @Test
    fun `핸들 또는 목록이 있으면 5초 폴링`() {
        assertEquals(5_000L, torrentPollIntervalMs(true, false))
        assertEquals(5_000L, torrentPollIntervalMs(false, true))
        assertEquals(5_000L, torrentPollIntervalMs(true, true))
    }

    @Test
    fun `피어 상세 상한은 양수`() {
        assertTrue(PEER_DETAIL_CAP > 0)
    }

    @Test
    fun `FFmpeg 동시 상한은 2`() {
        assertEquals(2, VideoDownloadManager.MAX_CONCURRENT_VIDEO)
    }
}
