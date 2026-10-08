package com.borasarang.droidrelay

import com.borasarang.droidrelay.plugin.PluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-1096 — Plugin SDK v2 계약 형식 회귀 테스트.
 * 규칙 원천: RelayConsole `docs/PLUGIN_SDK.md` v2.
 * 계약 문자열을 바꾸면 이 테스트가 깨진다 — 순서 변경·改名·삭제는 계약 버전 올림.
 */
class PluginContractTest {

    @Test
    fun `프로브 응답 v2 정확한 형식`() {
        assertEquals(
            "[PLUGIN] version=2 actions=server_status,server_control,download_add,torrent_add logTag=DroidRelay allowed=true appVersion=0.50.0",
            PluginContract.buildProbeLine(true, "0.50.0"),
        )
        assertEquals(
            "[PLUGIN] version=2 actions=server_status,server_control,download_add,torrent_add logTag=DroidRelay allowed=false appVersion=0.50.0",
            PluginContract.buildProbeLine(false, "0.50.0"),
        )
    }

    @Test
    fun `프로브 파싱 왕복`() {
        val probe = PluginContract.parseProbeLine(PluginContract.buildProbeLine(true, "0.50.0"))!!
        assertEquals(2, probe.version)
        assertEquals(
            listOf("server_status", "server_control", "download_add", "torrent_add"),
            probe.actions,
        )
        assertTrue(probe.allowed)
        assertEquals("0.50.0", probe.appVersion)
        assertFalse(PluginContract.parseProbeLine(PluginContract.buildProbeLine(false, "1.0"))!!.allowed)
    }

    @Test
    fun `프로브 파싱 logcat 접두 허용`() {
        val line = "10-08 12:00:00.000  1234  5678 I DroidRelay: " + PluginContract.buildProbeLine(true, "0.50.0")
        val probe = PluginContract.parseProbeLine(line)!!
        assertEquals(2, probe.version)
        assertTrue(probe.allowed)
    }

    @Test
    fun `프로브 파싱 실패는 null`() {
        assertNull(PluginContract.parseProbeLine("응답 없음"))
        assertNull(PluginContract.parseProbeLine("[PLUGIN] version=x allowed=true appVersion=1.0"))
        assertNull(PluginContract.parseProbeLine("[PLUGIN] version=2 actions=server_status allowed=maybe appVersion=1.0"))
        // appVersion 필수 (L1부터) — 없으면 구버전 취급
        assertNull(PluginContract.parseProbeLine("[PLUGIN] version=2 actions=server_status allowed=true"))
    }

    @Test
    fun `지원 판정 — v2 일치+액션 교집합만 true`() {
        val ok = PluginContract.Probe(2, listOf("server_status"), true, "0.50.0")
        assertTrue(PluginContract.supports(ok))
        assertFalse(PluginContract.supports(PluginContract.Probe(1, listOf("server_status"), true, "0.50.0")))
        assertFalse(PluginContract.supports(PluginContract.Probe(2, listOf("autorotate"), true, "1.0")))
        assertFalse(PluginContract.supports(PluginContract.Probe(2, emptyList(), true, "1.0")))
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
    fun `EVENT 줄 정확한 형식 — level 필수`() {
        assertEquals(
            "[EVENT] type=download_complete id=abc level=info filename=a.mp4 bytes=123",
            PluginContract.eventDownloadComplete("abc", "a.mp4", 123),
        )
        assertEquals(
            "[EVENT] type=download_failed id=abc level=warning filename=a.mp4 errorCode=E-AND-DOWN-1003",
            PluginContract.eventDownloadFailed("abc", "a.mp4", "E-AND-DOWN-1003"),
        )
        assertEquals(
            "[EVENT] type=torrent_complete id=t1 level=info name=ubuntu",
            PluginContract.eventTorrentComplete("t1", "ubuntu"),
        )
        assertEquals(
            "[EVENT] type=torrent_failed id=t1 level=warning name=ubuntu errorCode=-",
            PluginContract.eventTorrentFailed("t1", "ubuntu", "-"),
        )
    }

    @Test
    fun `파일명 공백 sanitize — 파서가 잘라내지 않게`() {
        assertEquals(
            "[EVENT] type=download_complete id=abc level=info filename=my_movie_2024.mp4 bytes=1",
            PluginContract.eventDownloadComplete("abc", "my movie  2024.mp4", 1),
        )
        assertEquals("-", PluginContract.token("   "))
    }

    @Test
    fun `actionsJson 4종 전부 기술`() {
        val json = PluginContract.actionsJson()
        for (id in listOf("server_status", "server_control", "download_add", "torrent_add")) {
            assertTrue(json.contains("\"id\":\"$id\""))
        }
        assertTrue(json.contains("com.borasarang.droidrelay.PLUGIN_ACTION"))
        assertTrue(json.contains("\"arg\":\"\$input\""))
    }
}
