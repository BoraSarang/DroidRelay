# Changelog

## [0.10.2] - 2026-08-27

### Added [android+web]
- **웹 정보 바 리디자인**: 상단 요약바 좌/우 고정 레이아웃 — 좌측(가변) = 진행 건수 + 총 속도(대기 시 회색 "대기중..."), 우측(고정) = 저장공간 여유 · 온도/배터리 · 버전. 폰 온도·배터리 실시간 표시 (`/api/guard/status` 연동). 보호 영역 min-width 480px로 모바일 압축 방지
- **가드 상태 색상 구분**: 임계치 초과 시 `⚠ 스로틀링` 배지 + 빨강 강조, 가드 비활성 시 회색 "가드 끔", 정상 시 초록
- **RSS 자동 다운로드 확장**: 항목 URL이 `magnet:` 또는 `.torrent`면 일반 다운로드 대신 TorrentEngine(`addMagnet`/`.torrent 추가`)으로 라우팅
- **TUNNEL_GUIDE.md**: Tailscale/Cloudflare 터널 시나리오·현재 한계점·로드맵 문서 신설

### Changed [android+web]
- **보관함↔설정 디자인 통일**: 보관함 오른쪽 콘텐츠를 설정 콘텐츠 스타일(라운드 카드 `.sg` + 섹션 헤더 `.sh`)로 재구성 — "🗂 파일 관리" 액션 카드 + "📄 폴더 내용/🗑️ 휴지통" 목록 카드, 파일 행을 리스트 그룹 스타일로 변경 (구분선+호버 배경, 단일 행엔 구분선 생략)
- **RSS "지금 확인" 버튼 수정**: `/api/rss/0/check`가 "피드 없음"으로 무응답하던 버그 — id "0"을 전체 피드 확인의 특수값으로 허용
- **RSS 에러 표면화 개선**: HTTP 비 2xx(403 등)/HTML(Cloudflare JS 챌린지) 응답을 구분해 의미 있는 오류 메시지로 표시 (`HTTP 403 (Cloudflare/보안 챌린지 차단 가능)` 등), 브라우저 UA 채택, 실패 시에도 `lastCheckedAt` 갱신해 UI에 확인 시각 노출

