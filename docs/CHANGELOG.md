# Changelog

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
