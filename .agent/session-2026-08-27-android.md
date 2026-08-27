# 세션 로그 2026-08-27 (Android + Web) — v0.10.1 디버그 시스템 완료 + v0.10.2

## 세션 요약
- **v0.10.1**: 디버그 시스템 부트스트랩 전면 구현 + 로그 증강 ✅ 빌드·설치·API 검증 완료
- **v0.10.2**: 웹 정보 바 리디자인(온도/배터리/대기중) + RSS 진단/버그수정 + 보관함↔설정 디자인 통일 + TUNNEL_GUIDE.md ✅
- **구현 범위**: DebugLogger 확장, 디버그 API 세트, 웹 디버그 패널(별도 페이지), API 호출 트래킹, 안드로이드 플로팅 오버레이, 8개 컴포넌트 로그 증강
- **빌드**: BUILD SUCCESSFUL (2회), 기기 설치 완료, 모든 디버그 API 응답 검증
- **PERF/CACHE**: 디버그 로그 링 버퍼 300줄 유지, API 호출 버퍼 별도 분리(동일 300줄)

## 구현 내역

### DebugLogger 확장
- MAX_LINES=300 유지, `api()`/`apiLines()`/`apiDump()`/`apiCount()` 신설
- API 호출 버퍼는 buf와 별도 격리 (로그 300줄 압박 방지)

### 디버그 API 세트 (RelayServer.kt)
- `GET /api/debug/logs?lines=&level=&tag=&text=` (필터 지원)
- `GET /api/debug/api-calls?limit=` / `GET /api/debug/status`
- `POST /api/debug/clear` / `GET /api/debug/overlay` / `POST /api/debug/overlay/toggle`
- API 호출 자동 기록: `intercept(ApplicationCallPipeline.Call)` + 명시적 `proceed()` 후 status 캡처 (`call.request.httpMethod` 접근 불가 → method "?" 표기)

### 웹 디버그 패널 (/debug)
- 독립 HTML 페이지 (`WebAssets.debugHtml`), 다크 테마, 1초 폴링
- 로그/API 호출 탭, 레벨·태그·텍스트 필터, 일시정지/재개, 복사, 내보내기(파일 저장)
- 설정 사이드바 "🐛 디버그" 섹션 — 패널 열기 + 상태 표시

### Android 플로팅 오버레이
- `DebugOverlayService.kt` — TYPE_APPLICATION_OVERLAY, 드래그 이동, 탭 시 일시정지, 1초 폴링
- `SYSTEM_ALERT_WINDOW` 권한 + AndroidManifest 서비스 등록
- 설정에서 권한 요청 + 시작/중지 토글

### 디버그 로그 증강 (8개 컴포넌트)
- McpServer: 요청진입/도구실행/완료+소요시간/권한 차단
- TunnelManager: 설치 감지 결과, 인터페이스 개수, Tailscale IP 매칭, 상태 조회 상세
- StorageDetector: getExternalFilesDirs 개수, 마운트 발견(경로/여유GB), bestPath 결과
- GuardDaemon: 30초마다 센서 수치 (thermal/battery/storage vs 임계치)
- DebridClient: API URL 호출, 응답 code/bodyLen/소요시간, 계정 확인
- SchedulerManager: 크론 파싱, 각 제약 설정 여부, JobScheduler 등록 결과(0=실패), 제약 체크 상세
- ScheduleJobService: 잡 ID, 제약 결과, 스케줄 건너뜀 사유
- RelayService: 컴포넌트 초기화/시작/정리 로그

## v0.10.2 — 정보 바 + RSS 진단 + 디자인 통일

### 웹 정보 바 리디자인 (WebAssets.kt)
- 좌/우 고정 레이아웃: 좌(가변) = 진행 N건 + 총 속도(0 시 회색 "대기중..."), 우(고정) = 저장공간 여유 · 온도·배터리(`/api/guard/status`) · 버전
- 가드 상태 색상: `temp-ok` 초록 / `temp-warn`+`⚠ 스로틀링` 빨강(45°C 경계 오르내리며 실시간 전환 확인) / `temp-off` 회색 "가드 끔"
- `.wrap{min-width:480px}` 모바일 보호, `.info-left/.info-right/.info-item(throttle-badge)` 클래스 신설, `updateInfoBar()` + `refresh()` 가드 페치

### RSS 진단 (사용자 보고: "응답이 없다던가")
- **원인 2가지 발견**: ① 웹 "지금 확인" 버튼이 `/api/rss/0/check`로 id=0 → "피드 없음" 무응답 버그, ② LimeTorrents가 Cloudflare JS 챌린지로 403 + 기존에는 오류가 "항목 없음"으로 삼켜짐
- **수정**: `/api/rss/{id}/check`에서 id "0" = 전체 확인 허용 / `fetchFeed`가 비2xx(403 힌트)·HTML 응답(챌린지) 구분해 throw / 브라우저 UA + Accept 헤더 / 오류 시에도 `lastCheckedAt` 갱신
- **검증**: 피드 추가 → "지금 확인" 클릭 → `마지막: ... | 항목: 0` + `⚠ HTTP 403 (Cloudflare/보안 챌린지 차단 가능)` UI 표시, 디버그 로그 `RssFeedMgr` 에러 경로 확인
- **자동 다운로드 확장**: RSS 항목 URL이 `magnet:`/`.torrent`면 Torn... `RelayApp.getTorrent(ctx).addMagnet/addTorrentFile` 라우팅 (fetchTorrentFile 헬퍼 신설)

