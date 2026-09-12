package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.AccessScope
import com.borasarang.droidrelay.relay.SettingsMigration
import com.borasarang.droidrelay.relay.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {

    @Test
    fun `버전키 없음은 마이그레이션 대상`() {
        assertTrue(SettingsMigration.needsMigration(null))
        assertTrue(SettingsMigration.needsMigration(0))
    }

    @Test
    fun `현행 버전은 대상 아님`() {
        assertFalse(SettingsMigration.needsMigration(SettingsMigration.CURRENT_VERSION))
    }

    @Test
    fun `마이그레이션은 멱등 스탬프`() {
        assertEquals(1, SettingsMigration.migratedVersion(null))
        assertEquals(1, SettingsMigration.migratedVersion(0))
        assertEquals(1, SettingsMigration.migratedVersion(1))
    }

    @Test
    fun `알 수 없는 테마는 SYSTEM 폴백`() {
        assertEquals(ThemeMode.SYSTEM, SettingsMigration.parseThemeMode("UNKNOWN_X"))
        assertEquals(ThemeMode.DARK, SettingsMigration.parseThemeMode("DARK"))
        assertEquals(ThemeMode.SYSTEM, SettingsMigration.parseThemeMode(null))
    }

    @Test
    fun `알 수 없는 범위는 SUBNET_ONLY 폴백`() {
        assertEquals(AccessScope.SUBNET_ONLY, SettingsMigration.parseAccessScope("BAD"))
        assertEquals(AccessScope.ANY_WITH_PASSWORD, SettingsMigration.parseAccessScope("ANY_WITH_PASSWORD"))
    }

    @Test
    fun `포트와 열 임계 수리`() {
        assertEquals(8080, SettingsMigration.clampPort(null))
        assertEquals(1024, SettingsMigration.clampPort(80))
        assertEquals(65535, SettingsMigration.clampPort(99999))
        assertEquals(50, SettingsMigration.clampThermal(null))
        assertEquals(50, SettingsMigration.clampThermal(10))
        assertEquals(70, SettingsMigration.clampThermal(99))
    }
}
