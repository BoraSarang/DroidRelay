# 세션 2026-09-12 (android, Phase E)

## 무엇을
- Phase E 트래커 프로빙: TrackerProbe(UDP BEP15+TCP) + 캐시 + 엔진 우선순위 + API + 웹/앱 UI
- 신규: TrackerProbe.kt + TrackerProbeTest 10건
- 수정: TorrentEngine(주입 정렬·probeTrackers·start 연쇄)/TorrentRoutes(GET 확장·POST probe·refresh 연쇄)/WebAssets(표시+버튼)/SettingsScreen(측정 행)

## 플랫폼
- Android (S22 실기기 LTE, Wi-Fi 10.206.125.138:8080)

## 빌드+PERF+CACHE
- testDebugUnitTest 전체 109건 GREEN (18 스위트)
- ktlint 본문 GREEN, JS node --check OK, assembleDebug GREEN + 실기기 설치
- PERF: 프로브 20개 병렬 8·4초 타임아웃, 수백 바이트/개. refresh 연쇄 1일 1회 수준
- CACHE: trackers_probe.txt 신규 (24h TTL, 1184B 실측)

## 남은 TODO
- T-991 ✅ (아래 E2E 4종 PASS)
- 다음 후보: Play 배포

## 전달로그 (E2E 실측)
1. POST probe → `{"started":true}`, GET probing:true 확인
2. 완료 후 GET: probing:false, probeOk:20/20, rtt 434~654ms, ageMs 정상
3. 로그 `[FEATURE] 트래커 프로브 완료 도달 20/20개` 2회 (기동 자동 + 수동)
4. 캐시 파일 `trackers_probe.txt` 존재 (1184B)

## 문서갱신
- docs/plans/PLAN_v0.26_tracker-probe_android.md 신설
- docs/TODO.md T-987~T-991 등록·완료
- docs/CHANGELOG.md 0.26.0 (미배포) 추가
- error_message_ko.json 변경 없음

## 큐상태/E2E
- 잡음: 테스트 오기 1건 (명시 포트 기대값) 수정
- UDP 차단망에서도 주입 유지되므로 기능 저하 없음 (설계상 폴백)
