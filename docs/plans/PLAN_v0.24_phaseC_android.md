# PLAN v0.24 — Phase C 운영 완성 (속도스케줄 + 알림 + 버전)

> Motrix-Next 차용 P1-5/P1-8/P2-9. T-974~T-981.

## 목표
1. 시간대별 속도 자동화 — 심야 무제한 / 주간 절약 등 요일+시간 창 기반 전역 제한
2. 알림 → 보관함/토렌트 탭 딥링크 + 완료 후 서버 정지 옵션
3. 버전 단일 진실 + 원자적 범프 스크립트

## 범위
- `SpeedSchedule.kt` 신규 (SpeedWindow 모델 + 수동 직렬화 + decide/isActive 순수함수)
- `SettingsRepository`: `speedSchedule: List<SpeedWindow>` (문자열 키 1개) + setter 3종(set/add/update/delete는 add/update/deleteWindow로)
- `SpeedScheduleManager.kt` 신규 (60초 틱, 가드 스로틀 시 적용 보류, 진입/이탈 시만 apply)
- `RelayService` 배선 (manager start/stop, 완료 전이 시 completionAction 판정)
- API: `GET/POST /api/settings/speed-schedule` (10개 cap, 400 검증)
- 웹 다운로드 설정에 스케줄 블록 + 앱 다운로드 섹션에 목록/토글/삭제/추가 다이얼로그
- `MainActivity.pendingOpenTab` + 알림 PendingIntent (완료→보관함, 토렌트완료→토렌트)
- `completionAction(none|stop_server)` 설정 + 라우트 + 웹/앱 UI + 전이 시 실행
- `gradle.properties` 버전 단일 진실 + `scripts/bump-version.sh`
- 테스트: SpeedSchedule 8건 (직렬화 왕복·요일·자정넘김·우선순위·검증)
- 문서: TODO T-974~T-981, CHANGELOG 0.24.0(미배포), 에러코드 추가 없음(400 "잘못된 요청" 기존 문구)

## 비범위
- 작업별(per-task) 속도 제한, 업로드 스케줄과 다운로드 스케줄 분리 (전역 1세트로 통일)
- 완료 후 시스템 종료/재부팅 (루트 권한 필요, Android 불가)
- Play 스토어 배포 자동화

## 설계 상세

### P1-5 속도 스케줄
```kotlin
data class SpeedWindow(val id: String, val enabled: Boolean, val days: Set<Int>,
  val startMin: Int, val endMin: Int, val downKbps: Long, val upKbps: Long)
// 직렬화: "id|1|1,2,3,4,5|0|360|0|0" ;로 연결 (수동 파서, org.json 미사용 — JVM 테스트 스텁 회피)
// 요일: Calendar.DAY_OF_WEEK (1=일..7=토), endMin<=startMin이면 자정 넘김 (min>=start || min<end)
fun decide(windows, day, min): SpeedWindow? // enabled 중 첫 매칭
```
- 영속: `stringPreferencesKey("speed_schedule")`, 파싱 실패 항목은 drop + 로그
- Manager: 60초 tick → snapshot(settings, guard.isThrottled) → active=decide → lastApplied와 다르면만 `RelayApp.applySpeedLimit` + `[FEATURE] 속도스케줄` 로그. 가드 스로틀 중이면 적용 보류(일시정지가 우선). 이탈 시 수동값 복원.
- 우선순위: 가드 일시정지 > 스케줄 > 수동 (일시정지된 잡은 속도 무관하므로 충돌 없음)
- API 검증: 10개 cap, days⊆1..7 비어있지 않음, 0<=start,end<1440, down/up>=0 (상한 1_000_000Kbps)

### P1-8 알림
- `notify()` 완료 알림에 `PendingIntent(MainActivity, action=OPEN_TAB, tab=2)`; `notifyTorrent()`는 tab=1. `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`.
- `MainActivity`: `pendingOpenTab: Int?` + `handleOpenTab(intent)` (onCreate/onNewIntent) + RootApp 루프에서 소비.
- `completionAction`: 기본 "none". DONE 전이 후 활성(HTTP RUNNING/QUEUED + video 포함 + 토렌트 DOWNLOADING/FETCHING_METADATA/QUEUED)이 0이고 직전까지 활성이었으면 `stop_server` → `RelayService.stop()` + 로그. SEEDING/PAUSED는 비활성 취급.

### P2-9 버전
- `gradle.properties`에 `versionCode/versionName` (단일 진실), `build.gradle.kts`는 참조만
- `scripts/bump-version.sh <versionName>`: versionCode+1, gradle.properties 갱신, CHANGELOG 헤더 존재 확인(없으면 경고만), 커밋/태그는 수동 (파괴적 가드: 서명·배포 영향 없음을 출력에 명시)

## 검증 게이트
1. `testDebugUnitTest` (신규 8건 포함 GREEN)
2. `ktlintCheck -x runKtlintCheckOverKotlinScripts` + WebAssets `node --check`
3. `assembleDebug` + 실기기 설치
4. E2E: 스케줄 창 진입/이탈 로그 + `/api/settings/speed-schedule` roundtrip + 알림 탭 이동(수동 탭) + 버전 스크립트 dry-run + 완료 액션 none 기본(실제 stop은 수동 검증만, 데이터 위험)

## 리스크
- 설정 Flow 수집기(엔진)와 스케줄 apply 경합: 진입/이탈 시만 기록해 발산 방지, 틱은 60초로 배터리 영향 미미
- 완료 후 정지 오발동: 전이 기반(직전 활성→현재 0)으로만 실행, SEEDING 제외 명시
- bump 스크립트의 build.gradle 파싱 실패: 정규식 매칭 실패 시 변경 없이 오류 종료
