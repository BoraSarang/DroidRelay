# PLAN v0.16.4 — FGS 크래시 루프 수정 + 배터리 예외 유도 (android)

- **날짜**: 2026-09-05
- **플랫폼**: Android (`apps/android/app`)
- **관련 docs**: `docs/TODO.md`(T-929), `docs/CHANGELOG.md`

## 진단 (실기기 logcat 확정)
- 크래시 = **`ForegroundServiceStartNotAllowedException`** 전부 (`Service.startForeground() not allowed due to mAllowStartForeground false`)
- 경로: 프로세스 사망 → 시스템이 `START_STICKY`로 `RelayService` 재생성 → `onCreate`의 `startInForeground()`(`RelayService.kt:43`)가 `startForeground` 무조건 호출 → Android 16(API 36)에서 백그라운드 FGS 시작 거부 → **예외 미처리 → `Unable to create service` RuntimeException → 프로세스 사망 → 무한 재시작 루프**
- 확인 크래시: 09:19(서비스 재시작) / 13:32(서비스 재시작) / 13:42(활동 시작) — pid 수 초 내 died
- 환경: 삼성 + Android 16(targetSdk 36) + **배터리 최적화 예외 미설정**(`dumpsys deviceidle whitelist`에 앱 없음) → 시스템 강제 종료 잦음

## 수정
1. **`RelayService.kt` — `startInForeground()` 방어** (핵심)
   - `try { startForeground(...) } catch (Exception)` → `DebugLogger.w` + **백그라운드 모드로 계속 구동** (크래시 루프 중단, 부팅/재시작/활동 시작 경로 모두 커버)
   - `isForeground` 플래그로 성공 상태 유지, 성공 시 `[INFO]` 로그
2. **`onStartCommand` 첫 줄에서 `startInForeground()` 재시도** — 사용자가 앱 재진입/시스템 재시작 시 FGS 허용 타이밍이면 알림 복구 (가드: 이미 포그라운드면 no-op)
3. **`RelayService.start(context)` 안전화** — `startForegroundService` 호출 자체가 백그라운드에서 실패할 수 있으므로 try-catch(로그) + `startService` fallback 시도
4. **배터리 최적화 예외 유도 UI** (사용자 승인 완료)
   - `SettingsScreen` '서버' 섹션에 상태 표시(`PowerManager.isIgnoringBatteryOptimizations`): "배터리 최적화 예외: 허용됨/미허용(백그라운드 종료 위험)"
   - 미허용 시 "배터리 무제한 허용 요청" 버튼 → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`package:...`)
   - `AndroidManifest.xml`: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 권한 추가
5. **버전**: versionCode 21 → **22**, versionName **0.16.4**

## 검증
- `testDebugUnitTest` / `ktlintCheck` / `assembleRelease` GREEN
- 실기기 `R5CT215F4QK` 설치 후:
  - `adb shell am crash com.borasarang.droidrelay`로 **강제 크래시** → 시스템이 서비스 재시작 → **FATAL 없이(크래시 루프 없이) START_STICKY 재생성**되는지 logcat 확인
  - `dumpsys deviceidle whitelist`로 배터리 예외 반영 확인 (사용자가 요청 화면에서 허용 시)
- 문서: TODO T-929 ✅, CHANGELOG v0.16.4, 세션 로그 갱신. 커밋·push는 사용자 요청 시.

## 발생 가능한 시나리오별 대응 (요약)
| 상황 | 개선 전 | 개선 후 |
|---|---|---|
| 시스템 프로세스 kill → START_STICKY 재시작 | 크래시 루프(사망) | 백그라운드로 계속 구동 |
| 부팅 완료 시 dataSync FGS 시작 | 예외→사망 | try-catch로 안전 |
| 사용자 앱 재진입 | — | FGS 재시도로 알림 복구 |
| 배터리 최적화 미허용 | 시스템이 자주 kill | UI로 무제한 허용 유도 |