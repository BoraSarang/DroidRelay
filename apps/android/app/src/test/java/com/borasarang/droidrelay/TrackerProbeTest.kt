package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.TrackerProbe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackerProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `엔드포인트 파싱`() {
        val u = TrackerProbe.parseEndpoint("udp://tracker.opentrackr.org:1337/announce")!!
        assertEquals("udp", u.scheme)
        assertEquals("tracker.opentrackr.org", u.host)
        assertEquals(1337, u.port)
        assertEquals(80, TrackerProbe.parseEndpoint("http://a.com/x")!!.port)
        assertEquals(443, TrackerProbe.parseEndpoint("https://a.com/x")!!.port)
        assertEquals(8443, TrackerProbe.parseEndpoint("https://a.com:8443/x")!!.port)
    }

    @Test
    fun `잘못된 URL은 null`() {
        assertNull(TrackerProbe.parseEndpoint("not a url with spaces"))
        assertNull(TrackerProbe.parseEndpoint("ftp://a.com:21/x"))
        assertNull(TrackerProbe.parseEndpoint("udp://noport/announce"))
        assertNull(TrackerProbe.parseEndpoint(""))
    }

    @Test
    fun `UDP 요청 16바이트 구조`() {
        val req = TrackerProbe.buildUdpConnectRequest(0x12345678)
        assertEquals(16, req.size)
        val buf = ByteBuffer.wrap(req).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x41727101980L, buf.long)
        assertEquals(0, buf.int)
        assertEquals(0x12345678, buf.int)
    }

    @Test
    fun `UDP 응답 검증`() {
        val tx = 999
        val good = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putInt(tx).putLong(12345L).array()
        assertTrue(TrackerProbe.parseUdpConnectResponse(good, tx))
        assertFalse(TrackerProbe.parseUdpConnectResponse(good, tx + 1))
        val err = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(3).putInt(tx).putLong(0L).array()
        assertFalse(TrackerProbe.parseUdpConnectResponse(err, tx))
        assertFalse(TrackerProbe.parseUdpConnectResponse(ByteArray(8), tx))
    }

    @Test
    fun `파싱 불가 URL 프로브는 불가`() {
        val r = TrackerProbe.probeOne("not a url")
        assertFalse(r.reachable)
        assertEquals(-1L, r.rttMs)
    }

    @Test
    fun `닫힌 포트 TCP는 불가`() {
        // 127.0.0.1 고번호 포트는 사실상 닫힘 — 루프백이라 외부망 영향 없음
        val r = TrackerProbe.probeOne("http://127.0.0.1:59999/x", timeoutMs = 1500)
        assertFalse(r.reachable)
    }

    @Test
    fun `도달 우선 정렬`() {
        val urls = listOf("u1", "u2", "u3", "u4")
        val probe = mapOf(
            "u1" to TrackerProbe.CachedProbe(com.borasarang.droidrelay.relay.ProbeResult("u1", false, -1), 0),
            "u2" to TrackerProbe.CachedProbe(com.borasarang.droidrelay.relay.ProbeResult("u2", true, 300), 0),
            "u3" to TrackerProbe.CachedProbe(com.borasarang.droidrelay.relay.ProbeResult("u3", true, 50), 0),
        )
        assertEquals(listOf("u3", "u2", "u4", "u1"), TrackerProbe.orderByProbe(urls, probe))
    }

    @Test
    fun `프로브 캐시 왕복`() {
        val now = 1700000000000L
        val text = TrackerProbe.encodeProbe(
            listOf(
                com.borasarang.droidrelay.relay.ProbeResult("udp://a:80/x", true, 120),
                com.borasarang.droidrelay.relay.ProbeResult("http://b/y", false, -1),
            ),
            now,
        )
        val back = TrackerProbe.decodeProbe(text)
        assertEquals(2, back.size)
        assertTrue(back["udp://a:80/x"]!!.result.reachable)
        assertEquals(120L, back["udp://a:80/x"]!!.result.rttMs)
        assertEquals(now, back["udp://a:80/x"]!!.at)
        assertFalse(back["http://b/y"]!!.result.reachable)
    }

    @Test
    fun `파손 행은 drop`() {
        val back = TrackerProbe.decodeProbe("ok|1|5|100\nbroken\n|||\n")
        assertEquals(1, back.size)
        assertTrue(back.containsKey("ok"))
        assertTrue(TrackerProbe.decodeProbe(null).isEmpty())
    }

    @Test
    fun `파일 캐시 읽기`() {
        val f = tmp.newFile("probe.txt")
        assertTrue(TrackerProbe.getProbeCached(f).isEmpty())
        f.writeText("udp://a:80/x|1|70|1700000000000\n")
        val back = TrackerProbe.getProbeCached(f)
        assertEquals(70L, back["udp://a:80/x"]!!.result.rttMs)
    }
}
