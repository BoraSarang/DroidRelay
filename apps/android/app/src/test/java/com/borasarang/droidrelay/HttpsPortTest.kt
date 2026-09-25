package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.AppSettings
import com.borasarang.droidrelay.relay.ServerState
import com.borasarang.droidrelay.relay.SettingsConstraints
import com.borasarang.droidrelay.relay.SettingsMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsPortTest {

    @Test
    fun `기본값은 HTTP 3000·HTTPS 8443·HTTPS 사용안함`() {
        assertEquals(3000, AppSettings().port)
        assertEquals(8443, AppSettings().httpsPort)
        assertFalse(AppSettings().httpsEnabled)
        assertEquals(3000, ServerState().port)
        assertEquals(8443, ServerState().httpsPort)
        assertFalse(ServerState().httpsEnabled)
        assertEquals(3000, SettingsConstraints.DEFAULT_HTTP_PORT)
        assertFalse(SettingsConstraints.DEFAULT_HTTPS_ENABLED)
    }

    @Test
    fun `같은 포트면 무효`() {
        assertFalse(SettingsConstraints.validPorts(3000, 3000))
        assertFalse(SettingsConstraints.validPorts(8443, 8443))
    }

    @Test
    fun `다른 포트면 유효`() {
        assertTrue(SettingsConstraints.validPorts(3000, 8443))
        assertTrue(SettingsConstraints.validPorts(9000, 9443))
    }

    @Test
    fun `범위 밖이면 무효`() {
        assertFalse(SettingsConstraints.validPorts(80, 8443))
        assertFalse(SettingsConstraints.validPorts(3000, 70000))
        assertFalse(SettingsConstraints.validPorts(0, 0))
    }

    @Test
    fun `HTTPS 기본값 지정 clamp`() {
        assertEquals(8443, SettingsMigration.clampPort(null, 8443))
        assertEquals(9443, SettingsMigration.clampPort(9443, 8443))
        assertEquals(1024, SettingsMigration.clampPort(80, 8443))
        assertEquals(65535, SettingsMigration.clampPort(99999, 8443))
    }

    @Test
    fun `기존 clampPort 기본 동작 유지`() {
        assertEquals(3000, SettingsMigration.clampPort(null))
    }
}
