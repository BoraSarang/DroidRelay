# TODO — DroidRelay

## v0.3 (2026-08-25)

| 항목 | 내용 | 상태 |
|------|------|------|
| T-201 | 네트워크 복구 자동 재시작 (NetworkMonitor + retryFailed) | ✅ |
| T-202 | 클라이언트 접속 범위 설정 (SUBNET_ONLY/ANY_WITH_PASSWORD/APPROVED_ONLY) | ✅ |
| T-203 | 강제종료 시 즉시 jobs.json 저장 (onTaskRemoved + onDestroy) | ✅ |
| T-204 | 시작/완료 시간 표시 (Job.startedAt/finishedAt) | ✅ |
| T-205 | 네트워크 타입 실시간 표시 (Wi-Fi/LTE/5G) | ✅ |
| T-206 | 접속 범위 설정 UI (SettingsScreen 필립칩) | ✅ |
| T-207 | 확장자별 아이콘·색상 구분 (FilesScreen) | ✅ |
| T-208 | 서버 주소 공유 (QR+텍스트 Intent) | ✅ |
| T-209 | 파일 이름변경 + 삭제 확인 다이얼로그 | ✅ |
| T-210 | 배지 제어 (서버실행 OFF / 완료 ON) | ✅ |
| T-211 | 크래시 수정 (ACCESS_NETWORK_STATE + try-catch) | ✅ |
| T-212 | 설정 서버 섹션 리디자인 (3줄 압축 + 랜덤 포트 + 상태표시) | ✅ |
| T-213 | 설정 탭 verticalScroll (하단 잘림 해결) | ✅ **사용자 확인 완료** |

## v0.4 (2026-08-25) — Torrent 클라이언트

| 항목 | 내용 | 상태 |
|------|------|------|
| T-301 | PLAN 문서 + libtorrent4j 의존성 추가 | ✅ |
| T-302 | TorrentEngine 클래스 (세션 관리) | ✅ |
| T-303 | TorrentRepository (상태 관리) | ✅ |
| T-304 | TorrentPersistence (JSON 영구 저장) | ✅ |
| T-305 | magnet 링크 입력 → 다운로드 | ✅ |
| T-306 | .torrent 파일 업로드 → 다운로드 | ✅ |
| T-307 | torrent 정보 표시 (이름/크기/파일목록/시드/피어) | ✅ |
| T-308 | 대역폭 제한 (다운로드 무제한/업로드 0KB/s) | ✅ |
| T-309 | Torrent 탭 UI (4탭 내비게이션) | ✅ |
| T-310 | 설정 탭 Torrent 섹션 | ✅ |
| T-311 | 웹 대시보드 Torrent API | ✅ |
| T-312 | Torrent 알림 (진행률 + 완료) | ✅ |
| T-313 | 테스트 + ktlint + 크래시 검증 | ✅ |
| T-314 | 스크린샷 + CHANGELOG + 커밋 | ✅ |
| T-315 | GitHub Release + APK 배포 | 🔄 |

## v0.5 (2026-08-25) — macOS 네이티브 클라이언트 (메뉴바)

| 항목 | 내용 | 상태 |
|------|------|------|
| T-401 | project.yml(xcodegen) + 디렉터리 골격 | ✅ |
| T-402 | Models + RelayAPI (18 엔드포인트 클라이언트) | ✅ |
| T-403 | DebugLog (os.Logger + 순환버퍼) | ✅ |
| T-404 | ServerDiscovery (/24 서브넷 스캔) | ✅ |
| T-405 | AppState (폴링 1초, 자동재연결) | ✅ |
| T-406 | TransferManager (Range 이어받기 + 완료 알림) | ✅ |
| T-407 | MenuBarExtra 팝오버 + 다운로드/토렌트/보관함 3탭 UI | ✅ |
| T-408 | Settings (주소/자동스캔/저장폴더/BasicAuth/로그인실행/속도표시) | ✅ |
| T-409 | 앱 아이콘 생성 스크립트 → icns | ✅ |
| T-410 | XCTest (Models 파싱, Range, Discovery) | ✅ |
| T-411 | build_and_run.sh macos 확장 + ~/Applications 배치 | ✅ |
| T-412 | CHANGELOG + 세션로그 + 커밋 | ✅ |

## v0.2 아카이브
T-101~T-115 전부 완료 (커밋 5956cfc).

## v0.1 아카이브
T-001~T-008 전부 완료 (커밋 7574486).

## 후속 후보 (v0.4)
- [x] 웹 대시보드 SSE 실시간 푸시
- [x] 다중 URL 일괄 붙여넣기
- [x] Content-Disposition 파일명 우선
- [ ] iPad Safari 실기기 검증

## v0.5 긴급 수정 + UX (2026-08-25, T-501~T-512)
| ID | 작업 | 상태 |
|----|------|------|
| T-501 | 다운로드 115% 버그 (이중 카운트) — curBytes=filePointer + clamp | ✅ |
| T-502 | 재시도 사유 표시 (friendlyReason + errorMessage) | ✅ |
| T-503 | 웹 드래그 전면 수리 (따옴표 버그→이벤트 위임, 카드 순서 드래그, 폴링 억제) | ✅ |
| T-504 | 보관함 move 자기참조 소실 버그 — rename우선+크기검증+원본보존 | ✅ |
| T-505 | 경로 탈출 차단 전면 적용 (storageFile 헬퍼 — list/mkdir/rename/delete/move/upload/dl-file) | ✅ |
| T-506 | 토렌트 속도 설정→TorrentEngine 연결 (미연결 버그) + 프리셋 5단계 + 0=업로드끔/다운무제한 | ✅ |
| T-507 | 동시 다운로드 기본값 2→1 | ✅ |
| T-508 | 웹 📥 이모지 + 다운로드 토스트 + <a download> 원복 | ✅ |
| T-509 | 맥 하단 도크 (다운로드 목록/디버그/설정) + WKDownload 진행 연결 | ✅ |
| T-510 | 맥 우클릭 메뉴 + 종료 + ⌘Q + ~/Applications 배포 | ✅ |
| T-511 | 맥 WKUIDelegate (prompt/confirm/alert/파일선택창 — 폴더생성·업로드 수정) | ✅ |
| T-512 | RelayServer 리팩토링 (respondOk/Err 헬퍼, storageFile/safeLeafName) | ✅ |

## v0.6 후속 UX (2026-08-26, T-701~T-704)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-701 | 웹 SSE 실시간 푸시 (/api/events + EventSource, 폴링 폴백) | ✅ |
| T-702 | 다중 URL 일괄 붙여넣기 (공백/줄바꿈 분할 순차 추가) | ✅ |
| T-703 | 보관함 휴지통 (.trash 이동 삭제/복구/영구삭제/비우기) | ✅ |
| T-704 | 체크섬 검증 (SHA-256 선택 입력, 불일치 E-AND-DOWN-1004) | ✅ |

## v0.7 후속 (2026-08-26, T-708~T-710)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-708 | 맥 앱 다운로드 추가 시트 (다중 URL + SHA-256 필드) | ✅ |
| T-709 | 웹 보관함 폴더 트리 사이드바 (/api/storage/tree + 2단 레이아웃) | ✅ |
| T-710 | 대용량 업로드 raw 스트리밍 (/api/storage/raw-upload, 196MB 11초) + cleartext 허용 | ✅ |

## v0.10 (2026-08-27) — Server Edition Phase 1.3~3

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-801 | DebridClient 공통 인터페이스 (RealDebrid/AllDebrid/Premiumize) | ✅ |
| T-802 | Debrid 설정 API (GET/POST /api/settings/debrid) | ✅ |
| T-803 | Debrid 언리스트링크 API (POST /api/debrid/unrestrict) + 계정 확인 | ✅ |
| T-804 | Debrid 설정 UI (제공자 선택, API 키 입력, 계정 확인 버튼) | ✅ |
| T-805 | 다운로드 엔진 Debrid 통합 (POST /api/jobs 시 자동 언리스트링크) | ✅ |
| T-806 | MCP 서버 내장 (JSON-RPC 2.0, /mcp + /mcp/call, 5개 도구) | ✅ |
| T-807 | 웹훅 매니저 (HMAC-SHA256, 지수 백오프, 데드레터 큐) | ✅ |
| T-808 | 터널 매니저 (Tailscale/CF Tunnel 상태 관리) | ✅ |
| T-809 | 가드 데몬 (열/배터리/스토리지 30초 폴링, 자동 일시정지/재개) | ✅ |
| T-810 | 가드 설정 UI (임계치 슬라이더, 상태 확인) | ✅ |
| T-811 | 메트릭스 API (/api/metrics — 가동시간/통계/속도) | ✅ |
| T-812 | 설정 데이터 모델 확장 (Debrid/Guard/Webhook/Tunnel 필드) | ✅ |
| T-813 | CHANGELOG + 세션 로그 갱신 | ✅ |
| T-814 | MCP 권한 설정 (도구별 ON/OFF + 프라이버시 모드) | ✅ |
| T-815 | 크론 파서 (5필드, step/comma/range) | ✅ |
| T-816 | 스케줄러 매니저 (JobScheduler + Wi-Fi/충전/배터리 제약) | ✅ |
| T-817 | 외장 스토리지 감지 (USB OTG/SSD 마운트 → 경로 제안) | ✅ |
| T-818 | 터널 상태 강화 (Tailscale 앱 감지 + IP 자동 탐지) | ✅ |
| T-819 | 설정 UI 3개 섹션 추가 (MCP/스케줄/스토리지) | ✅ |
| T-820 | API 4개 신규 (mcp/schedule/tunnel-status/storage-external) | ✅ |

## v0.10.1 (2026-08-27) — 디버그 시스템 부트스트랩

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-821 | DebugLogger 확장 — 링 버퍼 300줄 + api()/apiLines()/apiDump()/apiCount() | ✅ |
| T-822 | 디버그 API 세트 — logs/api-calls/status/clear | ✅ |
| T-823 | 웹 디버그 패널 (/debug 별도 페이지 + 1초 폴링 + 탭/필터/복사/일시정지) | ✅ |
| T-824 | API 호출 자동 기록 (Call 파이프라인 인터셉터, proceed() 후 status 캡처) | ✅ |
| T-825 | 설정 사이드바 "🐛 디버그" 섹션 (패널 열기 + 오버레이 권한/토글) | ✅ |
| T-826 | Android 플로팅 오버레이 서비스 (TYPE_APPLICATION_OVERLAY, 드래그/일시정지) | ✅ |
| T-827 | SYSTEM_ALERT_WINDOW 권한 + DebugOverlayService 인증 등록 | ✅ |
| T-828 | 디버그 로그 증강 — MCP/Tunnel/Storage/Guard/Debrid/Scheduler/ScheduleJob/Service 8개 컴포넌트 | ✅ |
| T-829 | 빌드 + 설치 + 전체 디버그 API 검증 + 문서 갱신 | ✅ |

