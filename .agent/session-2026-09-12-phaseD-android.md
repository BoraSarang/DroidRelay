# 세션 2026-09-12 (android, Phase D)

## 무엇을
- Phase D 작업별 속도 제한: Job.maxDownBps + 영속 + ThrottleInterceptor 작업별 버킷 + API + 웹/앱 UI
- 신규: TaskLimitTest 6건 + ThrottleChainTest 1건(타이밍)
- 수정: JobsRepository·JobsPersistence·ThrottleInterceptor·DownloadEngine·JobRoutes·WebAssets·DownloadsScreen

## 플랫폼
- Android (S22 실기기, Mac 로컬 2GB/200MB + Wi-Fi)

## 빌드+PERF+CACHE
- testDebugUnitTest 전체 99건 GREEN (17 스위트)
- ktlint 본문 GREEN, JS node --check OK, assembleDebug GREEN + 실기기 설치
- PERF: 상한 미설정 시 supplier 분기만 추가 (무제한 경로 오버헤드 ~0), 버킷은 작업별 lazy 생성 + forget 정리
- CACHE: 영향 없음

## 남은 TODO
- T-986 ✅ (아래 E2E 6종 PASS)
- 다음 후보: 트래커 프로빙, Play 배포

## 전달로그 (E2E 실측)
1. API roundtrip: POST limit → `{"ok":true}`, GET에 `maxDownBps` 반영, 무효(음수·초과·video·미존재) 400/404
2. 작업별 상한: 2MB/s cap → 실측 2001KB/s (2.3% 내, 올림 방향), 해제 후 73~79MB/s 복귀
3. 전역 회귀: 8MB/s cap → 평균 8.3MB/s (기존 2배速 버그 수정 확인)
4. 버그 2건 수정 (실측 기반):
   - a) 인터셉트 시점 무제한 판정 → 도중 설정 평생 미적용 → 항상 래핑 + live supplier로 변경
   - b) 토큰 버킷 수면분 이중 적립 → 정확히 2배速 (JVM 타이밍 테스트로 재현·확정, `accountSleep` 추가)
   - c) sleepMs 정수 절삭 → 고속 상한 33% 초과 (2MB/s→2.66MB/s) → 올림으로 변경
5. 기기 정리 완료 (테스트 잡·파일·전역제한 0·Mac 서버 종료)

## 문서갱신
- docs/plans/PLAN_v0.25_task-limit_android.md 신설
- docs/TODO.md T-982~T-986 등록·완료
- docs/CHANGELOG.md 0.25.0 (미배포) 추가 (기존 버그 수정 포함)
- error_message_ko.json 변경 없음

## 큐상태/E2E
- 잡음: Christina(cloudflare 403)·OVH 429로 외부 대용량 소스 실패 → Mac 로컬 http.server로 전환 성공
- 테스트 수 정정: T-983 6건 + 체인 1건 = 7건 (TODO 표기 유지)
