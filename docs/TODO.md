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