## v0.10.2 (2026-08-27) — 정보 바 + RSS 진단 + 디자인 통일

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-830 | 웹 정보 바 리디자인 — 좌(진행 건수/총 속도 or 대기중) 우(저장공간/온도·배터리/버전) 고정 레이아웃, .wrap min-width 480px | ✅ |
| T-831 | 가드 상태 표시 — temp-ok/temp-warn(+⚠ 스로틀링)/temp-off(가드 끔) 색상 구분 | ✅ |
| T-832 | RSS "지금 확인" 버튼 버그 수정 — /api/rss/0/check 전체 피드 확인 허용 (기존 "피드 없음" 무응답) | ✅ |
| T-833 | RSS 에러 표면화 — HTTP status/HTML(Cloudflare 챌린지) 감지 세분화, 오류 시에도 lastCheckedAt 갱신 | ✅ |
| T-834 | RSS 자동 다운로드 확장 — magnet:/ .torrent URL을 TorrentEngine으로 라우팅 | ✅ |
| T-835 | 보관함↔설정 콘텐츠 디자인 통일 — .sg/.sh 카드 구조 + 파일 리스트 그룹 스타일 | ✅ |
| T-836 | TUNNEL_GUIDE.md 작성 (Tailscale/CF 시나리오·한계점·로드맵) | ✅ |
| T-837 | 오버레이 토글 E2E — 크래시 수정(누락된 startForeground) + 진짜 ON/OFF 토글 + 응답 결정화, 실기기에서 권한→ON→OFF 시각 검증 | ✅ |
| T-838 | 정상 RSS 피드 자동 다운로드 E2E — magnet/.torrent/일반 URL 3종 라우팅 검증 중 enclosureUrl 빈 문자열이 `?:`를 무력화해 URL이 공백이 되던 버그 발견·수정 | ✅ |
| T-839 | 터널 웹 UI 설정 섹션 — 🔗 터널 사이드바 메뉴 + 토글/제공자/저장/상태 UI, E2E 저장·상태·프로바이더 전환 검증 + TUNNEL_GUIDE 갱신 | ✅ |

## v0.11 (2026-08-27) — 배터리/성능 최적화

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-840 | PLAN_v0.11_perf_android.md 작성 (핫스팟 조사: WakeLock/jobs 디스크 쓰기/폴링/방출) | ✅ |
| T-841 | WakeLock 완전 제거 (Foreground Service 유지) | ✅ |
| T-842 | jobs.json 저장 실질 디바운스 10초 (무디바운스 은닉 버그 수정) | ✅ |
| T-843 | 알림 갱신 700ms→2000ms | ✅ |
| T-844 | 다운로드 진행 틱 400ms→2000ms | ✅ |
| T-845 | 토렌트 상태 폴링 1초→5초 + 저장 실변경 가드 | ✅ |
| T-846 | Repository 동일값 스킵 (before==after 시 refresh 생략) | ✅ |
| T-847 | RssFeedManager 누수 수정 (필드 보관 + onDestroy stop) | ✅ |
| T-848 | 가드 데몬 30초→120초 + settings 5분 캐시 (WorkManager 미도입) | ✅ |
| T-849 | DebugOverlay 이중 startInForeground 제거 + 폴링 3초 | ✅ |
| T-850 | 빌드·설치·E2E 검증 (WakeLock 부재/저장 빈도) + CHANGELOG·세션 로그·커밋 | ✅ |
| T-851 | ThrottleInterceptor 이중 래핑 버그 수정 — 다운로드 0Byte 즉시 완료 (한국어: 전역 속도제한 설정 시 엔진 다운로드 무조건 실패) | ✅ |
| T-852 | DownloadEngine.fmt() MB 제수 오류 수정 — GiB 제수(1_073_741_824) 사용으로 1MiB 이상이 10.2배 작게 표기 | ✅ |
| T-853 | 가드 온도 임계치 30~60 → 50~70°C 확장 (기본값 45→50) — SettingsRepository(기본/로드/setter)·RelayServer 검증·WebAssets 슬라이더·GuardDaemon 주석 | ✅ |
| T-854 | 목록 우측 버튼 크기 통일 — 다운로드/토렌트 `.card-acts` 112px 픽스+버튼 100%, 보관함 `.acts` 아이콘 34px, box-sizing 통일. CDP 좌표로 받기/삭제 112px·아이콘 34px 확인 | ✅ |

## v0.11.1 (2026-08-27) — HTTPS 다운로드

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-855 | 웨일 "안전하지 않은 다운로드" 회피 — mkcert 자체 서명 인증서 + Ktor 엔진 **CIO→Netty 전환**(CIO는 HTTPS 미지원)+ HTTPS 8443 이중 커넥터. 웹 다운로드 링크를 `https://<host>:8443` 절대 경로로 전환, `/dl-file`에 nosniff·cache-control 헤더 추가. HTTP/HTTPS 동일 바이트(MD5 일치) 검증, 맥은 최초 1회 TLS 경고 후 정상 사용(CA 등록은 선택·미사용) | ✅ |
| T-856 | HTTP→HTTPS 리다이렉트(307) — LAN 클라이언트의 `http://…:8080` 접속을 `https://…:8443`로 이동. loopback(localhost/127.0.0.1/자기 IP)은 예외(터널 tailscaled·앱 자체 점검 보호). 맥에서 307→HTTPS follow 200 + ISO 전체 수신, 기기 loopback 200 확인 | ✅ |

## v0.13 (2026-08-28) — 비디오 다운로드 개선 (재요청 · MP4 직접 · 해상도/파일명)
> 유튜브 제거 후 남은 스트림 기능을 사용자 요구 3종으로 개선(PLAN_v0.13_stream_download.md). PageKit(네이버 상품 MP4 직접·토렌트씨 스트림 파싱) 동작을 OkHttp 정규식 기반으로 구현.

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-885 | PLAN v0.13 작성 + TODO 등록 | ✅ |
| T-886 | StreamDetector — MP4 직접(.mp4/임베디드 src/og:video/인라인 JS file 키) + m3u8/mpd 마스터 매니페스트 variant(해상도) 파싱(순수 함수, TDD) + Found.kind/qualities | ✅ |
| T-887 | video 재요청 — 재시도는 UI(웹 retryVideo·앱 JobCard)에서 VideoApi.create(url) 재호출(재분석, 부분 재개 불가) + E-AND-VID-0205 | ✅ |
| T-888 | API(analyze qualities / create variantUrl·filename) + 웹 UI(WebAssets 해상도 선택·파일명·FAILED video 재시도 버튼) | ✅ |
| T-889 | 앱 Compose UI(DownloadsScreen 해상도 선택·파일명 입력·JobCard video 재시도 분기) | ✅ |
| T-890 | 검증(ktlint·assembleDebug·testDebugUnitTest·node --check) + 실기기 E2E(네이버 MP4·토렌트씨 스트림해상도·재시도) + CHANGELOG/error_message_ko.json/세션 로그 + 커밋 | |

## v0.13.1 (2026-08-28) — 스트림 진행률 · 배지 · 한글 파일명 · 팝업 5종
> 동일 커밋 `feat/android-v013-stream-progress`로 5종 해결. 진행률 신호는 FFmpegKit LogCallback(불안정) 대신 **-progress 파일/out_time** 기반으로 전환(실기기 회귀 발견).

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-891 | 스트림 진행률 — StreamDetector 재생시간(EXTINF 합·마스터 첫 variant 팔로우) + Job.totalDurationMs + VideoDownloadManager `-progress` 파일 poll(`parseOutTimeUs`) + computeProgress 우선순위 + VideoApi 전달 + /api/jobs 응답 + 웹 UI(재생시간%·남은시간·용량) — 실기기 1→100% 단조 증가·DONE/63.9MB 검증 | ✅ |
| T-892 | 배지 정렬 — 웹 .badges 그룹화(state+video 배지 한 줄 정렬) | ✅ |
| T-893 | 파일명 한글 깨짐 — safeFilename 한글 보존 회귀 테스트 + 실기기 `한글파일명_테스트.mp4` JSON 보존 확인(회귀 테스트로 확정) | ✅ |
| T-894 | confirm/prompt 제거 → 팝업 레이어 + 폴더/파일 rename 팝업 — makeOverlay/confirmPopup/promptPopup/delJobConfirm, 웹 9곳 confirm/prompt 제거 | ✅ |
| T-895 | 검증 — ktlint + testDebugUnitTest 10건 GREEN + assembleDebug + WebAssets JS node --check + 실기기 E2E 2회 완주 + 문서/세션 로그 + 커밋 | ✅ |
| T-896 | 모바일 대응 — @media(max-width:640px) 미디어 쿼리 추가: wrap min-width 해제, 정보바 세로, 카드 액션 아래 재배치, 보관함 tree 숨김, 설정 사이드바→상단 가로 스크롤 칩, 입력 행 wrap | ✅ |
| T-897 | 모바일 후속 — 보관함/휴지통 가로 100%(`.storage-main width:100%`), 설정 메뉴 오른쪽 여백(`.settings-nav padding/border-box`), 카드 액션 삐져나감(`.card-acts box-sizing:border-box`), 서브타이틀 문구 축약, 폴링 GET(`/api/info`,`/api/jobs`,`/api/torrents`,`/api/guard/status`) 디버그 로그 억제(`pollExempt`) | ✅ |
| T-898 | 공개 배포 — 릴리즈 keystore+keystore.properties(gitignore)+build.gradle signingConfig 연결, versionName 0.13.3, 서명 APK 빌드+실기기 설치(서명 SHA-256 `2f1dc84a…` 확인), README+GitHub Pages 랜딩(docs/index.html), main merge+tag v0.13.3+gh release | ✅ |

## v0.12.2 (2026-08-28) — YouTube/yt-dlp 지원 전면 제거
> yt-dlp 방식은 유튜브 PoToken/봇가드 등 정책 변화로 막힐 위험이 커 **완전 폐기** — 폰은 외부 서버 없이 스트림(m3u8/mpd) 다운로드로 독립 동작.

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-882 | 유튜브/yt-dlp 코드 제거 — YtDlpClient.kt 삭제, SettingsRepository(ytdlp 3필드), /api/settings/ytdlp GET/POST, VideoApi 유튜브 분기·formatId 제거 → 스트림 단일 경로, VideoDownloadManager 0203 분류 제거 | ✅ |
| T-883 | 유튜브 UI 제거 — SettingsScreen '비디오 (YouTube)' 섹션, DownloadsScreen 유튜브 포맷 바텀시트, WebAssets yt-dlp 설정 섹션·연결테스트·analyzeVideo 포맷 UI, 에러코드(0102/0203 제거, 0101 문구 스트림 전용) | ✅ |
| T-884 | ytdlp_server.py·__pycache__ 삭제 + 문서(CHANGELOG 0.12.2, TODO, PLAN_v0.12) 유튜브 잔여 정리 + ktlint/assembleDebug + 스트림 회귀 검증 | ✅ |

## v0.12 (2026-08-27) — 비디오 다운로드 (범용 스트림 · 유튜브 제외 결정)
> 유튜브: NewPipeExtractor 최신(0.26.5)+visitor_id 주입+ANDROID_VR 스푸핑까지 시도했으나 PoToken+통신사 LTE NAT IP 평판 차단으로 실사용 불가 → **기능 제외**, m3u8/mpd 직접 경로만 제공.

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-857 | PLAN_v0.12_video_download.md 작성 (사용자 확정: 파워 m3u8 + FFmpeg 내장 + Play 미배포) | ✅ |
| T-858 | 의존성 — NewPipeExtractor 0.26.5 + desugar_nio + ffmpeg-kit https:8.1.7(full은 TLS 미포함 확인→https로 교체) · 빌드 게이트 + FFmpeg 스모크 | ✅ |
| T-859 | ~~YouTube 추출기~~ — 취소 (2026 유튜브 PoToken/IP 차단, 우회 시도 후 제외) | ❌ |
| T-860 | VideoDownloadManager — FFmpeg 실행·진행률·취소·MediaStore 게시 + Job(type) 통합 | ✅ |
| T-861 | StreamDetector — 웹페이지 m3u8/mpd 스니핑 + 직접 입력 병행 | ✅ |
| T-862 | API 2종(POST /api/video/analyze, /create) + 웹 UI(분석→다운로드) | ✅ |
| T-863 | 앱 Compose UI (v0.12.1 T-875~877로 구현 완료) | ✅ |
| T-864 | 실기기 E2E(m3u8 원본 copy) — Mux HLS 162MB DONE+ffprobe 무결성, 제거 후 회귀 스모크(분석→생성→진행→취소)·error_message_ko.json·세션 로그 | ✅ |
| T-865 | 유튜브 코드 제거 — TubeEngine.kt 삭제, RelayServer analyze/create 유튜브 분기 제거, newpipe/JitPack 의존성 제거, WebAssets placeholder·에러코드 정리, PLAN/TODO/CHANGELOG 갱신 | ✅ |
| T-866 | 307 리다이렉트 커밋 분리 완료 (`af29009`) | ✅ |

