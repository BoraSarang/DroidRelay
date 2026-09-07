package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.StreamContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamContentTypeTest {

    @Test
    fun `영상 확장자 매핑`() {
        assertEquals("video/mp4", StreamContentType.forName("a.mp4").toString())
        assertEquals("video/webm", StreamContentType.forName("a.WEBM").toString())
        assertEquals("video/quicktime", StreamContentType.forName("a.mov").toString())
        assertEquals("video/x-matroska", StreamContentType.forName("a.mkv").toString())
    }

    @Test
    fun `오디오·이미지 매핑`() {
        assertEquals("audio/mpeg", StreamContentType.forName("a.mp3").toString())
        assertEquals("image/jpeg", StreamContentType.forName("a.jpg").toString())
        assertEquals("image/png", StreamContentType.forName("a.png").toString())
    }

    @Test
    fun `알 수 없으면 octet-stream`() {
        assertEquals("application/octet-stream", StreamContentType.forName("a.bin").toString())
        assertEquals("application/octet-stream", StreamContentType.forName("noext").toString())
    }
}
