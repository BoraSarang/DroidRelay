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
- [ ] 체크섬 검증 (SHA-256 선택 입력)
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