### v0.12.1 (2026-08-28) — yt-dlp 서버 연동 + UX 정리
| ID | 작업 | 상태 |
|----|------|------|
| T-867 | ~~yt-dlp 서버(외부 실행) 통합~~ — ❌ **0.12.2에서 전면 폐기**(유튜브 PoToken/봇가드 불안정) | ❌ |
| T-868 | ~~yt-dlp 서버 /proxy 스트리밍 프록시~~ — ❌ **0.12.2에서 전면 폐기** | ❌ |
| T-869 | ~~YouTube 다운로드 원인 규명~~ — ❌ **0.12.2에서 전면 폐기** | ❌ |
| T-881 | ~~0203 차단 원인 반영 — 직링크 1회 재시도 + 클라이언트 정합~~ — ❌ **0.12.2에서 전면 폐기** | ❌ |
| T-870 | SHA-256 검증 기능 제거(사용자 요청) — Job 필드/영속/DownloadEngine 검증·sha256()/jobs API·웹 UI 입력·배지 전부 제거. 웹훅 서명(sha256=)은 유지. HTTP 50MB 다운로드 0→100% 진행 갱신 검증 | ✅ |
| T-871 | 토렌트 단일 파일 보관함 이동 수정 — moveToStorage isDirectory 가드로 단일 파일 스킵되던 버그, 파일/디렉토리 분기 처리 | ✅ |
| T-872 | 비디오 진행률 실시간화(TICK_MS 2000→1000 + StatisticsCallback) + 웹 정보바에 토렌트 활동 반영(대기중 허위 표시 해소, FETCHING_METADATA 속도 표시) | ✅ |
| T-873 | RelayService 기동 실패 실제 예외 표시("포트 이미 사용 중" 하드코딩 제거) — adb reverse로 8080 점유했던 원인 규명 문서화 | ✅ |
| T-874 | 문서 갱신 — CHANGELOG v0.12.1, TODO, PLAN_v0.12, error_message_ko.json(0101/0102/0203), 세션 로그 + 커밋 | ✅ |
| T-875 | **앱 Compose 비디오 UI(T-863 승계)** — DownloadsScreen '🎬 비디오' 섹션(URL 분석→제목 확인), ~~유튜브~~·스트림 원본 copy 다운로드, JobCard 🎬 배지 *(유튜브 포맷 시트는 0.12.2 T-883에서 제거)* | ✅ |
| T-876 | ~~SettingsScreen '비디오 (YouTube)' 설정~~ — ❌ **0.12.2 T-883에서 제거** | ❌ |
| T-877 | 공용 VideoApi 추출 + RelayServer analyze/create 핸들러 리팩터(422+코드/메시지 응답 통일) + **진행 폴링 전환**(StatisticsCallback이 `-c copy`에서 무감응 → 파일 크기 1초 폴링, SessionState 종료 감지) — 실기기 스트림 0→206MB 진행·254KB/s 실측 | ✅ |
| T-878 | 실기기 검증(스트림 분석→다운로드→진행률) + CHANGELOG/TODO/세션 로그 + 커밋 — *(유튜브 검증은 0.12.2에서 폐기)* | ✅ |
| T-879 | **실패 알림 반복+무의미 재시도 루프 차단** — RelayService 실패 알림을 상태 전이 시 1회로 + 동일 원인(에러코드+메시지) 재발신 금지, DownloadEngine.retryFailed에서 `type=="video"` 제외. 실기기 2초 무한 알림 소멸 확인 + HTTP 404 FAILED 알림 **1회만**(커밋 `df41ba6`) | ✅ |
| T-880 | ~~**웹 UI 유튜브 포맷/해상도 선택**~~ — ❌ **0.12.2 T-883에서 제거**(포맷 선택 UI 폐기, 스트림 "원본 그대로" 단일 버튼 유지) | ❌ |

## v0.14 (2026-08-31) — 안정성 7종 + 웨일 HTTPS 접속 복구 (PLAN_v0.14_stability_android.md)
> 실사용 중 보고된 7개 이슈. 실기기 검증(이슈7·웨일 접속·토렌트 409 등)은 사용자 직접 진행 예정.

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-899 | 부팅 자동시작(BootReceiver + RECEIVE_BOOT_COMPLETED) + 서버 watchdog(RelayService 1분 헬스체크→restart, `watchdogIntervalSec`) | ✅ |
| T-900 | 토렌트 교차 매핑 레이스 해결 — infohash 정확 일치 우선 + 빈 hash FIFO 폴백, addMagnet 중복 가드, `/api/torrents/add` 중복 409 + 웹 alert | ✅ |
| T-901 | 속도제한 오버플로우 방지 — `*1024 .toInt()` → `coerceIn(0, Int.MAX_VALUE)` | ✅ |
| T-902 | 시더 부재 자동 중단 — 피어 progress% 표시 + `torrentMinSeedWaitSec`(기본 0=꺼짐) 경과 후 pause | ✅ |
| T-903 | 보관함 업로드 진행률 — XHR `upload.onprogress` + raw-upload 스트리밍, 대상 폴더 라벨 | ✅ |
| T-904 | 보관함 폴더 날짜 표시(`modified`) — 웹 + FilesScreen | ✅ |
| T-905 | HTTPS 리다이렉트 호스트 보정(lanAddress 우선) + `forceHttpsRedirect`(기본 false) 도입으로 웨일/크롬 자체서명 차단 회피(HTTP 폴백) | ✅ |
| T-906 | **보관함 다운로드 비영문 파일명 깨짐** — Content-Disposition RFC 6266(`filename*=UTF-8''`) 적용(/dl-file·serveFile) + 웹 dlBase 프로토콜 자동 보정 + `<a download>` 파일명 힌트 + DispositionHeader 단위 테스트 | ✅ |

## v0.15 (2026-08-31) — 비디오 분석 403 조기 노출 + fetch 최적화 (PLAN_v0.15_analyze-403_android.md)
> wowstream2 m3u8 진단에서 발견: `parseManifestVariants`/`resolveSegmentsCount`/`mediaDurationMsFromUrl`이 403을 삼키고, `VideoApi.create`가 같은 403 URL을 4~5회 재fetch하며 조용히 실패. "분석 성공 → 다운로드 실패" 혼동 원인.

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-907 | **403/차단 즉시 전파** — `parseManifest` 신설(실패 삼킴 제거), fetch 403 → `E-AND-VID-0206`, 직접/검출 매니페스트·페이지 스니핑 경로 적용 | ✅ |
| T-908 | **fetch 중복 제거** — analyze 매니페스트 1회(+마스터 첫 variant 1회), `Found.durationMs` 추가, `VideoApi.create`가 동일 스트림이면 재사용 | ✅ |
| T-909 | **에러 문구** — `error_message_ko.json` E-AND-VID-0206 신설 + WebAssets analyze 오류 안내 보강 | ✅ |
| T-910 | **테스트** — 로컬 HttpServer 스텁(403 전파·미디어·마스터 variant 팔로우) + 풀 게이트(unit/ktlint/node/assembleRelease) | ✅ |
| T-911 | v0.15.0(versionCode 16) 릴리즈 인스톨 + CHANGELOG + 세션 로그 | ✅ |
| T-912 | **웹 비디오 분석 주소 초기화** — 다운로드(추가)·재시도 성공 시 `#vurl` 입력란 비움(`clearVideoUrlInput`) | ✅ |
| T-913 | **업로드 대상 라벨 제거** — "대상: 📁 폴더명"(`#uploadTargetLabel`) 요소·갱신 코드 삭제 (파일 올리기 동작과 정보 중복) | ✅ |
| T-914 | **비디오 카드 UX 압축** — 스트림 주소 길게 표시 제거 → 📋 주소 복사 버튼, 해상도 라디오 → `<select>`, 파일명/복사/다운로드를 한 줄 flex로 | ✅ |
| T-915 | **0206 차단 안내 1회·단문화** — analyze/create/retry 실패 시 💡 안내 1개만(짧은 문구), fetch/error_message_ko.json 문구 단축 | ✅ |

> v0.15 실기기 동작 검증(403 사이트 0206 즉시 안내 + 정상 m3u8 회귀)은 **사용자 직접** 진행 예정.