### 보관함↔설정 디자인 통일
- 보관함 오른쪽 콘텐츠를 설정 스타일로: 카드 1 ".sg 최대 관리" + ".sh", 카드 2 "폴더 내용/휴지통"(동적 제목) + 파일 리스트 그룹(구분선/호버배경, 단일 행 시 last-child 구분선 없음)
- `.storage-main`(flex column gap 12px) 래퍼 + `#fileList .file-row` 리스트 그룹 오버라이드. 트리(좌)는 이미 설정 내비 스타일
- 카드/행 마크업 검증: sg bg #101E3A radius 14px, 행 pad 16px 내부 정렬 확인

### TUNNEL_GUIDE.md
- Tailscale/CF 시나리오·API 사용법·현재 한계(바이너리 미번들/웹 UI 섹션 없음/자동시작 없음)·트러블슈팅·로드맵

### 오버레이 토글 E2E (실기기 SM-S901N, USB)
- **발견 버그 2개**: ① `startForegroundService()`인데 `startForeground()` 미호출 → ForegroundServiceDidNotStartInTimeException으로 **앱 프로세스 자체가 크래시** (pid 소멸 확인), ② 토글 API가 시작만 하고 OFF 불가
- **수정 (DebugOverlayService.kt + RelayServer.kt)**: `startInForeground()` 신설(relay_status 채널·`NOTIF_ID 2002`·`FOREGROUND_SERVICE_TYPE_DATA_SYNC` 재사용), `@Volatile isRunning` 상태 기반 진짜 ON/OFF 토글, 응답은 요청 시점 `wasRunning` 기준 결정화(비동기 플래그 경합으로 응답이 뒤집히던 것 수정), JS 오타 `权限이`→`권한이`
- **E2E 흐름**: `appops set SYSTEM_ALERT_WINDOW allow`(adb) → `GET /api/debug/overlay` `{"hasPermission":true}` → 토글 ON `{"running":true}` → dumpsys: ServiceRecord 3건 + `ty=APPLICATION_OVERLAY (0,100)(fillxwrap) gr=TOP START` + 프로세스 생존 → 토글 OFF `{"running":false}` → 서비스 0건 + 오버레이 윈도우 제거 + 프로세스 생존
- **시각 검증**: ON/OFF 스크린샷 상단(100~300px) 평균색 — ON `(23,32,47)` 어두운 청색(오버레이 bg 0xE60A1428 혼합) / OFF `(35,37,43)` 앱 배경. 오버레이 로그 시그니처 `[17:37:55.184][I][DebugOverlay] 오버레이 서비스 시작` 확인
- 산출물: `docs/screenshots/android/v0.10.1_overlay_on.png` / `_off.png`

## 검증 산출물
- `curl /api/debug/status` → logCount=51, apiCallCount=27 (정상 기록)
- `curl /api/debug/logs` → 가드 센서 체크(thermal=47°C), 스케줄러 크론+제약+JobScheduler result=1, MCP 도구 실행 로그 확인
- `curl /api/debug/api-calls` → `? /api/info → 200` 정상 기록
- MCP tools/call → JSON-RPC 정상 응답 + 상세 로그 (요청진입 138ms)
- /storage/external → StorageDetector 상세 로그 + bestPath=null (외장 미연결 정상)

## 문서
- CHANGELOG.md: v0.10.1 디버그 시스템 + v0.10.2(정보 바/RSS/디자인 통일/TUNNEL_GUIDE/오버레이 토글 수정) 기록
- TODO.md: T-821~T-829 + T-830~T-837 등록 + 전부 완료
- TUNNEL_GUIDE.md 신설

## 주요 변경 파일
- **변경**: DebugLogger.kt, RelayServer.kt (디버그 API + 인터셉터 + RSS id=0 허용 + 오버레이 토글), WebAssets.kt (debugHtml·디버그 섹션·정보 바·보관함 카드 구조·오타 수정), McpServer.kt/TunnelManager.kt/StorageDetector.kt/GuardDaemon.kt/DebridClient.kt/SchedulerManager.kt/ScheduleJobService.kt/RelayService.kt (로그 증강), RssFeedManager.kt (에러 표면화 + magnet/.torrent 라우팅), AndroidManifest.xml (오버레이), DebugOverlayService.kt (startForeground + isRunning + stop)
- **신규**: TUNNEL_GUIDE.md, docs/screenshots/android/v0.10.1_*.png+a11y dump

## 남은 TODO
- 터널 웹 UI 설정 섹션 (TUNNEL_GUIDE.md 로드맵 — 사용자 승인 필요)
- curl은 403 처리 확인됨 — 실제 Non-Cloudflare RSS 피드로 정상 파싱/자동 다운로드 E2E (예: 정상 공개 피드)
- 커밋 (아직 미커밋 — 사용자 확인 후)

## 큐 상태
- 없음

## E2E
- 디버그 API 전체 통과 확인
- 오버레이 토글 E2E 통과 (권한 adb 부여 → ON→OFF, 크래시 없음, 시각 확인)