### Fixed [android]
- **디버그 오버레이 토글 크래시**: `DebugOverlayService`가 `startForegroundService()`로 기동되면서도 `startForeground()`를 호출하지 않아(ForegroundServiceDidNotStartInTimeException) 프로세스가 즉시 종료되던 버그 — `startInForeground()` 추가 (`relay_status` 채널·`NOTIF_ID 2002`, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`)
- **오버레이 토글이 OFF 불가**: `/api/debug/overlay/toggle`이 항상 시작만 하고 중지는 불가능했음 — `DebugOverlayService.isRunning` 상태 기반 진짜 ON/OFF 토글로 수정
- **토글 응답 비동기 경합**: start/stop 직후 `isRunning` 플래그가 뒤늦게 변해 응답이 뒤집히던 것(`running` 반전) — 요청 시점의 `wasRunning` 기준 결정적 응답으로 수정
- **오버레이 권한 안내 문구 오타**: `权限이 없습니다`(중문) → `권한이 없습니다`(국문)로 교정
- **RSS 자동 다운로드 미동작 (enclosureUrl 빈 문자열)**: enclosure 태그가 없는 항목에서 `enclosureUrl`이 빈 문자열(`""`)이라 `?:`(null 전용)가 link로 대체하지 않아 다운로드 URL이 공백이 되던 버그 — `enclosureUrl?.takeIf { it.isNotBlank() } ?: link`로 수정. 정상 공개 피드 + 로컬 테스트 피드(magnet/`.torrent`/일반 URL 3종)로 E2E 검증 완료

## [0.10.0] - 2026-08-27

### Added [android+web]
- **Debrid 클라우드 다운로드 연동 (Phase 1.3)**: Real-Debrid / AllDebrid / Premiumize 공통 클라이언트. 설정 탭 > Debrid 섹션에서 API 키 입력, 계정 확인, 제공자 선택. `POST /api/debrid/unrestrict`로 언리스트링크 변환 → 고속 다운로드. 다운로드 추가 시 자동 적용
- **MCP 서버 내장 (Phase 2.1)**: JSON-RPC 2.0 프로토콜, `/mcp` + `/mcp/call` 엔드포인트. 도구: file_list, file_read, download_add, download_list, download_control. AI 에이전트 연동 지원
- **MCP 권한 설정 (Phase 2.1 확장)**: 도구별 ON/OFF + 프라이버시 모드 (file_read 차단). `GET/POST /api/settings/mcp`
- **웹훅/콜백 API (Phase 2.2)**: 다운로드 완료/실패 시 POST 콜백, HMAC-SHA256 서명, 지수 백오프 재시도(최대 3회), 데드레터 큐. `GET/POST /api/settings/webhook`
- **터널 매니저 (Phase 2.3)**: Tailscale / Cloudflare Tunnel 상태 관리, Tailscale 앱 감지 + IP 자동 탐지. `GET/POST /api/settings/tunnel` + `GET /api/tunnel/status`
- **가드 데몬 (Phase 2.4)**: 열/배터리/스토리지 임계치 모니터링 (30초 폴링). 임계치 초과 시 자동 다운로드 일시정지, 정상 복귀 시 재개. 설정 탭 > 가드 섹션에서 임계치 조정, 현재 상태 확인
- **스케줄/조건부 다운로드 (Phase 3 확장)**: 크론 표현식 파서 (5필드, step/comma/range 지원) + Wi-Fi/충전/배터리 제약. `GET/POST /api/settings/schedule`. JobScheduler 기반 자동 실행
- **외장 스토리지 자동 감지 (Phase 3 확장)**: USB OTG / SD카드 / 외장 SSD 마운트 감지, 여유 공간 표시, 권장 다운로드 경로 제안. `GET /api/storage/external`
- **메트릭스 API (Phase 3)**: `/api/metrics` — 서버 가동시간, 다운로드/토렌트 통계, 바이트 처리량, 실시간 속도. Prometheus/Grafana 연동 준비
- **설정 사이드바 확장**: Debrid(☁️), 가드(🛡), MCP(🤖), 스케줄(⏰), 스토리지(💾) 메뉴 추가 — 10개 섹션

### Changed [android+web]
- **다운로드 엔진 Debrid 통합**: POST /api/jobs 시 Debrid 활성화 상태면 자동 언리스트링크 → 고속 다운로드 URL로 변환 후 큐잉
- **설정 데이터 모델 확장**: AppSettings에 debridEnabled/debridProvider/debridApiKey, guardEnabled/guardThermalLimit/guardBatteryLimit/guardStorageLimit, webhookEnabled/webhookUrl/webhookSecret, tunnelEnabled/tunnelProvider 필드 추가
- **RelayService 가드 연동**: 가드 데몬 시작 + 상태 변경 시 다운로드 자동 일시정지/재개
- **디버그 모드 로그 증강 (Phase 3)**: McpServer(도구 호출/실행/완료 시간), TunnelManager(설치/인터페이스/IP 감지), StorageDetector(마운트/여유공간/bestPath), GuardDaemon(센서 수치 30초 주기), DebridClient(API 요청/응답), SchedulerManager(크론 파싱/제약/JobScheduler 등록 결과), ScheduleJobService(잡 ID/제약 결과), RelayService(컴포넌트 시작/정리) 전 구성요소 상세 로그 추가

### Added [android+web] (디버그 패널)
- **웹 디버그 패널 (별도 페이지)**: `GET /debug` — 독립 창으로 실시간 로그 모니터링 (1초 폴링), 로그/API 호출 탭, 레벨·태그·텍스트 필터, 일시정지, 복사, 내보내기
- **디버그 API 세트**: `GET /api/debug/logs`, `GET /api/debug/api-calls`, `GET /api/debug/status`, `POST /api/debug/clear`, `GET /api/debug/overlay`, `POST /api/debug/overlay/toggle`
- **API 호출 자동 기록**: Call 파이프라인 인터셉터 — 모든 `/api/*` 요청의 method/path/status/MS 기록 (`DebugLogger.api()` 링 버퍼 300줄)
- **설정 사이드바 "🐛 디버그" 섹션**: 디버그 패널 열기 버튼 + 실시간 상태 표시 + 오버레이 권한/토글

### Added [android] (플로팅 오버레이)
- **디버그 플로팅 오버레이**: `DebugOverlayService.kt` — `TYPE_APPLICATION_OVERLAY` 투명 오버레이. 최근 로그/API 호출 실시간 표시, 드래그 이동, 탭 시 일시정지, 1초 폴링
- **`SYSTEM_ALERT_WINDOW` 권한 + 서비스 등록**: AndroidManifest에 추가. 설정에서 오버레이 권한 요청 + 시작/중지 제어

## [0.9.0] - 2026-08-27

### Added [android+web]
- **시퀀셜 다운로드 지원**: 토렌트 설정 > 고급에 "시퀀셜 다운로드 (스트리밍 프리뷰)" 토글 추가. 활성화 시 첫 번째 조각부터 순서대로 다운로드하여 미디어 파일 재생 미리보기 지원
- **RSS/Atom 피드 자동 다운로드**: 설정 탭에 RSS 피드 관리 카드 추가. 피드 URL 등록, 키워드/정규식 필터, 15분 주기 자동 폴링, 매칭 시 자동 다운로드. `GET/POST/DELETE /api/rss` + `POST /api/rss/{id}/check`
- **PLAN_v0.9_android.md**: "Server Edition" 로드맵 문서 — MCP 서버, 가드 데몬, 터널링, 웹훅, RSS 피드, Debrid 연동 계획

### Changed [android]
- **TorrentEngine 시퀀셜 모드**: `TorrentFlags.SEQUENTIAL_DOWNLOAD` 플래그 적용 — magnet/torrent 파일 추가 시 설정에 따라 자동 적용, 기존 토렌트도 실시간 전환

## [0.8.0] - 2026-08-27

### Added [android+web]
- **설정 탭 UI 전면 재설계**: 4개 풀폭 카드(전역 속도 제한/다운로드 설정/토렌트 설정/기본값 복원), 서브그룹 분리(속도/연결/고급/포트/경로), 일관된 CSS 클래스 체계(.sg/.sh/.sr/.si/.sl/.sv/.ck/.sb/.ti/.ss/.ft/.fp/.rb), 인라인 스타일 제거
- **토렌트 엔진 기본값 권장값 적용**: 동시 2, 업로드 512KB/s, 시드비율 2.0, 랜덤 포트(49152~65535), 기본 저장 경로 /sdcard/Download/DroidRelay, DHT/PEX 활성화
- **경로 쓰기 권한 테스트**: `POST /api/storage/test-path` — 웹에서 경로 입력 후 즉시 테스트 가능

### Changed [android+web]
- **설정 API 부분 업데이트 보장**: `has()` 가드로 미지정 필드 보존(불리언 리셋 버그 수정)
- **전역 속도 제한 토스트 피드백**: 모든 설정 변경 시 저장 확인 토스트 표시(기존 토렌트만)

### Removed [macos]
- **macOS 네이티브 앱 완전 삭제**: apps/macos/, docs/DESIGN_v1.0_macos.md, docs/TODO_v1.0_macos.md, docs/plans/PLAN_v*_macos.md, docs/screenshots/macos/, .agent/session-*_macos.md, ~/Applications/DroidRelayClient.app, Xcode DerivedData — 웹 전용 아키텍처로 전환

## [0.7.1] - 2026-08-26

### Fixed [android]
- **토렌트 추가 직후 시드·피어·진행률 0 지연**: 매핑 성공 즉시 `forceReannounce`+`forceDHTAnnounce` 킥 추가(수동 매핑·폴링 자동 매핑 모두), 트래커 발표 전까지 통계 0으로 고정되던 문제 해소
- **파일 토렌트 매핑 레이스**: `delay(300)` 후 단 1회 `session.find()` → 최대 5초 재시도 루프로 교체
- **조각 정보 API 부재**: `/api/torrents`에 `piecesDone`/`piecesTotal` 신설(`pieceInfo()` = totalDone/pieceLength 기반)

### Added [macos]
- **토렌트 행 조각 표시**: 🧩 n/m 배지 추가(piecesDone/piecesTotal), 시드·피어 헬프툴팁
- **로컬 받기 진행률 실시간 표시**: `URLSession.download`(콜백 없음) → `bytes(for:)` 스트리밍 + 256KB 청크 쓰기, 0.25초 간격 진행률 갱신, Content-Length 기반 전체 크기, `.part` 이어받기 개선
- **편집 메뉴 누락 수정**: 커스텀 메인 메뉴에 편집 메뉴(⌘Z/⌘X/⌘C/⌘V/⌘A) 추가 — 없으면 모든 TextField 붙여넣기 불가

## [0.7.0] - 2026-08-26

### Added [macos]
- **메인 윈도우 사이드바 리디자인**: NavigationSplitView 5섹션(개요/다운로드/토렌트/보관함/설정), 개요 탭 통계 카드 4종 + 빠른 액션, 기본 창 1120×700
- **보관함 보기 전환**: 리스트/그리드 토글(AppStorage 기억, 기본 리스트)
- **보관함 파일→폴더 드래그 이동**: 행/카드 onDrag + 폴더 드롭 타깃 하이라이트, `POST /api/storage/move` 연동
- **다운로드·토렌트 드래그 순서 변경**: 잡/토렌트 행 드래그 → `/api/jobs/reorder`·`/api/torrents/reorder` 연동(순서 정렬 모드에서만 드래그 허용)
- **메뉴바 아이콘**: 백업 AppIcon.icns → StatusBarIcon.imageset(16x16@1x/2x, 템플릿 렌더링)

### Fixed [macos]
- **NSPanel 초기화 오류**: `NSPanel(contentView:)` 미존재 → contentRect 이니셜라이저 + `.contentView` 대입으로 수정(WindowFactory·DebugPanelWindow)
- **SwiftUI onDrop 오버로드 불일치**: macOS 26 SDK에서 `onDrop(of:isTargeted:delegate:)` 부재 → `onDrop(of:delegate:)` 형태로 전면 수정
- **접근 제어 충돌**: AppState `pollOnce`/`requireAPI` private → internal, MainView 중복 requireAPI 확장 제거
- **재귀 뷰 opaque type 에러**: StorageView 폴더 트리 재귀 → AnyView 타입소거

## [0.6.0] - 2026-08-26

### Added [android+web]
- **웹 SSE 실시간 푸시 (T-701)**: `/api/events` text/event-stream 1초 tick → 웹 즉시 갱신, 끊기면 1초 폴링 폴백 + 5초 재연결
- **다중 URL 일괄 추가 (T-702)**: 웹 입력에서 줄바꿈/공백/쉼표 구분 여러 URL 순차 추가, 결과 집계 토스트
- **보관함 휴지통 (T-703)**: 삭제 시 `.trash/` 이동(삭제 실수 방지), 복구(보관함 루트로), 개별 영구삭제, 전체 비우기 API + 웹 UI
- **체크섬 검증 (T-704)**: 다운로드 추가 시 선택 SHA-256 입력(64자리 검증), 완료 후 스트리밍 digest 비교 — 일치 시 ✓검증됨 배지, 불일치 시 FAILED `E-AND-DOWN-1005`
- **웹 폴더 트리 사이드바 (T-608)**: `/api/storage/tree` (깊이 3, .trash 제외) + 보관함 탭 2단 레이아웃, 클릭 탐색 + 현재 경로 하이라이트

### Added [macos]
- **다운로드 추가 시트**: 다중 URL(줄바꿈/공백 구분) + SHA-256 선택 입력 필드, 단일 URL에만 체크섬 적용

### Fixed [android]
- **HTTP 다운로드 전면 차단 버그**: Android cleartext 정책으로 `http://` URL 다운로드 실패 → `usesCleartextTraffic="true"` 추가
- **일시정지 진행바 0% 리셋 회귀 (T-601 재발 방지)**: 앱 재시작 시 복원된 작업의 progress가 0으로 표시 → `JobsRepository.restore()`에서 `downloadedBytes/totalBytes` 재계산 (근본 수정)
- **대용량 업로드 멈춤**: Ktor CIO multipart 63MB 버퍼 정체 → `POST /api/storage/raw-upload` 스트리밍 엔드포인트 신설 (196MB 11초), 맥 클라이언트 전환

## [0.5.0] - 2026-08-25

### Added [macos]
- **macOS 네이티브 클라이언트**: DroidRelayClient 메뉴바 앱 (MenuBarExtra .window)
- **서버 자동 스캔**: 실행 시 LAN /24 서브넷 동시 프로브 (128 동시, 0.7초 타임아웃)
- **수동 서버 입력**: IP:port 직접 입력으로 연결 (설정에 저장)
- **다운로드 관리**: URL 추가 → 잡 목록 → 진행률/속도 표시 → 일시정지/재개/삭제
- **⬇ 받기**: 완료된 파일을 로컬 저장 폴더로 스트리밍 (Range 이어받기, .part 임시파일)
- **토렌트 관리**: magnet 링크 입력 + .torrent 파일 추가 → 목록 표시 + 제어
- **보관함 탐색**: 서버 /sdcard/Download/DroidRelay 폴더/파일 브라우징
- **보관함 관리**: 폴더 생성, 이름 변경, 잘라내기/붙여넣기(이동), 삭제, 파일 업로드
- **설정**: 저장 폴더 지정, 자동 스캔 토글, Basic Auth 인증, 로그인 시 실행(SMAppService), 메뉴바 속도 표시
- **디버그 로그 창**: os.Logger + 500줄 순환 버퍼, 전체 복사
- **앱 아이콘**: CoreGraphics + iconutil 생성 (다운로드 화살표 디자인)
- **테스트**: Models JSON 디코딩 + Discovery URL 생성 + 포맷 계산 등 10건

## [0.4.0] - 2026-08-25

### Added [android]
- **Torrent 클라이언트**: libtorrent4j 기반 torrent 다운로드 엔진 추가
- **magnet 링크 지원**: magnet:?xt=... 직접 입력 → 다운로드
- **.torrent 파일 지원**: 파일 선택 → torrent 추가
- **4탭 내비게이션**: 다운로드 / Torrent / 파일 / 설정
- **Torrent 탭 UI**: torrent 목록, 상태 표시, 일시정지/재개/삭제
- **대역폭 제한**: 다운로드 무제한 / 업로드 0KB/s (기본, 설정에서 변경 가능)
- **DHT/PEX**: 분산 해시 테이블 + 피어 교환 지원
- **웹 대시보드 Torrent API**: /api/torrents, /api/torrents/add, /api/torrents/{id}/*, DELETE
- **Torrent 알림**: torrent 완료/실패 시 알림 (별도 채널)
- **Torrent 설정**: 업로드/다운로드 속도, 최대 활성 torrent, 시드 ratio, DHT/PEX 설정
- **Torrent 상태 영구 저장**: torrent.json 기반 저장/복원
- **GitHub Releases**: APK 자동 빌드/배포

## [0.3.0] - 2026-08-25

### Added [android]
- **네트워크 복구 자동 재시작**: ConnectivityManager 감지 → FAILED 작업 자동 재시도 (에어플레인 모드/네트워크 끊김 후 자동 복구)
- **클라이언트 접속 범위 설정**: 같은 핫스팟만 (기본) / 암호만 있으면 / 승인만 — 설정 화면에서 선택
- **시작/완료 시간 표시**: Job.startedAt/finishedAt 필드 + Persist 저장
- **네트워크 타입 실시간 표시**: Wi-Fi / LTE / 5G / 3G / 2G 감지 → 서버 카드에 표시 (5초 폴링)
- **서버 주소 공유**: 공유 버튼 → QR 이미지+텍스트 Intent → 카카오톡/문자/이메일 등 모든 앱으로 전송
- **확장자별 아이콘·색상**: 🎬비디오(빨강) 🎵음악(보라) 🖼이미지(파랑) 📄문서(주황) 📦압축(초록) ⚙실행(회색)
- **파일 이름변경**: 수정 아이콘 → 다이얼로그 → MediaStore 업데이트
- **삭제 확인 다이얼로그**: 삭제 전 AlertDialog 확인 → MediaStore 삭제
- **배지 제어**: 서버실행 알림은 배지 OFF, 다운로드 완료/실패 알림은 배지 ON

### Changed [android]
- AndroidManifest.xml: ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, READ_PHONE_STATE 권한 추가
- FileProvider 설정 (QR 이미지 공유용 cache 디렉토리)
- 알림 채널 분리: relay_status (배지 OFF) + relay_result (배지 ON)

## [0.2.0] - 2026-08-25
- MD3 디자인 시스템·설정·보안·다운로드 매니저 고도화 (커밋 5956cfc)

## [0.1.0] - 2026-08-25
- 초판 (커밋 7574486)