## v0.16 (2026-08-31) — 앱 설정 미러 1차 + MD3 디자인 개편 (PLAN_v0.16_settings-mirror_android.md)
> 웹 대시보드 설정에만 있고 앱에 없는 항목 중 **1차(전역 속도 제한·토렌트 고급·가드)**를 앱 설정 화면에 추가(백엔드 필드/setter는 이미 완비, UI만). 이후 MD3 전면 정돈(디자인 개편)로 확장.

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-916 | PLAN_v0.16 작성 + TODO 등록 + v0.16.0 versionCode 18 | ✅ |
| T-917 | **전역 속도 제한** — SettingsScreen '다운로드' 섹션에 다운로드/업로드 Mbps 스위치+슬라이더(1~10) → `setMaxDownloadBps/setMaxUploadBps`(0=무제한) | ✅ |
| T-918 | **토렌트 고급** — SettingsScreen Torrent 섹션에 시퀀셜 다운로드 switch / 시더 부재 대기(초) / 리슨 포트(+랜덤) / 저장 경로(+테스트) | ✅ |
| T-919 | **가드 섹션** — 활성화 switch + 열/배터리/스토리지 임계 슬라이더 + watchdog 주기(초) + HTTP→HTTPS 강제 switch | ✅ |
| T-920 | v0.16.0 검증(unit/ktlint/assembleRelease) + 실기기 인플레이스 설치 | ✅ |
| T-921 | v0.16.1 MD3 디자인 개편 — TopAppBar 도입·디버그 패널 이전·다운로드 단일 LazyColumn·Torrent 중첩 Scaffold 제거·카드색 통일·이모지→아이콘·Theme 표면 토큰·공용 DebugPanel | ✅ |
| T-922 | **웹 설정 미러 2차 — 스케줄** — 활성화 스위치 + Cron 입력+적용(`CronParser.isValid` 유효성 표시) + Wi-Fi/충전 중만 스위치 + 최소 배터리 슬라이더(5~100) → `setSchedule*` | ✅ |
| T-923 | **2차 — Debrid** — 활성화 스위치 + 제공자 FilterChip(REALDEBRID/ALLDEBRID/PREMIUMIZE) + API 키 저장 → `setDebrid*` | ✅ |
| T-924 | **2차 — 터널** — 활성화 스위치 + 제공자 FilterChip(TAILSCALE/CLOUDFLARE) → `setTunnelEnabled/setTunnelProvider` | ✅ |
| T-925 | **2차 — MCP** — 프라이버시 스위치 + 도구 5종 활성화(file_list/file_read/download_add/download_list/download_control) → `setMcpPrivacyMode/setMcpToolDisabled` | ✅ |
| T-926 | **2차 — 기본값 복원** — 다운로드/토렌트/전체 버튼 + 경고 AlertDialog → 서버 reset과 동일 조합 + RelayApp 즉시적용 | ✅ |
| T-927 | v0.16.2(versionCode 20) 검증(unit/ktlint/assembleRelease) + 실기기 인플레이스 설치 + CHANGELOG/TODO 갱신 | ✅ |
| T-928 | **다운로드 QR 확대/축소 토글** — QR 탭 → 전체 화면 반투명 오버레이에 확대 표시(scale 모션), 화면 아무 곳/닫기 버튼 재클릭 시 축소. qr 비트맵 1회 캐시 재사용 | ✅ |
| T-929 | **FGS 크래시 루프 수정(v0.16.4)** — 백그라운드 재시작 시 `ForegroundServiceStartNotAllowedException` → `startInForeground()` try-catch + onStartCommand 재시도 + start() 안전화. 배터리 최적화 예외 UI(상태+무제한 허용 요청 버튼) 추가 | ✅ |
| T-930 | **libtorrent JNI 직렬화 게이트(v0.16.6)** — ReentrantLock sessionGate로 모든 세션/핸들 접근 직렬화 + alert 콜백 tryLock(300ms) → GC가 netty 스레드를 정리하지 못해 크래시 지속 확인 | ✅ ⚠️ |
| T-931 | **alert.handle() dangling root fix(v0.16.6)** — `AddTorrentAlert.handle()`은 alert C++ 객체 내부 메모리 참조(swigCMemOwn=false, GC 후 dangling) → `handleMap` 장기 보관을 `session.find(hash)` 기반 독립 heap 카피로 교체. 실기기 동일 magnet 재현 테스트 20회 연속 200 + 프로세스 생존 | ✅ |
| T-932 | **웹 토렌트 업로드 32KB/s 선택** — 웹 설정 `torrentUploadLimit` 슬라이더 step 64 → 32 (앱 SettingsScreen 프리셋에 맞춘 동일 단위) | ✅ |
| T-933 | **Phase1 긴급 버그** — 웹 toast 미정의·`||`falsy 0소실·전역슬라이더 바인딩·키스토어PW BuildConfig·DeviceGate 타임아웃 (PLAN_v0.17) | ✅ |
| T-934 | **Phase2 T-931 후속 안정화** — 3종 alert transient 명시·FINISHED IO 게이트 밖·register/unregisterMapping·session 체크 게이트 안·seedWait 정리 | ✅ |
| T-935 | **Phase3 설정 단일화** — SettingsConstraints 단일 진실·앱 슬라이더 통일·Repo 기본값 512/2·reset 상수화·웹 kblabel | ✅ |
| T-936 | **Phase4 구조 분리** — RelayServer 1897→519 (Torrent/Job/Settings/Storage/DebugRoutes+StorageGuard)·WebAssets 중복삭제·apiGet/apiPost·num/kblabel·에러코드 8종 등록 | ✅ |
| T-937 | **웹 토렌트 삭제 UX + 잔존 정리** — 삭제 컨펌('목록에서 삭제+파일 동반안내')·버튼 앱 통일(추출중 일시정지·실패 재개·시딩 삭제만)·cancel() infohash 잔존 디렉토리 정리 | ✅ |
| T-938 | **보관함 폴더 다운로드** — `GET /dl-folder/` ZIP 실시간 스트리밍 + 웹 폴더행 📦 버튼 | ✅ |
| T-939 | **폴더 다운로드 속도 개선** — `setLevel(0)` 패스스루 + 256KB 버퍼 + 계측 로그 (PLAN_v0.17_dl-folder-speed) | ✅ |

## v0.18 (2026-09-07) — 담기↔소비 완성 (PLAN_v0.18_media-share_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-940 | **브라우저 직접 재생** — `GET /stream/` Range(206) + 보관함 ▶ 버튼 + video 오버레이 | ✅ |
| T-941 | **Share Intent 받기** — ACTION_SEND URL/magnet 수신 → 기존 등록 경로 | ✅ |
| T-942 | **토렌트 파일 선택** — 목록 조회 + file_priority API + 웹/앱 체크박스 UI | ✅ |
| T-943 | **검증·문서** — test/lint/assembleDebug + 실기기 E2E + CHANGELOG + 커밋 | ✅ |

## v0.19 (2026-09-07) — 외부 재생 + 자동 운영 (PLAN_v0.19_dav-quota_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-944 | **WebDAV 읽기** — PROPFIND+GET Range, VLC 연동 | ✅ |
| T-945 | **보관함 쿼터** — 상한 초과 시 오래된 파일 자동 휴지통 | ✅ |
| T-946 | **자동 분류** — 확장자별 폴더 자동 이동 | ✅ |
| T-947 | **중복 감지** — 동일 URL/magnet 등록 차단·안내 | ✅ |
| T-948 | **검증·문서** — test/lint/assembleDebug + 실기기 E2E + CHANGELOG + 커밋 | ✅ |

## v0.20 (2026-09-07) — 미리보기·검색·공유 (PLAN_v0.20_thumb-search-share_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-949 | **영상 썸네일** — FFmpeg 추출+캐시+목록 미리보기 | ✅ |
| T-950 | **토렌트 검색** — Jackett/Prowlarr Torznab 연동 | ✅ |
| T-951 | **만료 공유 링크** — 토큰+유효시간 발급/다운로드 | ✅ |
| T-952 | **게스트 읽기전용** — 열람·다운로드만 허용 | ✅ |
| T-953 | **위젯/퀵타일** — 서버 토글 | ✅ |
| T-954 | **검증·문서** — test/lint/assembleDebug + 실기기 E2E + CHANGELOG + 커밋 | ✅ |

## v0.21 (2026-09-07) — 설정 감사 + 업로드 최소화 (PLAN_v0.21_settings-audit_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-955 | **설정 감사** — 53필드 전수, 데드 4종 확정 | ✅ |
| T-956 | **시드 비율 강제** — 도달 시 자동 일시정지 | ✅ |
| T-957 | **DHT 토글 배선** — start/stopDht 반영 | ✅ |
| T-958 | **저장 경로 배선** — 설정값 사용 | ✅ |
| T-959 | **PEX 안내 + 업로드 최소화 프리셋** | ✅ |
| T-960 | **검증·문서** — test/lint/assembleDebug + 실기기 E2E + CHANGELOG + 커밋 | ✅ |

> v0.16.0(설정 1차)·v0.16.1(디자인 개편) 실기기 **직접 확인 대기**(사용자). 이후 **2차 확장** 예약: 스케줄(ScheduleRepository) / Debrid / 터널 / MCP / 기본값 복원. RSS CRUD는 별도 저장소(2차와 분리 검토).

## v0.22 (2026-09-12) — Phase A 안정성 기반 (PLAN_v0.22_phaseA_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-961 | PLAN 작성 + TODO 등록 + 버전 상수 설계 | ✅ |
| T-962 | SettingsMigration + Repository 배선 + 테스트 6건 | ✅ |
| T-963 | PersistenceGuard + Jobs/Torrent 백업 + 테스트 4건 | ✅ |
| T-964 | DebugBundle + /bundle 라우트 + status 확장 + 테스트 3건 | ✅ |
| T-965 | 검증(3종 게이트) + CHANGELOG + 세션 로그 | ✅ |
| T-966 | 실기기 E2E (마이그레이션/손상복구/번들) | ✅ |

## v0.23 (2026-09-12) — Phase B 성공률·복원력 (PLAN_v0.23_phaseB_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-967 | PLAN 작성 + TODO 등록 | ✅ |
| T-968 | P0-3 코어+라우트 (ExtraHeaders/VideoApi/JobRoutes/E-AND-VALID-0002) + 테스트 6건 | ✅ |
| T-969 | P0-3 UI (웹 Referer/Cookie + 앱 2필드, 메모리만) | ✅ |
| T-970 | P1-4 파일명 매트릭스 (safeFilename+DispositionHeader) + 테스트 6건 | ✅ |
| T-971 | P1-6 코어+엔진 (TrackerListProvider/설정/주입) + 테스트 5건 | ✅ |
| T-972 | P1-6 UI+API (trackers 2종 + 웹/앱 토글) | ✅ |
| T-973 | 검증(3종 게이트) + CHANGELOG + 세션 로그 + E2E | ✅ |

## v0.24 (2026-09-12) — Phase C 운영 완성 (PLAN_v0.24_phaseC_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-974 | PLAN 작성 + TODO 등록 | ✅ |
| T-975 | SpeedSchedule 코어(모델+직렬화+decide) + Repo 배선 + 테스트 8건 | ✅ |
| T-976 | SpeedScheduleManager(60초 틱) + RelayService 배선 | ✅ |
| T-977 | API speed-schedule 2종 + 검증 | ✅ |
| T-978 | UI 웹 스케줄 블록 + 앱 목록/다이얼로그 | ✅ |
| T-979 | 알림 딥링크(pendingOpenTab) + completionAction | ✅ |
| T-980 | 버전 단일 진실 + bump-version.sh | ✅ |
| T-981 | 검증(게이트) + CHANGELOG + 세션 로그 + E2E | ✅ |

## v0.25 (2026-09-12) — Phase D 작업별 속도 제한 (PLAN_v0.25_task-limit_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-982 | PLAN 작성 + TODO 등록 | ✅ |
| T-983 | 코어 (Job 필드+영속+스로틀+엔진) + 테스트 6건 | ✅ |
| T-984 | API (GET 포함 + POST limit) | ✅ |
| T-985 | UI 웹 카드 select + 앱 다이얼로그 | ✅ |
| T-986 | 검증(게이트) + CHANGELOG + 세션 로그 + E2E | ✅ |

## v0.26 (2026-09-12) — Phase E 트래커 프로빙 (PLAN_v0.26_tracker-probe_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-987 | PLAN 작성 + TODO 등록 | ✅ |
| T-988 | 코어 (UDP/TCP 프로브+판정) + 테스트 10건 | ✅ |
| T-989 | 캐시+엔진 우선순위+API | ✅ |
| T-990 | UI 웹/앱 | ✅ |
| T-991 | 검증(게이트) + CHANGELOG + 세션 로그 + E2E | ✅ |

## v0.27 (2026-09-12) — 웹 리뉴얼 (PLAN_v0.27_web-renew_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-992 | 새로고침 버튼+`R`키 (탭 유지) | ✅ |
| T-993 | CSS 토큰 19종 + 치환 | ✅ |
| T-994 | 테마 3종 + select + localStorage (기본 toxic) | ✅ |
| T-995 | 헤더 리뉴얼(.sub 삭제) + 네온 글로우 + 진행바 애니 | ✅ |
| T-996 | 모바일 대응 (44px·글로우 off·랩) | ✅ |
| T-997 | 검증(게이트+스크린샷) + CHANGELOG + 세션 로그 | ✅ |
| T-998 | 틱 리렌더 중 입력 보호 (속도 select·토렌트 체크박스) | ✅ |
| T-999 | 모바일 다운로드 1줄 입력 (한줄 고정) | ✅ |

## v0.28 (2026-09-12) — 주소 브라우저 열기 (PLAN_v0.28_addr-browser_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1000 | 앱 주소 클릭 시 브라우저 열기 (서버 카드+설정 URL, OpenBrowser 헬퍼) | ✅ |

