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
| T-213 | 설정 탭 verticalScroll (하단 잘림 해결) | ✅ |

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
- [ ] 웹 대시보드 SSE 실시간 푸시
- [ ] 다중 URL 일괄 붙여넣기
- [ ] Content-Disposition 파일명 우선
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
