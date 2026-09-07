# 세션 로그 2026-09-05 (Android) — FGS 크래시 완전 수정 (v0.16.4 → v0.16.5)

## 세션 요약
- **무엇을/플랫폼**: [ANDROID] "앱이 계속 죽는다" 제보로 시작. **v0.16.4**: `ForegroundServiceStartNotAllowedException` 크래시 루프 확정(09:19/13:32/13:42) → `startInForeground()` try-catch + `onStartCommand` 재시도 + `start()` 안전화 + **배터리 무제한 허용 요청 버튼**. **v0.16.5**: v0.16.4 실사용 70분 만에 **새 크래시 확정** — `ForegroundServiceDidNotStartInTimeException`: `startForegroundService()` 5초 내 `startForeground()` 의무가 try-catch로 소멸되지 않아 시스템이 앱 전체 강제 종료(스택 `MainActivity.kt:69`, `dumpsys code:DENIED`/`isForeground=false` 좀비 ServiceRecord 확인). → **`startService()` 기본 + 기회적 FGS 승격**(의무 타이머 원천 제거), `onCreate`에서 FGS 승격을 채널 생성보다 우선. 배터리 UI: 허용 시 "해제" 버튼 + `LifecycleEventObserver`(ON_RESUME)로 **상태 즉시 갱신**.
- **빌드**: unit/ktlint/assembleRelease GREEN(2회). `R5CT215F4QK` **릴리즈** 설치 — v0.16.4(VC22, 설치 후 실사용 크래시로 폐기) → v0.16.5(VC23, `--rerun-tasks` 릴리즈 재빌드 후 재설치).
- **PERF/CACHE**: 해당 없음(서비스 수명 주기 방어). 크래시 루프 무한 재시작 CPU 소모 해소 + 5초 의무 타이머 크래시 차단.
- **검증/재현**: v0.16.4 `am crash` 재현 1회 통과가 불충분했음을 실사용 크래시(14:10:26)로 확인 → v0.16.5는 `startForegroundService` 의무 경로를 배제해 근본 차단. 배터리 whitelist: **허용됨**(`user,com.borasarang.droidrelay,10352`). 앱 프로세스 정상 생존 확인.
- **남은TODO**: ① 사용자 실기기 장기 안정성 관찰(수 시간~밤새) ② v0.16.5 크래시 미발생 확인 ③ 재발 시 `dumpsys activity services`로 ServiceRecord 점검.
- **전달로그**: v0.16.4의 "catch로 충분" 가정이 틀렸음 — `startForegroundService()`는 catch로 소멸 불가한 5초 의무가 걸림. 따라서 FGS 승격은 **일반 `startService` + 기회 승격**이 정답. 배터리 예외 허용 상태라도 스와이프/재부팅 후에는 확인 필요.
- **문서갱신**: docs/TODO.md T-929 ✅, CHANGELOG v0.16.4/v0.16.5, 세션 로그, `.opencode/plans/PLAN_v0.16.4_fgs-crash_android.md`.
- **큐상태**: 없음.
- **E2E**: 실기기 로그 기반 재현(am crash + 실사용). Unit 테스트는 기존 유지.