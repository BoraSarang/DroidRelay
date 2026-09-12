package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.DebugBundle
import com.borasarang.droidrelay.relay.PersistenceGuard
import com.borasarang.droidrelay.relay.SettingsMigration
import com.borasarang.droidrelay.relay.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBundleTest {

    @Test
    fun `7엔트리 조립`() {
        val entries = DebugBundle.buildEntries(
            logs = listOf("[I] a"),
            apiCalls = listOf("[API] GET /api/info → 200"),
            metricsJson = "{}",
            settingsMasked = mapOf("port" to 8080),
            jobsRaw = "[]",
            torrentsRaw = "[]",
            deviceJson = "{}",
        )
        assertEquals(7, entries.size)
        assertTrue(entries.containsKey("logs.txt"))
        assertTrue(entries.containsKey("settings.json"))
        assertTrue(String(entries["logs.txt"]!!).contains("[I] a"))
    }

    @Test
    fun `시크릿 마스킹`() {
        val s = AppSettings(
            configVersion = SettingsMigration.CURRENT_VERSION,
            webPassword = "secret123",
            debridApiKey = "k",
            searchApiKey = "s",
            webhookSecret = "w",
        )
        val masked = PersistenceGuard.maskSecrets(s)
        assertEquals("***", masked["webPassword"])
        assertEquals("***", masked["debridApiKey"])
        assertEquals("***", masked["searchApiKey"])
        assertEquals("***", masked["webhookSecret"])
        val json = DebugBundle.settingsToJson(masked)
        assertFalse(json.contains("secret123"))
    }

    @Test
    fun `빈 로그도 엔트리 유지`() {
        val entries = DebugBundle.buildEntries(
            logs = emptyList(),
            apiCalls = emptyList(),
            metricsJson = "{}",
            settingsMasked = emptyMap(),
            jobsRaw = "[]",
            torrentsRaw = "[]",
            deviceJson = "{}",
        )
        assertEquals(7, entries.size)
        assertEquals(0, entries["logs.txt"]!!.size)
    }
}
