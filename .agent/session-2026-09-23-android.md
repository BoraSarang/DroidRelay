# 세션 로그 — 2026-09-23 (android, 안정성 Phase A~D)

## 1. 목표
안정성 조사에서 발견된 19건 이슈를 Phase A~D로 전수 수정 (Phase E 제외)

## 2. 수행
- Phase A (#1~#3): publishToDownloads 가드, Jobs/Torrent Persistence 원자 쓰기 (tmp→rename, .tmp 폴백)
- Phase B (#4~#8): tryStart TOCTOU 락, add 중복 URL 가드, RelayServer.start():Boolean, ScheduleJobService scope 재생성, MainActivity/BootReceiver 메인 I/O 제거
- Phase C (#9~#18): onTaskRemoved 유휴 종료, 429/5xx→RETRY, DeviceGate 10분 TTL, moveToStorage 회피 이름, Thumb FFmpeg Semaphore(2), Janitor dirSize 30초 캐시+MediaStore 스캔, UUID/reorder/cache 정리, respondErr JSON 이스케이프, RSS use{}, OkHttp lazy 공유
- Phase D (#19): mockk 1.14.2 + kotlinx-coroutines-test 1.10.2 + turbine 1.2.0, 회귀 테스트 4건
- 문서: CHANGELOG Unreleased 19건 기록, TODO T-1045~T-1049, 세션 로그

## 3. 빌드 검증
- `./build_and_run.sh test android` → BUILD SUCCESSFUL (169 testcases, failures=0 errors=0)
- `./build_and_run.sh lint android` → BUILD SUCCESSFUL (ktlintCheck)
- `assembleDebug` → BUILD SUCCESSFUL
- 실기기: 무선 `10.233.247.205:5555` 설치·런치 성공

## 4. 이슈·해결
- Gradle 병렬 데몬 간섭으로 간헐 `NoSuchFileException`/`EOFException`/daemon stopped — 데몬 정리 후 재실행으로 해소 (테스트 실패 아님)

## 5. 미완료
- Phase E: WebAssets.kt 분할(2859행)·SettingsScreen 분할(1236행)·권한 축소 — 다음 세션
- `server.p12` git 이력 제거, `TLS_KEYSTORE_PASSWORD` BuildConfig 평문 (선행 세션 잔여)

## 6. 커밋
- 안정성 Phase A~D + 문서 정리 일괄 커밋

## 7. 다음
- Phase E 착수 (PLAN 작성 후 분할·권한 축소)

## 8. 상태
✅ Phase A~D 완료, test+lint+assemble+실기기 통과 / ⬜ Phase E 대기
