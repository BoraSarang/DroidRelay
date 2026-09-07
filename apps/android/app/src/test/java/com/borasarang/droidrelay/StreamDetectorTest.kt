package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.StreamDetector
import com.borasarang.droidrelay.relay.VideoDownloadManager
import com.borasarang.droidrelay.relay.VideoException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `HLS 미디어 플레이리스트에서 세그먼트 개수를 센다`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            0000.ts
            #EXTINF:10.0,
            0001.ts
            #EXTINF:3.04,
            0002.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals(3, StreamDetector.countHlsSegments(playlist))
    }

    @Test
    fun `미디어 플레이리스트가 아니거나 비면 세그먼트 0`() {
        assertEquals(0, StreamDetector.countHlsSegments(""))
        assertEquals(0, StreamDetector.countHlsSegments("#EXTM3U\n#EXT-X-ENDLIST\n"))
    }

    @Test
    fun `미디어 플레이리스트에서 총 재생 시간을 EXTINF 합으로 센다`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:10.0,
            0000.ts
            #EXTINF:10.5,
            0001.ts
            #EXTINF:3.04,
            0002.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals(23540L, StreamDetector.playlistDurationMs(playlist))
    }

    @Test
    fun `마스터 또는 빈 플레이리스트는 재생 시간 0`() {
        assertEquals(0L, StreamDetector.playlistDurationMs(""))
        assertEquals(0L, StreamDetector.playlistDurationMs("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nmid.m3u8\n"))
    }

    @Test
    fun `progress 파일에서 마지막 out_time_us를 파싱한다`() {
        val content = """
            frame=100
            out_time_us=5000000
            progress=continue
            frame=200
            out_time_us=12500000
            progress=continue
        """.trimIndent()
        assertEquals(12_500_000L, VideoDownloadManager.parseOutTimeUs(content))
        assertEquals(0L, VideoDownloadManager.parseOutTimeUs("frame=100\nprogress=end\n"))
        assertEquals(0L, VideoDownloadManager.parseOutTimeUs(""))
    }

    @Test
    fun `안전 파일명은 한글을 보존한다_밑줄로 뭉개지지 않음`() {
        // 회귀: ____(_____) 한글 깨짐이 다시 나오지 않도록 한글 보존 보장
        assertEquals("스트림_(직접_주소).mp4", VideoDownloadManager.safeFilename("스트림 (직접 주소)", "mp4"))
        assertEquals("동영상.mp4", VideoDownloadManager.safeFilename("동영상", "mp4"))
        val blank = VideoDownloadManager.safeFilename("  ", "mp4")
        assertTrue(blank.startsWith("video-") && blank.endsWith(".mp4"))
    }

    @Test
    fun `FFmpeg 로그에서 열린 세그먼트 basename을 distinct개로 센다`() {
        val log = """
            [hls @ 0x55] Opening '0000.ts' for reading
            [hls @ 0x55] Opening '0001.ts' for reading
            [hls @ 0x55] Opening '0002.ts' for reading
        """.trimIndent()
        assertEquals(3, VideoDownloadManager.segmentsDoneFromLog(log, 10))
    }

    @Test
    fun `실제 스트림 url_N ts basename도 distinct개로 센다`() {
        // mux 스트림 사례: https://…/url_848/193039199_mp4_h264_aac_hq_7.ts — basename이 구분되므로 distinct개
        val log = """
            [https @ 0x1] Opening 'https://cdn.example.com/url_848/193039199_mp4_h264_aac_hq_7.ts' for reading
            [https @ 0x1] Opening 'https://cdn.example.com/url_849/193039199_mp4_h264_aac_hq_7.ts' for reading
            [https @ 0x1] Opening 'https://cdn.example.com/url_850/193039199_mp4_h264_aac_hq_7.ts' for reading
        """.trimIndent()
        assertEquals(3, VideoDownloadManager.segmentsDoneFromLog(log, 100))
    }

    @Test
    fun `같은 세그먼트를 재시도해도 distinct라 중복 카운트하지 않는다`() {
        val log = """
            [hls] Opening 'partA.ts' for reading
            [hls] Opening 'partA.ts' for reading
            [hls] Opening 'partB.ts' for reading
        """.trimIndent()
        assertEquals(2, VideoDownloadManager.segmentsDoneFromLog(log, 5))
    }

    @Test
    fun `세그먼트 로그가 없으면 0이고 전체를 초과하지 않는다`() {
        assertEquals(0, VideoDownloadManager.segmentsDoneFromLog("", 5))
        val over = VideoDownloadManager.segmentsDoneFromLog("[hls] Opening '0000.ts' for reading\n[hls] Opening '0001.ts' for reading", 1)
        assertEquals(1, over)
    }
}

/**
 * 직접 매니페스트 분석(fetch) 경로 단위 테스트 — JDK 내장 HttpServer 스텁.
 * 403 즉시 전파(E-AND-VID-0206)와 fetch 1회로 variant/세그먼트/재생시간 계측을 검증한다.
 */
class ManifestFetchTest {
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        runCatching { server?.stop(0) }
    }

    /** path → (status, body) 라우팅 로컬 서버를 켜고 포트 반환 (테스트마다 자동 close) */
    private fun startServer(vararg routes: Pair<String, Pair<Int, String>>): Int {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, resp) ->
            s.createContext(path) { ex ->
                val body = resp.second.toByteArray()
                ex.sendResponseHeaders(resp.first, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        s.start()
        server = s
        return s.address.port
    }

    @Test
    fun `직접 매니페스트가 403이면 분석 즉시 차단 예외를 전파한다`() {
        val port = startServer("/v.m3u8" to (403 to "<html>blocked</html>"))
        try {
            StreamDetector.analyze("http://127.0.0.1:$port/v.m3u8")
            fail("403 매니페스트는 VideoException을 던져야 한다")
        } catch (e: VideoException) {
            assertEquals("E-AND-VID-0206", e.code)
        }
    }

    @Test
    fun `미디어 플레이리스트는 fetch 한 번으로 세그먼트와 재생 시간을 계측한다`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            0000.ts
            #EXTINF:6.0,
            0001.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val port = startServer("/media.m3u8" to (200 to playlist))
        val found = StreamDetector.analyze("http://127.0.0.1:$port/media.m3u8")
        assertTrue(found.isDirect)
        assertEquals("stream", found.kind)
        assertEquals(0, found.qualities.size)
        assertEquals(2, found.segmentsTotal)
        assertEquals(12000L, found.durationMs)
    }

    @Test
    fun `마스터 매니페스트는 첫 variant를 팔로우해 세그먼트와 재생 시간을 계측한다`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
            high.m3u8
        """.trimIndent()
        val low = """
            #EXTM3U
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val port = startServer(
            "/index.m3u8" to (200 to master),
            "/low.m3u8" to (200 to low),
        )
        val found = StreamDetector.analyze("http://127.0.0.1:$port/index.m3u8")
        assertEquals(2, found.qualities.size)
        assertEquals("360p", found.qualities[0].label)
        assertEquals(2, found.segmentsTotal)
        assertEquals(12000L, found.durationMs)
    }
}
