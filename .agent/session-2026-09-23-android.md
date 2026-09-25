# 세션 로그 — 2026-09-23 (android, 안정성 Phase A~D + Phase E + 포트 기본값)

## 1. 목표
안정성 조사 19건 Phase A~D 전수 수정 + Phase E (WebAssets·SettingsScreen 분할, 권한 축소) + 서버 기본값 정리

## 2. 수행
- Phase A (#1~#3): publishToDownloads 가드, Jobs/Torrent Persistence 원자 쓰기 (tmp→rename, .tmp 폴백)
- Phase B (#4~#8): tryStart TOCTOU 락, add 중복 URL 가드, RelayServer.start():Boolean, ScheduleJobService scope 재생성, MainActivity/BootReceiver 메인 I/O 제거
- Phase C (#9~#18): onTaskRemoved 유휴 종료, 429/5xx→RETRY, DeviceGate 10분 TTL, moveToStorage 회피 이름, Thumb FFmpeg Semaphore(2), Janitor dirSize 30초 캐시+MediaStore 스캔, UUID/reorder/cache 정리, respondErr JSON 이스케이프, RSS use{}, OkHttp lazy 공유
- Phase D (#19): mockk 1.14.2 + kotlinx-coroutines-test 1.10.2 + turbine 1.2.0, 회귀 테스트 4건
- Phase E (#T-1049): WebAssets 파사드 분할(2859→8+WebDashboardHtml 2715+WebDebugHtml 146), SettingsScreen 섹션 분할(1236→8+Components/Server/General/Torrent/Service), READ maxSdk=32·WRITE maxSdk=29
- **재시도 메시지 패치** (DownloadEngine): RUNNING/DONE 전환 시 errorMessage/errorCode 클리어, friendlyReason SocketException 매핑 — test+ktlint GREEN (미커밋)
- **다운로드 소실 사건**: 완전 재설치로 `.part`(4.47GB, 92%)·jobs.json 소멸 → opencode tool-output에서 MediaFire URL 복구(`.agent/recovered-url.txt`) → POST /api/jobs 재등록·resume. MediaFire IP throttle ~130KB/s (16-way 병렬無효), ETA ~10h
- **서버 기본값**: HTTP `8080`→`3000`, HTTPS 기본 **사용안함** (`DEFAULT_HTTPS_ENABLED=false`). SettingsConstraints/Repository/Migration/RelayServer/TunnelManager + 테스트·README·AGENTS·TUNNEL_GUIDE·CHANGELOG·error_message_ko 반영
- 실기기 즉시 적용: `POST /api/settings/server {port:3000, httpsEnabled:false}` — 3000=200, 8080/8443 닫힘, 다운로드 유지
- 문서: CHANGELOG Unreleased (안정성 19건+Phase E+포트 기본값), 세션 로그 갱신

## 3. 빌드 검증
- `:app:testDebugUnitTest` → 169 tests, failures=0 errors=0
- `:app:ktlintCheck` → EXIT 0
- 실기기: 무선 `10.233.247.205:5555`, 웹 `http://10.233.247.205:3000`

## 4. 이슈·해결
- Gradle 병렬 데몬 간섭으로 간헐 `NoSuchFileException`/`EOFException`/daemon stopped — 데몬 정리 후 재실행으로 해소 (테스트 실패 아님)
- 21:27 완전 재설치로 다운로드 데이터 소멸 — `install -r`만으로는 불가, uninstall 경로(서명 전환/수동 제거) 추정. URL은 tool-output에서 복구
- MediaFire free throttle: 맥 16-way=phone 1-way 동일(~130KB/s) — 멀티세그먼트 불가

## 5. 미완료
- `server.p12` git 이력 제거, `TLS_KEYSTORE_PASSWORD` BuildConfig 평문 (선행 세션 잔여)
- 부분 미사용 import (섹션 파일 공통 import 블록) — ktlint는 GREEN, 잔여 정리 후보
- **진행 중 `.part` uninstall 생존**: 완료분만 공용 Download/DroidRelay로 이동 → 재설치 전 확인 절차·공용 저장 검토
- MediaFire 다운로드 진행 중 (~4%, 137KB/s) — 완료 시 보관함 게시 확인

## 6. 커밋
- Phase A~D 안정성 19건 (43a8071) + Phase E 분할·권한 (c893ac7)
- **미커밋**: 재시도 메시지 패치 + 포트 기본값 3000/HTTPS 끔 + 문서 + 세션 로그

## 7. 다음
- 미커밋 변경 커밋 (사용자 확인 후)
- 다운로드 완료 확인 + `.part` 생존 개선
- (후보) 섹션 파일별 import 정리

## 8. 상태
✅ Phase A~E·포트 기본값·URL 복구 완료, test+ktlint+실기기 통과 / ⏳ 다운로드 진행 중

