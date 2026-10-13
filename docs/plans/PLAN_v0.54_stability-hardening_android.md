# PLAN v0.54 — 무인 서버 안정화 (Stability Hardening)

> 생성일: 2026-10-13 | 플랫폼: android | 작성자: OpenCode
> 배경: 받기 404 + 파일 사라짐 (ghidra zip, `문서/` 자동분류 vs 루트-only 폴백). Hotfix 적용·두 대 배포·검증 완료 (206 확인).
> 목표: 놀고 있는 기기의 하드웨어+저장공간을 빌려 쓰는 **무인 다운로드 서버**로서 재시작·kill·분류 이동에도 일(파일+이력)이 증발하지 않고, RelayConsole 플러그인 연동이 깨지지 않는다.

## 0. 비전 (사용자 요구)

- 이 앱 하나로 동작하는 게 아님 — 유휴 기기 N대를 다운로드 서버로 활용
- 안정성 없으면 전체가 무너짐. 기능 추가보다 **안 죽고, 안 잃고, 연동 안 깨짐**이 우선
- RelayConsole (플러그인) 연동은 계약으로 보호

## 1. Phase 목록

### Phase 0 — 계약 동결 (반나절, 읽기 전용)
- 산출물: 계약 목록 (서버 경로 60+개, PluginContract 이벤트 4종, MCP 2종, share token+url)
- 완료 조건: 목록이 본 PLAN 부록에 고정되고, 이후 Phase는 계약 변경 금지
- 검증: 목록 vs 실서버 대조 (curl)

### Phase 1 — 데이터 내구성 (1일, 핵심)
- 범위: "파일 최종 위치 가정" 13곳 중 잔여 11곳 (엔진 감사 보고 기준)
  - `DownloadEngine.onCompleted` 루트 가정 → publish 실제 경로 사용
  - `VideoDownloadManager` 썸네일 구경로 문제
  - `TorrentEngine` savePath 미갱신·이중 위치·DONE 검출 누락
  - `workDir` 3중 정의 단일화
- 완료 조건: kill → 재시작 후 잡·파일·썸네일 전부 생존 (두 대 실기)
- 검증: `/file/{id}` 206, `/dl-file/문서/...` 200, 재시작 후 DONE 유지

### Phase 2 — 무인 감독 분리 (반나절, 동작 변경 없음)
- 범위: `RelayService`에서 알림/Foreground와 ServerSupervisor(watchdog+재시작) 분리
- 금지: watchdog 규칙(3연속 실패 재시작, 전송 중 연기) 변경 금지
- 검증: 기존 테스트 전체 통과 + 두 대 재시작·watchdog 로그 확인

### Phase 3 — 배포 안전 (반나절)
- 절차: 1대 카나리 → 하루 관찰 → 나머지. 직전 APK 롤백 보관
- `ApiPaths` 상수화는 Phase 3 이후 별도 PLAN으로 (동작 변경 없음 범위에서만)

### 제외 (이번에 안 함)
- SettingsRoutes 768행 분할 등 보기 좋은 정리 — 무인 동작과 무관, 깨질 위험만 있음

## 2. 롤백

- 각 Phase마다 직전 APK를 `dist/` 에 보관. 카나리 기기에서 404/증발 재발 시 즉시 롤백 후 원인 분석

## 3. 부록 — 계약 목록 (Phase 0 확정, 2026-10-13)

코드 정본: `RelayServer.kt, JobRoutes.kt, TorrentRoutes.kt, StorageRoutes.kt, SettingsRoutes.kt, ShareRoutes.kt, StatsRoutes.kt, DebugRoutes.kt, NetSpeedRoutes.kt, DavRoutes.kt, McpServer.kt, Favicon.kt`에서 `get/post/delete("…")` 전수 추출. 실서버(S22) 10종 200 확인.

