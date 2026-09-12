# 세션 2026-09-12 (android, Phase B)

## 무엇을
- Phase B 구현: 쿠키 전달(P0-3) + 파일명 매트릭스(P1-4) + 트래커 동기(P1-6)
- 신규: TrackerListProvider.kt + 테스트 3종 17건(ExtraHeaders 6·SafeFilename 6·TrackerList 5)
- 수정: StreamDetector(ExtraHeaders)/VideoApi/JobRoutes(E-AND-VALID-0002)/WebAssets(4곳)/DownloadsScreen/SettingsScreen/VideoDownloadManager/DispositionHeader/SettingsRepository·Routes(reset 포함)/TorrentEngine(주입+refresh)/TorrentRoutes(2종)

## 플랫폼
- Android (S22 실기기, Wi-Fi 10.154.225.186:8080)

## 빌드+PERF+CACHE
- testDebugUnitTest GREEN (신규 17건 포함 전체)
- ktlint 본문 GREEN (kts 파서 기존 이슈 제외)
- WebAssets JS node --check 2블록 OK
- assembleDebug GREEN + 실기기 설치 성공
- PERF: 트래커 refresh는 start 시 백그라운드 1회, 주입은 매핑 시 최대 20개·게이트 내. fetch 헤더 추가로 요청 수 변화 없음
- CACHE: 트래커 24h 파일 캐시 신규 (수 KB)

## 남은 TODO
- T-973 ✅ (아래 E2E 5종 PASS)
- 다음: Phase C (속도 스케줄 + 알림 클릭 + 버전 스크립트)

## 전달로그 (E2E 실측)
1. 트래커 자동 동기: 기동 시 `[FEATURE] 트래커 동기 완료 20개`, `GET /trackers` count=20 (번들 5개 아님 → 네트워크 성공)
2. 길이 제한: 5KB 쿠키 → `E-AND-VALID-0002: 전달 헤더가 너무 깁니다` (400)
3. 전달 경로: 쿠키+Referer 포함 분석 요청 → fetch 정상 수행(HTTP 404 응답까지 도달, 헤더 통과 증명)
4. 누출 검사: 로그 내 `SECRETCHK123` 0건, 분석 로그는 `ref=true ck=true`만
5. 설정 roundtrip: `torrentTrackerSync: True` (54번째 필드, 마이그레이션 없이 기본값 구체화 — Phase A 검증 겸함)

## 문서갱신
- docs/plans/PLAN_v0.23_phaseB_android.md 신설
- docs/TODO.md T-967~T-973 등록·완료
- docs/CHANGELOG.md 0.23.0 (미배포) 추가
- error_message_ko.json `E-AND-VALID-0002` 1건 추가

## 큐상태/E2E
- 잡음: 컴파일 2건 수정 (object 내 companion 불가 → 직접 멤버, 공백편집 줄합침 복구)
- 실사용 관찰: 0206 사이트 실전 구제는 사이트 종속으로 사용자 확인 대기
