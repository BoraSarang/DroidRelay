package com.borasarang.droidrelay

import com.borasarang.droidrelay.plugin.PluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-1095 — 플러그인 계약 형식 회귀 테스트.
 * 계약 문자열을 바꾸면 이 테스트가 깨진다 — 필드 순서·키 변경은 계약 버전 올림.
 */
class PluginContractTest {

    @Test
    fun `프로브 응답 정확한 형식`() {
        assertEquals(
            "[PLUGIN] version=1 actions=server_status,server_control,download_add,torrent_add logTag=DroidRelay allowed=true",
            PluginContract.buildProbeLine(true),
        )
        assertEquals(
            "[PLUGIN] version=1 actions=server_status,server_control,download_add,torrent_add logTag=DroidRelay allowed=false",
            PluginContract.buildProbeLine(false),
        )
    }

    @Test
    fun `프로브 파싱 왕복`() {
        val probe = PluginContract.parseProbeLine(PluginContract.buildProbeLine(true))!!
        assertEquals(1, probe.version)
        assertEquals(
            listOf("server_status", "server_control", "download_add", "torrent_add"),
            probe.actions,
        )
        assertTrue(probe.allowed)
        assertFalse(PluginContract.parseProbeLine(PluginContract.buildProbeLine(false))!!.allowed)
    }

    @Test
    fun `프로브 파싱 logcat 접두 허용`() {
        val line = "10-08 12:00:00.000  1234  5678 I DroidRelay: " + PluginContract.buildProbeLine(true)
        val probe = PluginContract.parseProbeLine(line)!!
        assertEquals(1, probe.version)
        assertTrue(probe.allowed)
    }

    @Test
    fun `프로브 파싱 실패는 null`() {
        assertNull(PluginContract.parseProbeLine("응답 없음"))
        assertNull(PluginContract.parseProbeLine("[PLUGIN] version=x allowed=true"))
        assertNull(PluginContract.parseProbeLine("[PLUGIN] version=1 actions=server_status allowed=maybe"))
    }

    @Test
    fun `지원 판정 — 버전 일치+액션 교집합만 true`() {
        val ok = PluginContract.Probe(1, listOf("server_status"), true)
        assertTrue(PluginContract.supports(ok))
        assertFalse(PluginContract.supports(PluginContract.Probe(2, listOf("server_status"), true)))
        assertFalse(PluginContract.supports(PluginContract.Probe(1, listOf("autorotate"), true)))
        assertFalse(PluginContract.supports(PluginContract.Probe(1, emptyList(), true)))
    }

    @Test
    fun `REMOTE 줄 정확한 형식`() {
        assertEquals(
            "[REMOTE] action=server_status ok=true running=true ip=192.168.1.2 port=3000 version=0.50.0",
            PluginContract.remoteServerStatus(true, "192.168.1.2", 3000, "0.50.0"),
        )
        assertEquals(
            "[REMOTE] action=server_control ok=true op=start running=true",
            PluginContract.remoteServerControl("start", true),
        )
        assertEquals(
            "[REMOTE] action=download_add ok=true id=abc duplicate=false",
            PluginContract.remoteDownloadAdd("abc", false),
        )
        assertEquals(
            "[REMOTE] action=torrent_add ok=true id=t1 duplicate=true",
            PluginContract.remoteTorrentAdd("t1", true),
        )
        assertEquals(
            "[REMOTE] action=download_add ok=false errorCode=E-AND-PLG-0002 note=invalid url",
            PluginContract.remoteFailure("download_add", "E-AND-PLG-0002", "invalid url"),
        )
        assertEquals("[REMOTE] 거부됨 (연동 OFF)", PluginContract.REFUSED_LINE)
    }

    @Test
    fun `EVENT 줄 정확한 형식`() {
        assertEquals(
            "[EVENT] type=download_complete id=abc filename=a.mp4 bytes=123",
            PluginContract.eventDownloadComplete("abc", "a.mp4", 123),
        )
        assertEquals(
            "[EVENT] type=download_failed id=abc filename=a.mp4 errorCode=E-AND-DOWN-1003",
            PluginContract.eventDownloadFailed("abc", "a.mp4", "E-AND-DOWN-1003"),
        )
        assertEquals(
            "[EVENT] type=torrent_complete id=t1 name=ubuntu",
            PluginContract.eventTorrentComplete("t1", "ubuntu"),
        )
        assertEquals(
            "[EVENT] type=torrent_failed id=t1 name=ubuntu errorCode=-",
            PluginContract.eventTorrentFailed("t1", "ubuntu", "-"),
        )
    }
}
