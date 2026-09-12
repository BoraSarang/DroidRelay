# PLAN v0.22 — Phase A 안정성 기반 (설정 마이그레이션 + 진단 번들)

> Motrix-Next 차용 P0-1/P0-2. 권장안 확정(2026-09-12): Phase A 우선, 미디어는 해상도+컨테이너로 제한, 쿠키는 메모리만.

## 목표
1. 설정 스키마 버전 도입으로 구버전 DataStore 깨짐을 마이그레이션으로 승격 (땜질식 coerce 유지 + 버전 스탬프)
2. 진단 ZIP + 저장소 무결성 가드로 원격 이슈 1회 확정 (jobs/torrents 손상 시 .bak 보존, 초기화 금지)

## 범위
- `SettingsMigration.kt` 신규 (CURRENT_CONFIG_VERSION=1, 순수함수 + DataStore 버전키)
- `SettingsRepository`: `configVersion` 필드 + `Keys.CONFIG_VERSION` + `ensureMigrated()` suspend + Flow 노출
- `PersistenceGuard.kt` 신규 (JOBS_SCHEMA=1, TORRENTS_SCHEMA=1, backupCorrupt(), maskedSettings())
- `JobsPersistence.load/save`, `TorrentPersistence.load/save`: 손상 시 백업 후 빈 목록, 로그 E-AND-DOWN-2003 유지
- `DebugBundle.kt` 신규 (엔트리 조립 순수함수, 시크릿 마스킹)
- `DebugRoutes`: `GET /api/debug/bundle` ZIP 스트리밍 + `/api/debug/status`에 configVersion/schema 노출
- `RelayService.onCreate`: `ensureMigrated()` 1회 호출 (실패해도 기동 계속)
- 테스트: `SettingsMigrationTest` 6건 + `PersistenceGuardTest` 4건 + `DebugBundleTest` 3건
- 문서: TODO T-961~T-966, CHANGELOG 0.22.0 (미배포), error_message 추가 없음(기존 2003 재사용)

## 비범위
- 쿠키 전달(P0-3), 속도 스케줄, 트래커 동기, 미디어 자막 — Phase B로 이월
- DataStore→SQLite 전환 없음 (JSON 유지, 가드만 추가)
- 시크릿 영속 저장 없음 (번들은 마스킹, 쿠키는 메모리만 — Phase B 원칙 선반영)

## 설계 상세

### P0-1 마이그레이션
```kotlin
object SettingsMigration {
  const val CURRENT_VERSION = 1
  fun needsMigration(stored: Int?): Boolean = (stored ?: 0) < CURRENT_VERSION
  fun parseThemeMode(raw: String?): ThemeMode // SYSTEM 폴백, pure
  fun parseAccessScope(raw: String?): AccessScope // SUBNET_ONLY 폴백, pure
  fun clampPort(v: Int?): Int // 1024..65535, pure
  fun clampThermal(v: Int?): Int // 50..70, pure
}
```
- `AppSettings.configVersion: Int = CURRENT_VERSION`
- `settings Flow`에서 `configVersion = p[CONFIG_VERSION] ?: 0` 노출 (0=마이그레이션 전 설치분)
- `ensureMigrated()`: `data.first()[CONFIG_VERSION]` 확인 → behind면 `edit{ it[CONFIG_VERSION]=CURRENT }` + `[INFO] [FEATURE] 설정마이그레이션 v{old}→v{CURRENT}` 로그
- 멱등: 재실행 시 no-op, 실패 시 로그만 (기동 차단 금지)

### P0-2 무결성 가드
```kotlin
object PersistenceGuard {
  const val JOBS_SCHEMA_VERSION = 1
  const val TORRENTS_SCHEMA_VERSION = 1
  fun backupCorrupt(file: File): File? // file.bak + file.corrupt-<ts> 복사, 원본 유지
  fun maskSecrets(s: AppSettings): Map<String, Any?> // password/apiKey/secret → "***"
}
```
- `JobsPersistence.load` catch → `backupCorrupt(file)` → E-AND-DOWN-2003 로그(백업 경로 포함) → emptyList
- `TorrentPersistence` 동일 (로그 태그 TorrentPersist 유지)
- save는 기존 atomic (.tmp→rename) 유지, 변경 없음

### 진단 번들
- `DebugBundle.buildEntries(logs, apiCalls, metricsJson, settingsMasked, jobsRaw, torrentsRaw, deviceJson): Map<String,ByteArray>` 순수함수
- 라우트 `GET /api/debug/bundle`: `respondOutputStream(Zip)` + `setLevel(0)` 패스스루 + 256KB 버퍼 (T-938/939 패턴 재사용), 파일명 `droidrelay-debug-<yyyyMMdd-HHmmss>.zip`, `DispositionHeader.make()` 적용
- 포함: logs.txt(300) / api-calls.txt(100) / metrics.json / settings.json(마스킹) / jobs.json / torrents.json / device.json(version, uptime, configVersion, schemas)
- 손상 JSON은 `"<unavailable: corrupt, backup kept>"` 텍스트로 대체, 절대 500 금지
- `/api/debug/status` 추가 필드: `configVersion, jobsSchema, torrentsSchema`

## 검증 게이트
1. `./build_and_run.sh test android` (신규 13건 포함 GREEN)
2. `./build_and_run.sh lint android` (ktlint GREEN)
3. `./build_and_run.sh debug android` assemble 성공
4. 수동 E2E (실기기, 사용자 확인 대기 아님 — AI가 adb 가능 시 수행):
   - 구버전(DataStore 버전키 없음) 기동 → configVersion=1 스탬프 + 로그 확인
   - jobs.json 깨뜨리기(`echo corrupt > jobs.json`) → 재기동 시 .bak 생성 + 빈 목록 + 2003 로그
   - `/api/debug/bundle` 200 + ZIP 내 7엔트리 + 시크릿 `***` 확인
   - `/api/debug/status` 버전 필드 확인

## T-번호 (TODO 등록 예정)
- T-961 PLAN + TODO + 버전 상수
- T-962 SettingsMigration + Repository 배선 + 테스트
- T-963 PersistenceGuard + Jobs/Torrent 백업 + 테스트
- T-964 DebugBundle + /bundle 라우트 + status 확장 + 테스트
- T-965 검증(3종 게이트) + CHANGELOG + 세션 로그
- T-966 실기기 E2E (AI 수행 가능분)

## 리스크
- DataStore `first()` 블로킹: `ensureMigrated`는 IO 디스패처에서 호출, `firstBlocking`과 경합 주의 → onCreate scope.launch(IO)로 분리
- 번들 대용량: jobs/torrents 수 MB 가능 → 스트리밍이므로 메모리 Buff 금지, `respondOutputStream` 직접 ZIP
- 시크릿 누출: 번들 마스킹 누락 시 개인정보 유출 → 테스트에서 `***` 단언 필수
