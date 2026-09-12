# 세션 2026-09-12 (android, Phase C)

## 무엇을
- Phase C 구현: 속도 스케줄(P1-5) + 알림 딥링크/완료동작(P1-8) + 버전 단일 진실(P2-9)
- 신규: SpeedSchedule.kt / SpeedScheduleManager.kt + SpeedScheduleTest 8건
- 수정: SettingsRepository(2필드+setter)/SettingsConstraints(상수)/SettingsRoutes(speed-schedule 2종·download·reset)/RelayService(매니저·전이·PendingIntent)/MainActivity(pendingOpenTab)/WebAssets(스케줄 블록·완료 셀렉트)/SettingsScreen(칩·섹션·리셋)/PersistenceGuard(mask)/gradle.properties+build.gradle.kts(버전 참조)/scripts/bump-version.sh

## 플랫폼
- Android (S22 실기기, Wi-Fi 10.206.125.138:8080)

## 빌드+PERF+CACHE
- testDebugUnitTest 전체 92건 GREEN (15 스위트)
- ktlint 본문 GREEN (kts 파서 기존 이슈 제외), JS node --check 2블록 OK, assembleDebug GREEN
- PERF: 스케줄 틱 60초(진입/이탈 시만 apply), 트래커 캐시 수 KB. 배터리 영향 미미
- CACHE: 영향 없음

## 남은 TODO
- T-981 ✅ (아래 E2E 7종 PASS)
- Motrix 차용 3상 모두 완료. 다음 후보: 작업별 제한, 트래커 프로빙, Play 배포

## 전달로그 (E2E 실측)
1. 스케줄 roundtrip: POST 24h 창 → GET 반영, 무효(days=9) → 400 "잘못된 요청"
2. 스케줄 적용: 60초 틱 내 `[FEATURE] 속도스케줄 적용 id=e2e1 DL=1024KB/s UL=64KB/s`
3. 스케줄 복원: 창 삭제 후 `[FEATURE] 속도스케줄 종료 — 수동값 복원`
4. 완료 후 정지: tiny 다운로드 DONE → `[FEATURE] 전체 완료 → 서버 정지` → status 무응답(정지 확정)
5. 복구: 재기동 후 completionAction=none 복원, 테스트 파일·잡 정리, 스케줄 빈 목록 확인
6. 알림 인텐트: OPEN_TAB/tab=2 → `[FEATURE] 알림 탭 이동 tab=2` (PendingIntent 실탭은 수동 확인 대기)
7. 버전 스크립트: dry-run 0.99.0-test/999 성공 후 원복 (26/0.21.0 유지)

## 문서갱신
- docs/plans/PLAN_v0.24_phaseC_android.md 신설
- docs/TODO.md T-974~T-981 등록·완료
- docs/CHANGELOG.md 0.24.0 (미배포) 추가
- error_message_ko.json 변경 없음 (400 기존 문구 재사용)

## 큐상태/E2E
- 잡음: 자정넘김 규칙 명세화(새벽분은 시작요일 소속), 공백편집 줄합침 1건 복구, 전이판정 대입순서 버그 수정
- 잔여 수동 확인: 알림 실탭 이동, 0206 실전 구제율
