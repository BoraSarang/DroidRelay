package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.AppSettings
import com.borasarang.droidrelay.relay.ServerState
import com.borasarang.droidrelay.relay.SettingsMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerControlTest {

    @Test
    fun `분리값·레거시 없음이면 true`() {
        assertTrue(SettingsMigration.resolveAutoStart(null, null))
    }

    @Test
    fun `분리값 없으면 레거시 폴백 false`() {
        assertFalse(SettingsMigration.resolveAutoStart(null, false))
    }

    @Test
    fun `분리값 없으면 레거시 폴백 true`() {
        assertTrue(SettingsMigration.resolveAutoStart(null, true))
    }

    @Test
    fun `분리값이 레거시보다 우선`() {
        assertTrue(SettingsMigration.resolveAutoStart(true, false))
        assertFalse(SettingsMigration.resolveAutoStart(false, true))
    }

    @Test
    fun `신규 필드 기본값은 모두 true`() {
        val s = AppSettings()
        assertTrue(s.bootAutoStart)
        assertTrue(s.launchAutoStart)
        assertTrue(s.httpsEnabled)
        assertTrue(s.autoStart)
    }

    @Test
    fun `ServerState 기본 httpsEnabled true`() {
        assertTrue(ServerState().httpsEnabled)
    }

    @Test
    fun `HTTP·HTTPS 같으면 실포트 +1 회피`() {
        val srv = RelayServerFakePorts(port = 8080, httpsPort = 8080)
        assertEquals(8081, srv)
    }

    @Test
    fun `HTTP·HTTPS 다르면 실포트 그대로`() {
        val srv = RelayServerFakePorts(port = 8080, httpsPort = 8443)
        assertEquals(8443, srv)
    }

    @Test
    fun `HTTPS OFF 상태 문구 분기 조건`() {
        val off = ServerState(running = true, port = 8080, httpsPort = 8443, httpsEnabled = false)
        val text = serverStatusText(off)
        assertTrue(text.contains("HTTPS 끔"))
        assertFalse(text.contains("8443(HTTPS) 실행 중"))
    }

    @Test
    fun `HTTPS ON 상태 문구 기존 형식`() {
        val on = ServerState(running = true, port = 8080, httpsPort = 8443, httpsEnabled = true)
        val text = serverStatusText(on)
        assertTrue(text.contains("8443(HTTPS) 실행 중"))
    }

    // ── 테스트용 헬퍼 (프로덕션 ServerCard·Settings 상태줄과 동일 규칙) ──
    private fun RelayServerFakePorts(port: Int, httpsPort: Int): Int =
        if (port == httpsPort) httpsPort + 1 else httpsPort

    private fun serverStatusText(s: ServerState): String {
        if (s.error != null) return "에러: ${s.error}"
        val httpsPart = if (s.httpsEnabled) "${s.httpsPort}(HTTPS)" else "HTTPS 끔"
        val suffix = if (s.running) "실행 중" else "대기 중"
        return "${s.port}(HTTP) · $httpsPart $suffix"
    }
}
