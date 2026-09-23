# PLAN_v0.40_phaseE_android.md
> 생성일: 2026-09-23 | 플랫폼: android | 상태: 구현 완료 (2026-09-23)

## 1. 목표 (1줄)
안정성 Phase A~D 후속으로 WebAssets·SettingsScreen 대형 파일을 분할하고 미사용/과다 권한을 축소한다.

## 2. 범위

### E1. WebAssets.kt 분할 (2859행)
- `WebAssets.kt` — 얇은 파사드 (`dashboardHtml`/`debugHtml` 위임, 공개 API 불변)
- `WebDashboardHtml.kt` — 대시보드 HTML 원문 (lazy)
- `WebDebugHtml.kt` — 디버그 HTML 원문 (lazy)
- 호출부(`RelayServer`, `FaviconTest`)는 `WebAssets.*` 유지 → 수정 없음
- Kotlin 보간 `${StorageGuard...}` 3곳 보존

### E2. SettingsScreen.kt 분할 (1236행)
- `SettingsScreen.kt` — 엔트리 + 섹션 배선만 (~150행 목표)
- `SettingsComponents.kt` — SettingSection/SwitchRow/InfoRow + resetSettings/TrackerProbeRow/SpeedScheduleSection
- `SettingsServerSection.kt` — 서버
- `SettingsGeneralSections.kt` — 화면·다운로드·보안
- `SettingsTorrentSection.kt` — Torrent
- `SettingsServiceSections.kt` — 가드·스케줄·Debrid·터널·MCP·리셋·앱정보
- 각 섹션: `@Composable internal fun XxxSection(s, repo, scope, ctx, …)` 시그니처
- UI 동작·문구·순서 무변경 (리팩토링만)

### E3. 권한 축소 (AndroidManifest)
- `READ_EXTERNAL_STORAGE` → `maxSdkVersion="32"` (API 33+ READ_MEDIA/MANAGE 체계)
- `WRITE_EXTERNAL_STORAGE` → `maxSdkVersion="29"` (API 30+는 MANAGE_EXTERNAL_STORAGE)
- `requestLegacyExternalStorage` 유지 (API 29 하위 호환)
- 유지: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, BOOT, FGS(+dataSync),
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK, SYSTEM_ALERT_WINDOW(디버그 오버레이), MANAGE_EXTERNAL_STORAGE

## 3. 리스크
- 문자열 이동 시 `$`/`${}` 손상 → 이동 후 `node --check`(JS 추출) + FaviconTest
- 섹션 추출 시 파라미터 누락 → ktlint + compileDebugKotlin 게이트
- maxSdkVersion 축소로 API 26~28 공유저장소 쓰기 회귀 → WRITE는 29까지 유지

## 4. 검증
- `./build_and_run.sh test android` GREEN
- `./build_and_run.sh lint android` GREEN
- WebAssets 분할 후 JS `node --check`
- `assembleDebug` + 실기기 설치 (가능 시)
- CHANGELOG·TODO·세션로그 갱신 후 커밋