## v0.29 (2026-09-12) — Safari 테마 select 수정 (PLAN_v0.29_safari-select_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1001 | Safari 셀렉트 깨짐 수정 (appearance 리셋+SVG 셰브론) | ✅ |

## v0.30 (2026-09-12) — 모바일 390px 설정 탭 수정 (PLAN_v0.30_mobile-settings-390_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1002 | 설정 탭 390px 깨짐 수정 (.fp 적층+.ck wrap+range 최소폭+stat 2열) | ✅ |

## v0.31 (2026-09-12) — 접이식 섹션 (PLAN_v0.31_collapsible_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1003 | 비디오 분석·토렌트 검색 접이식 (기본접힘+상태기억+검색OFF 숨김) | ✅ |

## v0.33 (2026-09-13) — 웹 파비콘/북마크 아이콘 (PLAN_v0.33_favicon_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1005 | PLAN 작성 + TODO 등록 | ✅ |
| T-1006 | 에셋 (favicon.svg+PNG 16/32/180+webmanifest, assets/web + docs) | ✅ |
| T-1007 | 코드 (Favicon 헬퍼+WebAssets head+RelayServer 라우트/인증예외) + FaviconTest | ✅ |
| T-1008 | 랜딩(docs/index.html) head 링크 | ✅ |
| T-1009 | 검증(게이트+CHANGELOG+세션 로그) | ✅ |

## v0.34 (2026-09-13) — 홈 실행상태 + HTTPS 포트 설정 (PLAN_v0.34_server-status-https-port_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1010 | PLAN 작성 + TODO 등록 | ✅ |
| T-1011 | 모델·DataStore·제약 (httpsPort+validPorts+E-AND-SRV-0111) + 단위테스트 | ✅ |
| T-1012 | RelayServer·RelayService httpsPort 배선 + 재시작 감시 | ✅ |
| T-1013 | 설정 UI (HTTPS 행+두 포트 상태줄) + 에러코드 등록 | ✅ |
| T-1014 | 홈 ServerCard 상태 + QR/주소 포트 버그 + 알림 문구 | ✅ |
| T-1015 | 웹 응답 httpsPort 노출 (확인 후 최소) | ✅ |
| T-1016 | 검증(게이트+CHANGELOG+세션 로그) | ✅ |

## v0.35 (2026-09-13) — 홈 QR 카드 HTTP·HTTPS 바로가기 (PLAN_v0.35_server-card-links_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1017 | PLAN 작성 + TODO 등록 | ✅ |
| T-1018 | ServerCard 두 주소 표시 + 복사/공유 반영 | ✅ |
| T-1019 | 검증(게이트+CHANGELOG+세션 로그) | ✅ |

## v0.32 (2026-09-13) — Content-Disposition 파일명 우선 (PLAN_v0.32_content-disposition_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1004 | 쿼리 response-content-disposition + 응답 Content-Disposition 헤더 우선, 폴백명 1회 교정 (UI 변경 없음) | ✅ |

## v0.36 (2026-09-21) — 서버 제어 분리 + HTTPS 개별 (PLAN_v0.36_server-control_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1020 | PLAN 작성 + TODO 등록 + 0.36.0/28 버전 | ✅ |
| T-1021 | 설정 모델 분리(bootAutoStart/launchAutoStart/httpsEnabled)+마이그레이션+단위테스트 10건 | ✅ |
| T-1022 | BootReceiver+MainActivity+RelayService(재시작 감시) 배선 | ✅ |
| T-1023 | ServerCard 시작/정지 버튼+HTTPS 상태 분리 표시 | ✅ |
| T-1024 | 설정 UI 3토글+웹 API(/api/info·/api/settings/server) 미러 | ✅ |
| T-1025 | 검증(게이트+CHANGELOG+세션 로그) | ✅ |

## v0.37 (2026-09-21) — 트래픽 통계 (PLAN_v0.37_traffic-stats_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1030 | PLAN 작성 + TODO 등록 + 0.37.0/29 버전 | ✅ |
| T-1031 | TrafficLedger 원장+영속+단위테스트 12건 | ✅ |
| T-1032 | 다운로드 3종 계측(엔진/비디오/토렌트 diff) | ✅ |
| T-1033 | 업로드 계측(serveFile/dl-file/dl-folder+토렌트 diff) | ✅ |
| T-1034 | API summary/daily 2종 + 라우트 등록 | ✅ |
| T-1035 | 웹 📊 통계 탭(SVG 30일 그래프+요약 카드) | ✅ |
| T-1036 | 앱 통계 탭(Canvas 7일 미니바+요약) | ✅ |
| T-1037 | 검증(게이트+CHANGELOG+세션 로그) | ✅ |

## v0.38 (2026-09-22) — 통계 하이라이트 + 원장 v2 + 알림 개선 (PLAN_v0.38/PLAN_v0.39)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1040 | 통계 하이라이트 6종 (TrafficHighlights+웹/앱 UI+테스트 6건) | ✅ |
| T-1041 | 원장 v2 스키마+마이그레이션+속도/완료실패 훅+StatsSnapshots 5종+extended API+UI | ✅ |
| T-1042 | 통계 3열 깨짐 수정 (웹 grid+앱 줄바꿈) + 메뉴 클릭 불능 JS fix | ✅ |
| T-1043 | 다운로드 알림 토렌트/시딩 표시 + 토렌트 플로우 갱신 | ✅ |
| T-1044 | 검증(0.38.0/30 test GREEN+assembleDebug+실기기 설치) | ✅ |

