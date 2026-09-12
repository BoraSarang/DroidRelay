package com.borasarang.droidrelay.relay

/** 설정 스키마 버전형 마이그레이션 (v0.22 Phase A, Motrix-Next P0-1 차용).
 *
 * DataStore Preferences는 JSON과 달리 스키마 버전이 없어 구버전 설치분이
 * 새 필드 추가 시 기본값·범위 불일치로 깨질 수 있다. 본 객체는 순수함수만
 * 제공해 단위 테스트 가능하게 하며, 실제 영속은 [SettingsRepository.ensureMigrated]가 담당한다.
 *
 * 규칙 (Motrix configMigration 차용, Android식 완화):
 * - 멱등: 재실행 시 no-op
 * - 실패해도 기동 차단 금지 (로그만)
 * - 배열(Set)은 유저 소유로 deep-merge 금지 — 호출자가 덮어쓰지 않는다
 */
object SettingsMigration {
    const val CURRENT_VERSION = 1

    /** 저장된 버전이 마이그레이션 대상인지 (null/0 = 버전키 도입 전 설치분) */
    fun needsMigration(stored: Int?): Boolean = (stored ?: 0) < CURRENT_VERSION

    /** 마이그레이션 후 스탬프할 버전 (현재는 단일 버전이므로 항상 CURRENT) */
    fun migratedVersion(stored: Int?): Int = CURRENT_VERSION

    /** ThemeMode 파싱 — 알 수 없는 값은 SYSTEM 폴백 */
    fun parseThemeMode(raw: String?): ThemeMode =
        runCatching { ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    /** AccessScope 파싱 — 알 수 없는 값은 SUBNET_ONLY 폴백 */
    fun parseAccessScope(raw: String?): AccessScope =
        runCatching { AccessScope.valueOf(raw ?: AccessScope.SUBNET_ONLY.name) }
            .getOrDefault(AccessScope.SUBNET_ONLY)

    /** 포트 범위 수리 — 1024~65535 */
    fun clampPort(v: Int?): Int = (v ?: 8080).coerceIn(1024, 65535)

    /** 가드 열 임계 수리 — 50~70 */
    fun clampThermal(v: Int?): Int = (v ?: 50).coerceIn(50, 70)
}