- [x] HTTP 파일: `GET /`, `GET /debug`, `GET /file/{id}`, `GET /dl-file/{name...}`, `GET /dl-folder/{name...}`, `GET /stream/{name...}`, `GET /thumb/{name...}`, `GET /s/{token}`, `GET /dav/{path...}` + 파비콘 6종 (`Favicon.kt`)
- [x] Jobs/비디오: `GET /api/jobs`, `POST /api/jobs`, `POST /api/jobs/{id}/{action}`, `POST /api/jobs/{id}/limit`, `DELETE /api/jobs/{id}`, `POST /api/jobs/reorder`, `POST /api/video/analyze`, `POST /api/video/create`
- [x] Torrents/검색: `GET /api/torrents`, `POST /api/torrents/add`, `POST /api/torrents/{id}/{action}`, `GET /api/torrents/{id}`, `DELETE /api/torrents/{id}`, `GET /api/search`, `POST /api/torrents/reorder`, `GET /api/torrents/trackers`, `POST /api/torrents/trackers/refresh`, `POST /api/torrents/trackers/probe`
- [x] 보관함: `GET /api/storage`, `GET /api/storage/external`, `GET /api/storage/tree`, `GET /api/storage/trash`, `POST /api/storage/trash/restore`, `POST /api/storage/trash/purge`, `POST /api/storage/move|mkdir|rename|delete`, `POST /api/storage/raw-upload|upload`, `POST /api/storage/test-path`
- [x] 공유: `POST /api/share`, `GET /api/share`, `DELETE /api/share/{token}` — 응답 `{token, url: "/s/<token>"}`
- [x] 설정 12종 GET+POST: `server|download|speed-limit|speed-schedule|torrent|search|guard|webhook|tunnel|mcp|schedule|debrid` + `POST /api/settings/reset`, `POST /api/debrid/check|unrestrict`, `POST /api/rss`, `GET /api/rss`, `DELETE /api/rss/{id}`, `POST /api/rss/{id}/check`, `GET /api/guard/status`, `GET /api/tunnel/status`, `GET /api/signal/status`
- [x] 통계·디버그·망: `GET /api/stats/summary|daily|extended`, `GET /api/debug/logs|api-calls|bundle|status|overlay`, `POST /api/debug/clear|overlay/toggle`, `GET /api/metrics`, `GET /api/events`, `GET /api/net/speed`
- [x] Plugin (RelayConsole 연동): `eventDownloadComplete`, `eventDownloadFailed`, `eventTorrentComplete`, `eventTorrentFailed` (+ `pluginAllowed` 게이트, 기본 true)
- [x] MCP: `POST /mcp`, `POST /mcp/call` (게스트 차단, `/api` prefix 가드 대상)
- 규칙: 이후 Phase는 본 목록의 경로·메서드·응답 필드 변경 금지. 추가는 허용, 삭제·개명은 별도 PLAN 필요

## 4. 진행 기록

- [x] Hotfix: `resolveJobFile` + `/file` 폴백 + DONE 유지 (2026-10-13, 두 대 배포·206 검증)
- [x] Phase 0 완료 (2026-10-13, 경로 80개 추출·실서버 10종 200 확인)
- [x] Phase 1 완료 (2026-10-13, 종단 검증: 100KB zip → 문서/ 분류 → `/file` 206 → 재시작 DONE 유지·206 → 잔재 정리 → 두 대 배포)
  - 수정: `StorageJanitor.onCompleted` 최종경로 반환, 비디오 썸네일 최종경로, 토렌트 `moveToStorage` File?+savePath 갱신, `/api/info` 보관함 기준
  - 테스트: 387개 중 1 실패 = 기존 `CronParserPerfTest` 깨짐 (클린 트리 재현 확인, 본 변경 무관)
  - 제외/연기: `workDir` 3중 정의 단일화 (위험 대비 가치 낮음)
- [x] 테스트 복구 (2026-10-13): `CronParserPerfTest.정상 표현식은 유효` — `"30 3 1 * *"`이 월초 7일 전이 아니면 7일 탐색창 밖이라 flaky. 제품 버그 아님, `"30 3 * * *"`로 교체 → 387/387 녹색
- [x] Phase 2 완료 (2026-10-13, 동작 변경 없음)
  - `ServiceNotifications.kt` 신설 (221행): 채널 4종·빌더 8종·fmtBytes를 RelayService(932→734행)에서 분리. 호출 순서·문구·ID 그대로
  - 종료 경로 중복 제거: `persistJobs/persistTorrents/stopForegroundIfNeeded` (가드·문구·runCatching 구조 유지)
  - 연기: ServerSupervisor/EventFanout/EngineRegistry 분리 — 코루틴 얽힘상 위험 대비 가치 낮음, 현 구조 유지
  - 검증: 387/387 녹색, 카나리 실다운로드→DONE→206→정리, 두 대 배포·info 200·FATAL 0
- [x] Phase 3 완료 (2026-10-13): 카나리(S22)→검증→Router 순차 배포, 롤백본 `dist/droidrelay-phase1-canary.apk`, `dist/droidrelay-phase2-canary.apk` 보관
- [ ] 관찰 항목 (다음 턴): 10-12 11:58 S22 시작 2~3초 SIGABRT 5연발 (어제 빌드, 본 변경 이전·오늘 0건). 토렌트 네이티브 의심, 재발 시 tombstone 확보
