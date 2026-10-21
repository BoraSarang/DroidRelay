package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T-1144 — 서버 32MB 슬라이스(206)의 Content-Range 총량 파싱 */
class DownloadRangeTest {

    @Test
    fun `Content-Range 의 총량을 읽는다`() {
        assertEquals(83_886_080L, DownloadEngine.parseContentRangeTotal("bytes 0-33554431/83886080"))
        assertEquals(10L, DownloadEngine.parseContentRangeTotal("bytes 9-9/10"))
    }

    @Test
    fun `총량이 없는 헤더는 null 이다`() {
        assertNull(DownloadEngine.parseContentRangeTotal(null))
        assertNull(DownloadEngine.parseContentRangeTotal("bytes 0-10/*"))
        assertNull(DownloadEngine.parseContentRangeTotal(""))
    }

    @Test
    fun `총량 0 이하·숫자 아님은 null 이다`() {
        assertNull(DownloadEngine.parseContentRangeTotal("bytes 0-0/0"))
        assertNull(DownloadEngine.parseContentRangeTotal("bytes 0-0/abc"))
    }
}
