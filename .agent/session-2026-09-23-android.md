# 세션 로그 — 2026-09-23 (android, 안정성 Phase A~D + Phase E)

## 1. 목표
안정성 조사 19건 Phase A~D 전수 수정 + Phase E (WebAssets·SettingsScreen 분할, 권한 축소)

## 2. 수행
- Phase A (#1~#3): publishToDownloads 가드, Jobs/Torrent Persistence 원자 쓰기 (tmp→rename, .tmp 폴백)
- Phase B (#4~#8): tryStart TOCTOU 락, add 중복 URL 가드, RelayServer.start():Boolean, ScheduleJobService scope 재생성, MainActivity/BootReceiver 메인 I/O 제거
- Phase C (#9~#18): onTaskRemoved 유휴 종료, 429/5xx→RETRY, DeviceGate 10분 TTL, moveToStorage 회피 이름, Thumb FFmpeg Semaphore(2), Janitor dirSize 30초 캐시+MediaStore 스캔, UUID/reorder/cache 정리, respondErr JSON 이스케이프, RSS use{}, OkHttp lazy 공유
- Phase D (#19): mockk 1.14.2 + kotlinx-coroutines-test 1.10.2 + turbine 1.2.0, 회귀 테스트 4건
- Phase E (#T-1049): WebAssets 파사드 분할(2859→8+WebDashboardHtml 2715+WebDebugHtml 146), SettingsScreen 섹션 분할(1236→68+Components/Server/General/Torrent/Service), READ maxSdk=32·WRITE maxSdk=29
- 문서: CHANGELOG Unreleased 19건+Phase E, TODO T-1045~T-1049, PLAN_v0.40, 세션 로그

## 3. 빌드 검증
- `./build_and_run.sh test android` → BUILD SUCCESSFUL (169 testcases, failures=0 errors=0)
- `./gradlew ktlintCheck --rerun-tasks` → BUILD SUCCESSFUL
- `assembleDebug` → BUILD SUCCESSFUL
- WebAssets JS `node --check` → OK (dashboard/debug 각 1 script)
- 실기기: 무선 `10.233.247.205:5555` 설치·런치 성공

## 4. 이슈·해결
- Gradle 병렬 데몬 간섭으로 간헐 `NoSuchFileException`/`EOFException`/daemon stopped — 데몬 정리 후 재실행으로 해소 (테스트 실패 아님)

## 5. 미완료
- `server.p12` git 이력 제거, `TLS_KEYSTORE_PASSWORD` BuildConfig 평문 (선행 세션 잔여)
- 부분 미사용 import (섹션 파일 공통 import 블록) — ktlint는 GREEN, 잔여 정리 후보

## 6. 커밋
- Phase A~D 안정성 19건 (43a8071) + Phase E 분할·권한 (이 커밋)

## 7. 다음
- 실기기 설정 탭·웹 대시보드 수동 스모크
- (후보) 섹션 파일별 import 정리

## 8. 상태
✅ Phase A~E 완료, test+ktlint+assemble+실기기 통과
