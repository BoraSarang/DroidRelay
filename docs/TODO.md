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
