package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.StreamDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDetectorTest {

    @Test
    fun `HLS 마스터에서 해상도별 variant 라벨과 URI를 뽑는다`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
            mid.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1920x1080
            high.m3u8
        """.trimIndent()
        val qs = StreamDetector.parseHlsMaster(manifest, "https://cdn.example.com/hls/index.m3u8")
        assertEquals(3, qs.size)
        assertEquals("360p", qs[0].label)
        assertEquals("720p", qs[1].label)
        assertEquals("1080p", qs[2].label)
        assertEquals("https://cdn.example.com/hls/low.m3u8", qs[0].url)
        assertEquals("hls", qs[0].protocol)
    }

    @Test
    fun `HLS 상대 variant URI는 기준 URL 기준으로 절대화된다`() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            /streams/low.m3u8
        """.trimIndent()
        val qs = StreamDetector.parseHlsMaster(manifest, "https://cdn.example.com/a/b/index.m3u8")
        assertEquals("https://cdn.example.com/streams/low.m3u8", qs[0].url)
    }

    @Test
    fun `HLS variant가 하나면 해상도 목록은 단일`() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            single.m3u8
        """.trimIndent()
        val qs = StreamDetector.parseHlsMaster(manifest, "https://cdn.example.com/index.m3u8")
        assertEquals(1, qs.size)
        assertEquals("720p", qs[0].label)
    }

    @Test
    fun `HLS 마스터가 아니면 빈 목록`() {
        // 미디어 플레이리스트(세그먼트 직접)는 RESOLUTION이 없음
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()
        assertTrue(StreamDetector.parseHlsMaster(media, "https://cdn.example.com/seg.m3u8").isEmpty())
    }

    @Test
    fun `DASH 매니페스트에서 Representation 해상도를 뽑는다`() {
        val manifest = """
            <?xml version="1.0"?>
            <MPD>
              <Period>
                <AdaptationSet mimeType="video/mp4" width="1920" height="1080">
                  <Representation id="v1" bandwidth="2600000" width="1920" height="1080">
                    <BaseURL>v1080.mp4</BaseURL>
                  </Representation>
                  <Representation id="v2" bandwidth="1400000" width="1280" height="720">
                    <BaseURL>v720.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val qs = StreamDetector.parseDashManifest(manifest, "https://cdn.example.com/dash/manifest.mpd")
        assertEquals(2, qs.size)
        assertEquals("1080p", qs[0].label)
        assertEquals("720p", qs[1].label)
        assertTrue(qs[0].url.contains("v1080.mp4"))
        assertEquals("dash", qs[0].protocol)
    }

    @Test
    fun `DASH에 Representation이 없으면 빈 목록`() {
        val manifest = """<?xml version="1.0"?><MPD><Period><AdaptationSet/></Period></MPD>"""
        assertTrue(StreamDetector.parseDashManifest(manifest, "https://cdn.example.com/x.mpd").isEmpty())
    }

    @Test
    fun `MP4 직접 URL은 kind mp4로 판정된다`() {
        assertEquals("mp4", StreamDetector.kindOf("https://brand.example.com/v.mp4?token=x"))
        assertEquals("stream", StreamDetector.kindOf("https://cdn.example.com/master.m3u8"))
        assertEquals("stream", StreamDetector.kindOf("https://cdn.example.com/manifest.mpd"))
        assertEquals("page", StreamDetector.kindOf("https://brand.example.com/products/123"))
    }
}