## v0.39 안정성 Phase A~D (2026-09-23) — 안정성 조사 19건 전수 수정

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1045 | Phase A — publishToDownloads 가드 + Jobs/Torrent Persistence 원자 쓰기 (#1~#3) | ✅ |
| T-1046 | Phase B — tryStart TOCTOU·add 중복 가드·start():Boolean·ScheduleJobService scope·메인 I/O 제거 (#4~#8) | ✅ |
| T-1047 | Phase C — onTaskRemoved 유휴 종료·429/5xx 재시도·DeviceGate TTL·moveToStorage 회피이름·Thumb Semaphore·Janitor 캐시·UUID/reorder·respondErr 이스케이프·RSS close·OkHttp 공유 (#9~#18) | ✅ |
| T-1048 | Phase D — mockk/coroutines-test/turbine + 회귀 테스트 + test 169 GREEN + ktlint GREEN + assembleDebug + 실기기 설치 | ✅ |
| T-1049 | Phase E — WebAssets 파사드 분할(2859→8+2715+146) + SettingsScreen 섹션 분할(1236→68+5파일) + READ/WRITE maxSdkVersion 축소 | ✅ |

## v0.41 (2026-09-25) — 토렌트 정체 회전 + 속도 프리셋 통일 (PLAN_v0.41_torrent-queue-rotate_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1050 | 정체 토렌트 자동 일시정지+큐 맨뒤 회전 (STALLED 상태·Session 세팅·순수 함수·maintainSlots·설정 3종·앱/웹 UI) | ✅ |
| T-1051 | 토렌트 개별 다운로드 제한 (/api/torrents/{id}/limit + 엔진 반영·영속 + 카드 선택 UI) | ✅ |
| T-1052 | 속도 프리셋 단일화 (SpeedLimits.kt + 슬라이더 3곳 select + 작업/토렌트 개별 제한 프리셋) | ✅ |
| T-1053 | 웹 대시보드 미러 (속도 select 3종·정체 설정 블록·STALLED 배지·uiBusy 가드) | ✅ |
| T-1054 | 검증 (TorrentStallTest 9건+test/ktlint GREEN+실기기 설치·API/웹 실측) | ✅ |

## v0.42 (2026-09-27) — 웹 대시보드 헤더 통합 (서버 설명+통계 드롭다운) (PLAN_v0.41_info-stats-menu_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1064 | PLAN 작성 + TODO 등록 + 0.41.0/32 버전 bump | ✅ |
| T-1065 | 서버 설명 바(`.info`) + 통계 탭을 헤더 우측 `📊` 단일 아이콘 드롭다운으로 통합 (4탭) | ✅ |
| T-1066 | 닫기 UX 4종(토글/외부 클릭/Esc/S) + 상태 배지 + 15초 스로틀 (닫힘 시 통계 API 0건) + `__infoHtml` 캐시 가드 | ✅ |
| T-1067 | 검증 (DashboardInfoMenuContractTest 19건 + verify_dashboard_info_menu.js 35/35 + 실기기 0.41.0 실측) | ✅ |
| T-1068 | 사파리 보고 결함 — 📊 버튼 44px vs 형제 34px 헤더 어긋남 수정 (metrics 일치 + flex center) | ✅ |
| T-1069 | 보관함 브레드크럼 인라인 onclick SyntaxError — 위임 + data-path 대체, jsArg 헬퍼 신설 | ✅ |
| T-1070 | DashboardInlineHandlerContractTest 6건 (홀수따옴표/JSON.stringify 인라인/자유텍스트 인라인 전수 스캔) | ✅ |
| T-1071 | 순서변경 드롭 표시 통일 — 사각 점선 태두리 + 삽입선 + drop-empty (보관함과 동일 시각 언어) | ✅ |
| T-1072 | **데이터 손실** — `/api/storage/move` 이름 충돌 가드(StorageMove) + 덮어쓰기 확인 UI + 실패 정리 분기 사용자 파일 보호 | ✅ |

## v0.43 (2026-09-27) — API/MCP 하드닝 (맥 클라이언트 전제 조건) (PLAN_v0.42_api-mcp-hardening_android.md)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1073 | Phase 0 — Origin/Host 검증 + `Sec-Fetch-Site` 보조 판정 (CSRF·DNS rebinding 차단) | ✅ |
| T-1074 | Phase 0 — `/api/*`·`/mcp` Content-Type 화이트리스트 (`text/plain` 벡터 차단, `/dav/` 예외) | ✅ |
| T-1075 | Phase 1 — MCP 버전 협상 (`MCP-Protocol-Version` + `_meta` 일치, 미지원 버전 400) | ✅ |
| T-1076 | Phase 1 — notification 202 / 미지원 메서드 404 `-32601` / `tools/call` `content`+`isError` 응답 형태 | ✅ |
| T-1077 | Phase 1 — 도구 확장 (5 → 12개: batch·storage 3종·torrent·video·stats) | ✅ |
| T-1078 | 검증 — 실기기 13종 + 웹 대시보드 무결성 + 테스트 300건 GREEN | ✅ |
| T-1079 | **보류** Phase 2~3 — 맥 메뉴바 앱. `docs/plans/PLAN_v0.42` 6장 참조. 전 Phase 완료 후 착수 | ⏸ |

## v0.43 (2026-09-28) — 토렌트 보관함 이동 + 웹 삭제 복구 (T-1085)

> v0.42(T-1073 교차 출처 가드, T-1055 폴링 이원화)에서 생긴 **회귀 2건**.
> 사용자 제보: "토렌트 완료 되도 보관함으로 이동 안 됨" + "웹에서 삭제해도 삭제가 안 됨".

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1085 | **완료 torrent → 보관함 이동 0건** — `TORRENT_FINISHED` 알림이 state=DONE 선기록 → 폴링의 `prevState==DONE` 조기 반환에 막혀 `moveToStorage()` 미도달. 전이 판정 → "완료+미이동" 판정으로 교체 · `storageMoved` 재이동 방지 · 실패 재시도 상한 3 · `moveToStorage()` 성공 반환 · 복원 시 미완료 이동 수습 | ✅ |
| T-1085 | **웹 삭제·일시정지·재개 전부 415** — `CrossOriginGuard` "Content-Type 비면 거부"가 본문 없는 `DELETE /api/torrents/{id}` 등을 막음(맥 `RelayClient.control()` 도 동일). `hasBody` 분기 추가 — 본문 있을 때만 검사. Origin·Sec-Fetch-Site 방어 유지 | ✅ |
| T-1085 | 회귀 테스트 `TorrentStorageMoveContractTest` 17건 + `PersistBeforeRestoreTest` 3건 (테스트 300 → 320) | ✅ |
| T-1085 | **목록 유실 사고** — 메인 스레드 동기 마이그레이션(6.8GB·3분53초) 동안 `persistDebounced` 가 빈 저장소를 저장 → `torrents.json`·`jobs.json` 이 `[]` 로 덮어써짐. 복원 완료 플래그를 모든 저장 경로에 심음(`persistNow`·`restoreTorrentsAsync`·`onDestroy`·`onTaskRemoved`) | ✅ |
| T-1085 | 토렌트 작업 위치 → `<보관함>/.torrents` (같은 파일시스템 → rename, 40초 복사 소멸). `.trash` 와 동일 숨김 처리(목록·용량·관리 API 제외), 숨김 판정 하드코딩 6곳 → `StorageGuard.isHidden()` | ✅ |

**함정 2 — 내가 만든 버그가 두 번째 사고를 만들었다**
워킹 디렉터리 마이그레이션을 `start()`(메인 스레드)에 두었다. 크로스마운트 동기 복사라
6.8GB 에 3분 53초, 그동안 ①ANR ②5초 폴링이 "아직 복원 전인 빈 저장소"를 보고
`torrents.json` 을 `[]` 로 덮어씀 ③`load()` 가 그 `[]` 를 읽어 목록 소실.
**"아무것도 안 읽은 시점"과 "사용자가 다 지운 시점"은 파일로 구분되지 않는다** —
둘 다 `[]` 다. 저장 가드는 플래그가 아니라 그 구분이 필요하다.
教训: 목록을 메모리에만 두는 구조에서 **복원 완료 전 저장은 항상 위험**하다.

**함정 3 — "저장 위치를 보관함으로"의 함정**
`enforceQuota` 는 `sortedBy { lastModified }` 로 초과분을 휴지통 보낸다. 진행 중인
대용량 파일이 보관함 루트에 잡히면 **오래된 사용자 파일이 대신 삭제된다.** 그래서
루트가 아니라 **숨김 하위 폴더**를 썼다 — 같은 파일시스템 이점(복사 소멸) + 쿼터·목록
부작용 제거를 동시에 얻는다.

**함정**: "알림 유실 대비" 코드를 만들면서 **알림이 정상적으로 오는 경로**를 죽였다.
폴링 주기(5초) > 알림 지연이라 알림이 이기는 게 사실상 항상인데, 그걸 전이 판정과
조기 반환이 함께 못 보고 있었다. 두 조건이 같은 함수 안에서 **모순**이었다.

## v0.44 (2026-09-27) — 빌드 스크립트 디바이스 선택 (T-1080)

| T-번호 | 내용 | 상태 |
|--------|------|------|
| T-1080 | `build_and_run.sh` adb 대상 미지정 버그 + 다중 기기 선택 규칙 + `run`·`log`·`devices` 서브커맨드 | ✅ |

## macOS 메뉴바 클라이언트 (2026-09-28 착수) — `apps/macos/`

> 착수 조건(`T-1079`) 해소: Phase 0~1 · PR #14 · #15 · 안정화 작업 전부 완료.
> 브랜치 `feat/macos-menubar-m1` (`787457b`, origin 푸시). **PR 미생성.**

| T-번호 | 내용 | 상태 |
|--------|------|------|
| M-01 | M1 — SwiftPM 구조 (Core/UI 분리) · `Subnet`/`IPv4` 계산 | ✅ |
| M-02 | M1 — 서버 탐색 3단계 (저장됨 → 게이트웨이 → `/24` 스캔) + 수동 입력 | ✅ |
| M-03 | M1 — `RelayClient` (`/api/info`·`/api/jobs`·제어) | ✅ |
| M-04 | M1 — SSE 스트림 + 10초 백업 폴링 (진행률 실시간) | ✅ |
| M-05 | M1 — 수동 `NSStatusItem` · 팝오버(폭 352) · 배지 · 설정 시트 | ✅ |
| M-06 | M1 — `--diagnose` (메뉴바 앱은 화면이 없으므로 **필수 장비**) | ✅ |
| M-07 | M1 — 버그 5건 수정 (`/24` 캡 방향 · `withPrefix` · CRLF 그래프eme · `AsyncBytes.lines` 빈줄 · 포인터 이중 래핑) | ✅ |
| M-08 | 메뉴바 아이콘 실제 렌더링 — **사용자 확인 완료 (2026-09-28)**: `.app` 설치 → 실행 → 3탭 동작 확인. 폰 established 연결 3개 · SSE `data: tick` 스트리밍 | ✅ |
| M-09 | **PR 생성** — `feat/macos-menubar-m1` → main — **PR #17 머지 완료** (`e05d0d4`) | ✅ |
| M-13 | **M3 — 독립 창 + 탭바** — 팝오버에 3탭(다운로드/토렌트/보관함) + 탭별 진행 배지 · `Torrent`/`StorageEntry` 모델(순수 파싱) · 탭별 통계 · `torrentControl`/`torrentDelete` · `--diagnose` 확장. 테스트 25 → **39건** | ✅ |
| M-10 | 목업 리뷰 4건 반영 (팝오버 폭 · 통계 행 · 팔레트 단계 · 탭바) | ✅ **실질 완결** — 폭 352 반영(M1) · 통계 행은 탭별로 3칸 구현(M13) · 탭바 반영(M13) · **팔레트는 M-12 로 분리**(시스템 권한 필요). 목업의 "온도" 항목은 **서버에 엔드포인트가 없어 표시할 값이 없다**(`/api/info` 실측: version·storageFree·storageTotal·ip·port·running·speedTotalBps·httpsEnabled·httpsPort 뿐) → **목업에서 제거**(값이 없는 칸은 "—" 가 영영 보인다) |
| M-15 | **쓰기 기능 일괄 구현** — `RelayClient+Write`(다운로드 추가·잡/토렌트 속도 제한·토렌트 추가·상세·파일 선택 · 보관함 이동/이름변경/휴지통/삭제/폴더생성/복원/영구삭제/공유링크) + `WriteResult`(서버 사유 그대로 전달) + `WriteSheets`(8종 시트) + 행 액션. **서버 명세를 curl 로 실측 대조** — 가정한 키 3개가 틀렸음(`address`→`ip`, `progress` 없음, `numComplete/Incomplete` 추가) | ✅ |
| M-14 | **팝오버 속도 그래프** — `SpeedGraph`(SwiftUI Canvas, 4계열 공유 Y축) + `AppModel.graphSeries` 시간축 균등 재구성 · 출처별 이력 분리(Droid ⊇ 기기이므로 **같은 축**) | ✅ |
| M-14 | **팝오버가 아예 안 뜨던 회귀 2건** — ① `statusItem.view` 전환 후 `statusItem.button` 이 nil → 팝오버 위치 `guard` 에서 조용히 return ② `contentSize` 와 `contentViewController` **충돌** → 그래프를 넣자 팝오버가 안 뜸 | ✅ |
| M-14 | **진단 추가** `--popover-check` (팝오버 표시 여부) — diagnose 는 `StatusItem` 을 안 만들어서 팝오버를 검증할 수 없었다 | ✅ |
| M-24 | **뛴 값 근본 해결 시도 → 실패 기록 + 표시로 전환** — ① **`NetworkStatsManager.querySummaryForDevice`** → 실측 **불가능**: `SecurityException: Network stats history of uid -1 is forbidden for caller 10375`. uid 전체 조회는 시스템 권한 필요, **시그니처에 uid 인자가 없어** 내 uid 로 우회도 못 한다 ② **`/proc/net/dev` · `/sys/class/net/wlan0/statistics/tx_bytes`** → SELinux denied 재확인 ③ **서버 1초 폴링** ("클라이언트가 5초마다 물어보니까 그 사이에 정산이 끼는 거다") → 실측 **무의미**: 1.00초/0.20초/0.05초 모두 **5,002,000 B/s 그대로**. **정산은 네트워크 계층이라 폴링 주기와 무관** ④ **증분 상한** → 진짜 대용량 다운로드까지 잘라내며 총량까지 틀림. 총량 보존이 더 중요 → **실패 3건 + 기각 1건을 `NetSpeedRoutes.kt` 주석에 기록**(같은 실수를 다시 안 하게) ⑤ **결론: 원본 유지 + 축에 "보통 N KB/s" 병기** — M-23 의 필터가 **3초짜리 실제 다운로드를 전부 지우고 있었다**(6초 이상이어야 일부 생존, 5초 이하는 0). 필터 제거하고 `SpeedScale.axisLabel` 추가 — `"4.8 MB/s · 보통 2 KB/s"` | ✅ |
| M-28 | **기기 트래픽 3구간 분리** — 사용자: "다운로드로 마찬가지 속도가 다르게 나옴", "업로드는 제대로 나오지 않음". ① **원인은 버그가 아니라 정의 불일치** — `external` = `rmnet*`(셀룰러)만 세므로 **핫스팟 ↔ 클라이언트 구간이 어느 곳에도 잡히지 않았다**. 통제 실험(11MB 다운로드): `swlan0 Δtx=11,693,373`(실제 전송과 일치) / `rmnet_data1 Δrx=295,326`(2.6%) / **`external Δrx=0` ★ 화면에 0** ② **`hotspot` 구간 신설** — `swlan0`(SoftAP) 전용. **검증: 11MB 다운로드 → `hotspot Δtx=11,783,254` vs 실제 11,363,590 (3.7% 오차) · `external Δtx=512`(셀룰러만)** ③ **★ `rmnet_ipa0` 중복 계상 차단** — 처음엔 "누락된 40GB" 라 판단했으나 **틀렸고 실측이 뒤집었다**: 동일 구간 `ipa0 Δtx=5,717,550` vs `data1 Δtx=5,677,102` = **99.2% 일치** → IPA 오프로드 가상 장치(`dumpsys netstats` 에도 없음). **더하면 2배 계상** → 명시적 제외 ④ **캐시 무효화(30초 TTL)** — 영구 캐시는 재접속 후 죽은 이름을 읽어 속도가 0 으로 굳는다 ⑤ `wlan0`(STA) 은 핫스팟으로 **안 잡는다** — 동시 활성화 불가, 없으면 없는 값을 만들어낸다 ⑥ 구버전 호환 유지(파라미터 없음 = `all`) · 테스트 macOS 297→**302** · Android 329→**336** 0 실패 **PLAN**: `docs/plans/PLAN_v0.43_traffic-3scope.md` | ✅ |
| M-27 | **기기 트래픽 범위 선택 (외부만 / 전체)** — 사용자 질문: "기기의 UP/DOWN 은 핫스팟 동작까지 다 포함한 것 같은데, **외부로 나갔다 들어오는 용량만** 체크할 순 없는가?" ① **실측 — 사용자가 맞았다** — `getTotalRxBytes/TxBytes` = **모든 인터페이스 합**. `total == swlan0 + rmnet` (rx 29.5GB = 1.4 + 28.1 **정확히 일치**). **핫스팟 내부(wlan) 가 tx 의 67%** 를 차지한다 ② **`/proc/net/dev` · `/sys/class/net` 은 여전히 denied** — M-24 기록이 정확했다 ③ **★ hidden API `getRxBytes(String iface)` 는 앱 uid 로 동작한다** — **M-24 가 못 찾던 경로**. `android.jar` 에 없고 시스템 jar 에만 있다. 리플렉션으로 부르면 정상값. **SELinux 가 막는 건 "파일 직접 읽기" 뿐**이고 `TrafficStats` 안쪽 Binder 경로는 통과한다. **왜 M-24 가 못 찾았나** — `adb shell` 로 테스트하면 **셸 uid** 라서 되고, 그게 성공한 걸 "앱도 된다" 로 착각. **앱 안에서 직접 읽어야 답이 된다** ④ **3구간 실측으로 분리 확인** (내부 200회 / 외부 25MB / 내부 200회) — `swlan0Δtx` **11.6배** · `rmnetΔrx` **9.7배** 로 **둘이 독립적으로 움직인다**. 핫스팟 AP 경유는 양쪽에 다 잡히지만 **증분이 다르다** → **합으로 빼지 않고 rmnet 을 직접 읽는다** (뺄셈은 언제든 음수가 될 수 있다) ⑤ **구현** — 서버 `?scope=` + `rmnet*` 합계 + `scope` echo + `note` / Core `TrafficScope`·`TrafficScopeSetting`·`mismatchNote` / AppModel `trafficScopeSetting`(기본 외부만) / 설정창 별도 줄 / `--diagnose` 가 두 모드 다 출력. Method·인터페이스 목록 `@Volatile` 캐시 (1초 폴링마다 reflection 재수행 방지) ⑥ **★ "기본값" 이 두 군데에서 다르다** — 클라이언트 설정 = **외부만**(지표의 목적) / **서버 파라미터 없음 = ALL**(안 보낸다 = 필드를 모르는 구버전). **서버·클라이언트는 따로 업그레이드되므로** 구버전 클라이언트에 "조용히 다른 값" 이 보이면 가장 나쁜 실패다 → **모르는 쪽에는 예전 값을 준다** ⑦ **가이드 문서 작성** — `~/Documents/AGENTS/development/guide/android-traffic-per-interface.md` (조사 방법·실측표·실패 9건·구현 코드·교훈 3가지) 테스트 macOS 278 → **297건**, Android 320 → **329건** 0 실패. **구현 후 실측 차이(제거된 핫스팟 내부): rx 27,049,247,967 / tx 67,629,568,942 (96%)**. **화면 확인**: 설정에 `기기 속도 범위 [외부만 ▾]`, 기기 값이 작아짐. **사용자 확인 완료 (범위 전환 시 그래프·메뉴바 값 함께 변경)** |
| M-26 | **속도 표시가 두 줄로 깨지던 버그** — 사용자 스크린샷: `Droi` / `d` 로 갈리고 `↓269` `K` 로 끊기며 축 라벨은 `1.0 MB/s · 보통 3…` 로 잘림. ① **추정하지 않고 실측했다** — `NSFont` 로 문자열 폭을 직접 재니 **실제 필요폭 365.0pt / 그때 가용폭 328pt = 37pt 부족**. 원인은 **범례 `Text` 에 `lineLimit` 이 없었다** — 폭이 모자라면 SwiftUI 가 **물어보지 않고 접는다**(한 단어가 안 들어가면 글자 중간에서 자른다). 축 라벨만 `lineLimit(1)` 이라 **같은 줄에서 하나는 접히고 하나는 잘렸다** ② **`PopoverMetrics` 신규(Core)** — 폭 352 를 **여섯 군데**에 흩어 적던 걸 **한 곳**으로. **한 곳을 넓혀도 나머지가 따라가지 않아 반쪽만 고쳐지는** 상태였다 ③ **팝오버 352 → 450** (그래프 328 → 426) — 실측 365 대비 **+17% 여유**. **설정 창 460 보다 좁게** — 팝오버가 설정 창보다 넓으면 두 창이 같은 앱의 조각으로 안 읽힌다 ④ **접히지 않게 만드는 안전장치** — `lineLimit(1)` **+** `fixedSize(horizontal: true)`. **둘을 같이 써야 한다** — `lineLimit(1)` 만 주면 한 단어가 통째로 안 들어가면 그것까지 자른다. 여백이 모자라면 **축 라벨이 양보**한다(값이 먼저 사라져서는 안 된다) ⑤ **`SpeedLegend` 로 분리** — 그래프와 한 덩어리로 재면 `Canvas` 46pt 이 섞여 **한 줄인지 두 줄인지 구분되지 않는다** ⑥ **진단 `--legend-check` 신설** — M-21 교훈("계산이 맞다 ≠ 화면에 간다")을 그대로 적용. **실제 뷰를 `NSHostingView` 에 넣어 높이를 잰다**(9.5pt 한 줄=12pt, 두 줄=23pt) ⑦ **진단이 내 계산 오류를 잡았다** — `Spacer` 가 자식이라 **간격을 두 번 받고 자체로 10pt** 를 차지하는데, **계산엔 간격 3곳(30pt) 만 넣고 Spacer 를 잊었다 → 20.2pt 과소평가**. 여전히 365 < 426 이라 **화면은 멀쩡해서 아무도 몰랐고**, 값만 길어지면 접힌다. **과소평가는 조용히 못-found 한다** — 수정한 뒤 **계산 364.8 vs 실제 365.0 (차이 0.2pt)** ⑧ **글꼴 계수도 두 번 틀렸다** — 한글을 "전각=1.0" 으로 셌더니 **15% 과대**, `·` `→` `↓` `↑` 를 전각으로 셌더니 **실제 반각**(0.618)보다 넓음. **실제 `NSFont` 와 대조하는 테스트**가 잡아 준다 — 과소(접힘 놓침)·과대(안심) **양쪽** ⑨ **`SpeedColor` 로 색 통일** — 그래프 선과 범례 점이 **같은 색**이어야 한다 테스트 265 → **278건 0 실패**(신규 13건). **실측: 필요 365.0 / 가용 426 (여유 61pt) · 한 줄 확인(12.0pt) · 팝오버 450 = 상수 일치** | ✅ |
| M-25 | **토렌트·잡 다운로드 속도가 안 보이던 버그** — 사용자 스크린샷: `IPZZ-926 ▲4.6 KB/s` 인데 **다운로드 속도가 없다**. `▼104` 는 **피어 수**였음. ① `/api/torrents` 실측 — 서버는 `downloadSpeed: 102427` 을 **정상적으로 주고 있었고 파싱도 정상**이었다. 문제는 표시 로직 ② **`if uploadBps > 0 { ▲ } else { 다운로드 }`** — **업 속도가 켜지는 순간 다운로드 속도가 화면에서 사라졌다.** 잡 행도 같은 구조여서 **둘 다 고침** ③ **`RowSpeedDisplay` 신규(Core)** — 순수 함수. `if/else` 로 UI에 쓰면 어떤 조합이 숨겨지는지 알 수 없으므로 **9가지 조합을 전부 테스트**로 고정 ④ **값은 독립이다** — 하나가 0 이어도 다른 하나는 살아 있어야 한다. 조건으로 고르면 **항상 존재하는 정보를 조건에 걸어 없앤다** ⑤ `0` 은 `nil`(빈 칸) — "0 B/s" 를 그리지 않는다. 진행 중인 다운로드 옆의 0 은 **노이즈**다. 테스트 255 → **265건**. **실측: 서버 down 65,186 / up 15,729 → 화면에 `▼64K ▲16K` 둘 다 표시** | ✅ *(사용자 확인 대기)* |
| M-23 | **기기 속도 스파이크 제거 (Y축 5 MB/s 버그)** — 사용자 스크린샷: 라벨은 `기기 ↓4K ↑17K` 인데 그래프 축이 **5.3 MB/s**. ① **`/api/net/speed` 20회 1초 간격 실측** — 정상 **1~5 KB/s** vs 보충 **2~5 MB/s**, 1000배 튀었다가 **바로** 내려옴. **나눗셈은 정확했고 나눈 대상이 `TrafficStats` 보충분**이었다 ② `SpeedSpike` 신규(Core) — **중앙값** 기준. 평균이면 스파이크 하나가 기준을 끌어올려 **다음 스파이크가 "정상" 이 된다** ③ `baselineWindow=10` · `spikeRatio=50` — 실측 비율이 1000배니 50배면 넉넉하다. **기준이 0 이면 판정하지 않는다** — "0 보다 큰 모든 것" 을 걸으면 트래픽 없는 구간에서 시작하는 실제 전송을 버린다 ④ **지속 전송은 살아남는다** — 실전 다운로드는 수 초 안에 여러 샘플이 쌓여 **기준이 올라가** 첫 프레임만 걸리고 이후는 통과. **이게 실패하는 방향이라 테스트로 고정** ⑤ **그래프·축·메뉴바·범례 4곳 전부** 같은 값 — 하나만 걸면 **같은 화면에서 두 개가 다른 말을 한다** ⑥ `peak()` 도 걸러진 값으로 산다. 테스트 242 → **259건**. **실측 검증: 축 4.8 MB/s → 17.6 KB/s, 보충분 6개 제거 · 실제 트래픽 13개 전부 보존** | ⚠️ **M-24 에서 부분 철회** — 축은 고쳐졌지만 **3초짜리 실제 다운로드를 지우고 있었다**(6초 이상이어야 일부 생존). **원본 유지 + 축 참고값 병기로 전환** |
| M-22 | **탐색 상태 표시 (M-11 착수)** — 팝오버 헤더 아래에 **"어디에 붙었는지" 한 줄**. ① **`DiscoveryBadge` 신규(Core)** — 화면 글자를 순수 함수로. 전략 4개 × 버전 유무 × 수동 입력이 얽혀 있으니 **디스플레이 없이 전부 고정** ② **주소 + 버전 + 방법** 3가지를 함께 — 주소만 있으면 "이게 내 폰이 맞아?" 에 답할 방법이 없다. **버전**이 있어야 폰 설정 화면과 숫자를 맞춰 본다 ③ **수동 입력을 자동 발견과 구분** — 오해의 방향이 정반대다(자동 발견=안심 / 직접 입력=확인 필요). `isManual` 이 전략보다 우선 ④ **빈 버전 걸러냄** — `cached` 경로는 `version: ""` 로 만들어 **"· v" 가 붙으면 잘린 버전처럼 보인다** ⑤ **헤더 아래 고정 줄** — 오른쪽의 "방금 전" 과 **한 줄에서 경쟁하지 않게 분리**. `Text("DroidRelay")` 만으로는 어느 폰인지 모른다 ⑥ 색은 글자와 같은 판단 — "연결됨" 인데 주황이면 거짓말로 읽힌다 ⑦ `--diagnose` 에 **화면과 같은 함수**로 찍음 — 따로 적으면 화면과 진단이 다른 말을 한다. 테스트 231 → **242건**. **실측: "10.38.120.211:3000 · v0.43.0 · 게이트웨이"** | ✅ |
| M-11 | 탐색 상태 표시 추가 여부 확정 (`🔌 10.38.120.211 자동 발견 ✓`) | ✅ M-22 로 착수 |
| M-12 | M2 — 팔레트 (Carbon 핫키 `⌘⇧D` · `.nonactivatingPanel` · IME 입력 소스 캡처) | ⏸ |
| M-16 | **보관함 쓰기 실사용화** — ① 폴더 행 어디든 눌러도 진입(`.disabled(true)` 로 만들면 `[비활성]` AX 상태가 남아 누르는 데 실패하는 버튼이 됨 — 없는 것보다 나쁨) ② 동영상 `▶` 실시간 재생 · `▶` 는 진짜 재생되는 형식에만(`mkv`/`avi` 는 검은 화면 → 고장난 앱으로 보임) ③ 받기는 playback 판정과 무관하게 **모든 파일에** ④ URL 조립은 Core 로 — 슬래시는 구분자로 남기고 조각마다만 인코딩(경로 전체 인코딩 → `%2F` → 서버 404 실측). **사용자 확인: 받기 300000B 도착** | ✅ |
| M-17 | **IINA 우선 재생 (브라우저 폴백)** — `MediaOpener.resolve(bundleIDs:lookup:)`. **"http 는 브라우저만 연다" 는 오독이었다** — `urlForApplication(toOpen:)` 은 Launch Services 등록 목록이지 앱의 능력이 아니었다. IINA 는 mpv 기반이라 스트리밍이 되고(서버 로그 206 + 창 제목 = 파일명) **버퍼를 앞서 쌓아** ~600KB/s 환경에서 브라우저보다 매끄럽다. `lookup` 주입으로 설치 여부와 무관하게 판단을 테스트로 고정. 주의: 성공은 `Bool` 이 아니라 `NSRunningApplication` | ✅ |
| M-18 | **설정을 별도 창으로 분리 + 안전망 폴링 주기** — ① `.sheet` 는 붙은 뷰의 **윈도우**에서 뜨는데 그게 팝오버라 **자기 안에서 자랐다**(사용자 지적: "팝오버로 보인다") → `SettingsWindowController` 로 분리. ② 폴링 루프는 둘인데 **10초 안전망만** 설정에 노출(1초는 그래프용 — 노출하면 그래프만 거칠어지고 서버 부하만 오른다). ③ `clamp` 필요 — `UserDefaults` 는 손으로 편집 가능하고 0/음수는 0ms 루프로 이벤트 루프를 태운다. **사용자 확인 "잘 드고 잘 되"**. ⚠️ `.accessory` 앱 첫 `NSWindow` 함정 2개: `NSApp.activate` 를 `present()` **첫째**로 / 컨트롤러 **강한 참조**(풀리면 창이 통째로 사라짐) | ✅ |
| M-21 | **설정 창이 왼쪽 하단에 뜨던 버그** — `NSWindow(contentRect: NSRect(x: 0, y: 0, …))` 로 만들어 **생성 위치가 (0,0) = 화면 왼쪽 아래**였다. `applyPlacement()` 로 나중에 옮겨도 **창이 뜨는 첫 순간은 이미 (0,0)** 이고 그 사이 사용자는 화면을 본다. 게다가 초기 프레임은 `(0, -28)` 이었다 — `contentRect` 는 **내용물** 영역이라 위로 제목바 28pt 가 붙어 **제목바가 화면 밖으로 삐져나갔다**. → `init` 에서 **바로 가운데로 세팅**. ① **`initialContentOrigin` / `frameAfterTitleBar` 추가(Core)** — 제목바가 위쪽에 붙으므로 세로 중심은 `y + (contentH - titleBar)/2`, 즉 **`(content + titleBar)/2` 가 아니라 `(content - titleBar)/2` 를 빼야 한다.** 부호를 `+` 로 적은 첫 판은 테스트가 28pt 어긋남을 잡았다 ② **진단 `--settings-check` 를 "계산값만" 에서 "실제 창을 띄우고 `w.frame` 을 읽는" 것으로 강화** — 첫 판은 계산 (670,291) 을 찍고 테스트도 통과했는데 **실제 창은 (0,-28) 에 있었다. 계산이 맞다 ≠ 창이 그곳에 간다** ③ 진단이 **저장값을 건드리지 않음** 유지. 테스트 227 → **231건**. **실측: 첫 실행 (670,291) 정중앙 ✓ · 저장값 (300,500) 유지 ✓** | ✅ |
| M-20 | **설정 창을 화면 가운데 + 위치 기억** — ① 오른쪽 위(메뉴바 아래) 고정 → **가운데**(첫 실행) ② **닫을 때 좌표 저장**(`NSWindowDelegate.windowWillClose` — 알림이 아니라 delegate 인 이유: 여러 창이 같은 알림을 들어 **필터 한 줄이 없어진다**) ③ **`WindowPlacement` 신규(Core)** — 순수 함수. 화면 좌표 계산은 `NSScreen` 이 필요해 보이지만 **실제로는 창 크기·화면 목록·저장 좌표 3개뿐** → **디스플레이를 붙였다 떼는 모든 경우를 테스트로 재현**. 테스트 208 → **227건**. ④ **설계가 한 번 뒤집혔다** — 처음엔 "화면 밖이면 안으로 클램프" 했더니 **"창이 안 뜬다" 는 분기가 죽은 코드가 됐다**(클램프가 항상 화면 안으로 넣으므로 뒤의 검사가 절대 실패하지 않음). → **당기지 않는다. 아니면 가운데로 간다.** ⑤ **화면 "목록"으로 바꾼 이유** — 주 화면 하나만 보면 **왼쪽 2인치 모니터의 창을 "안 보인다" 고 판단해 주 화면 가운데로 튕긴다.** ⑥ **제목바 띠(상단 28pt)로 판정** — 전체 창의 겹침 넓이로 재면 **맨 아래 2px 만 걸친 창이 460×2 로 "넓게 보인다"** 고 통과하지만 **제목바는 화면 반대편이라 끌 방법이 없다**. ⑦ **신규 진단 `--settings-check`** — "가운데에 나온다" 는 **서술**이라 좌표로 판정해야 한다. `--diagnose`(StatusItem 안 만듦)·`--title-check`(메뉴바만) **둘 다 이 창을 못 본다**. **실측 3024×1964 Retina → 1800×1130 pt, 창 460×548, 중앙 (670, 291) ✓ · 화면 밖 복귀 ✓** | ✅ |
| M-19 | **▶ 재생 규칙을 `mp4`·`mp3` 두 개로 축소** — ① **함수 이름 변경** `isBrowserPlayableVideo` → `isStreamPlayable`. M-17 에서 재생 앱이 IINA 로 바뀐 뒤 **"Browser" 가 거짓말이었고**, `mp3` 를 넣으면서 **"Video" 도 거짓말이 됐다.** 프로젝트 원칙("거짓말인 이름은 나중에 그 필터로 코드를 다시 쓴다")대로 고침 ② 목록 `mp4 m4v mov webm` → **`mp4 mp3`**. `m4v mov webm` 은 **실제로 재생되지만** ▶ 를 뺐다 — 넓힐 이유가 "못 하는 것" 이 아니라 "편리할 것" 이었고 **받기는 모든 파일에 있으므로 잃는 방법이 없다.** 되돌리려면 `streamPlayable` 에 3개 추가 ③ **대문자 확장자**(`HOME.MP4`)도 같게 처리 — 서버·Finder 는 구분 안 함 ④ help 텍스트도 "브라우저" → "IINA(없으면 브라우저)". **보관함 실측: mp3 는 0개**(mp4·dmg·tar.gz만) — 규칙은 서버 `audio/mpeg` + `/stream` 206 실측으로 뒷받침. 테스트 206 → **208건** | ✅ |
| M-13 | M3 — 독립 창 + 탭바 | ✅ |
| M-14 | M4 — **DMG 배포 경로** — `./build_and_run.sh dmg macos` → `dist/DroidRelay-<버전>.dmg` (create-dmg, Applications 바로가기, ad-hoc 서명) | ✅ |
| M-14 | **앱 아이콘 신설** — `Tools/MakeIcon.swift` 가 SF Symbol 을 즉석에서 그려 icns 생성(리포에 PNG 10장 없음) | ✅ |
| M-14 | **macOS 버전 = 서버 버전 연동** — 하드코딩 0.1.0 이었음(서버 0.43.0). `gradle.properties` 에서 읽는다 | ✅ |
| M-14 | **메뉴바 속도 표시** — `statusItem.view` 로 `MenuBarSpeedView`(NSTextField 2줄 직접 배치) + `MenuBarTitle`(그릴 줄을 정하는 순수 로직) · 9pt 2줄이 메뉴바 22pt 에 들어감 · `--title-check`/`--watch` 로 프레임·잘림·실측값 검증. 테스트 64 → **85건** | ✅ |
| M-14 | **2줄 표시** — TetherLens 방식(`statusItem.view` + 9pt) 로 확정. 10.5pt 로는 26pt 필요해 잘렸으나 **9pt 는 21pt** 로 22pt 안에 들어간다 | ✅ |
| M-14 | **설정 시 "DroidRelay Settings" 창이 뜸** — `Settings { EmptyView() }` 씬 하나만 있으면 활성화될 때 자동 표시. 씬 없이 `NSApplication` 직접 부팅으로 교체. 창 0개 확인 | ✅ |
| M-14 | **기기 속도** — 서버 `api/net/speed` 신설(`TrafficStats` 누적 카운터) + 클라이언트가 `DeviceTrafficRate` 로 시간 차 계산. `supported=false` 면 **빈 열을 만들지 않는다** | ✅ |
| M-14 | **메뉴바 열 배치** — 앱 아이콘 1개(1줄) · `↑`/`↓` SF Symbol(왼쪽 고정) · 출처별 **고정폭 열 + 오른쪽 정렬** (값이 바뀌어도 열이 흔들리지 않음) | ✅ |
| — | **앱 설치 위치 = `~/Applications`** (사용자 지정). `/Applications` 는 시스템 영역이라 승인·권한이 붙는다 | ✅ |
| M-14 | **로그인 시 자동 실행** — `SMAppService.mainApp` (구식 `SMLoginItemSetEnabled` 는 macOS 13 deprecated) · 상태는 플래그가 아니라 Launch Services 조회 · 실패 사유를 설정 화면에 노출 · `--login-item=on/off` 진단 플래그 · `--diagnose` 에 상태 출력. 테스트 39 → **45건** | ✅ |

> **M-14 배포 방식 결정 (2026-09-28, 사용자)** — **DMG 로 배포**한다.
> `spctl` 판정이 `rejected` 인 것은 ad-hoc 서명 상태의 **현재** 판정이고, 지금은
> 로컬 빌드(격리 속성 없음)라 실행에 문제가 없다.
>
> 사실 하나는 남겨둔다: **다운로드받은 파일에는 `com.apple.quarantine` 가 붙으므로
> DMG 는 Gatekeeper 를 가장 통과하기 어려운 경로다.** ad-hoc 서명은 "개발자를 확인할 수
> 없다" 화면이 나오고, 사용자가 우클릭 → 열기(1회) 또는 `xattr -dr com.apple.quarantine`
> 가 필요하다. 불만을 감수하면 ad-hoc + DMG 로도 배포는 된다(M-14 에서 패키징만 추가).
> 이걸 없애려면 **Developer ID + 공증**이 필요하고 Apple Developer Program(연 $99) 가입이 전제다.

**M-02 부근 근거**: `docs/mockups/probe_discovery.py` 실측 — 게이트웨이 35ms, `/24` 스캔 0.11초.
**함정 13종** (리서치 6 + 구현 중 발견 7): `docs/plans/PLAN_v0.42_api-mcp-hardening_android.md` 6-2 / 6-2-1

| M-14 | **속도가 0 으로 표시되던 버그 2건** — ① 잡 상태를 `"RUNNING"` 하드코딩 → 서버는 `DOWNLOADING`. `Job.isRunning` 으로 교체 ② `refresh()` 가 토렌트를 **탭 조건부로** 당김 → 메뉴바용 속도가 0. 항상 당기도록 변경 | ✅ |
