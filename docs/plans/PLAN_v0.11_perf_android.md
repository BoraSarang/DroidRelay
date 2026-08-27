# PLAN v0.11 — 배터리/성능 최적화 (2026-08-27)

## 개요
안드로이드 앱이 기기 배터리를 가장 많이 소모 → 성능·효율·버그 1순위 조사 후 개선.
배터리 드레인 핵심: ① 24시간 WakeLock, ② jobs.json 디스크 저장 무디바운스(400ms/1회당),
③ 토렌트/다운로드/가드/디버그 오버레이 과다 폴링, ④ Repository 무의미한 StateFlow 방출.

## 발견된 핫스팟 (조사 결과)
| # | 이슈 | 위치 | 영향 |
|---|------|------|------|
| 1 | WakeLock 24시간 연속 보유 (PARTIAL_WAKE_LOCK) | `RelayService.kt:270-277,415` | CPU deep sleep 봉쇄 — 최대 드레인 |
| 2 | jobs.json 저장 "디바운스" 주석과 달리 실구현 없음 — emission마다 전체 JSON 디스크 쓰기 | `RelayService.kt:136-140` | 다운로드 중 400ms마다 플래시 쓰기 |
| 3 | 토렌트 1초 폴링 → 모든 handle `th.status()`(JNI) + repo update + 10s persist | `TorrentEngine.kt:506-567` | 토렌트 N개 × 초당 JNI/업데이트 |
| 4 | 다운로드 진행률 400ms 업데이트 → refresh + 방출 | `DownloadEngine.kt:436` | 활성 다운로드당 2.5회/초 방출 |
| 5 | Repository update마다 무조건 refresh(new list + StateFlow 방출) | `JobsRepository.kt:53-74` `TorrentRepository.kt:65-78` | UI 재컴포지션 유발 |
| 6 | 가드 30초 폴링 + 매 폴링 `settings.firstBlocking()`(DataStore) | `GuardDaemon.kt:44,99-101` | 주기 I/O |
| 7 | RssFeedManager 누수 — 로컬 변수라 서비스 종료 후에도 15분 폴링 지속 | `RelayService.kt:59-61` `RssFeedManager.kt:20` | 수명주기 버그 |
| 8 | 디버그 오버레이 1초 폴링 + `startInForeground()` 이중 호출 | `DebugOverlayService.kt:41-42,124` | 불필요 UI/폴링 |
| 9 | 알림 갱신 700ms마다 NotificationManager IPC | `RelayService.kt:175` | 부수적 |

## 결정사항 (사용자 승인 완료)
| 항목 | 결정 |
|------|------|
| WakeLock | 완전 제거 (Foreground Service `DATA_SYNC`만으로 유지) |
| 토렌트 폴링 | 1초 → 5초 |
| 다운로드 틱 | 400ms → 2000ms |
| 가드 데몬 | WorkManager 미도입 — in-process 30초 → 120초 + `firstBlocking()` 5분 캐시 |
| jobs 저장 | 실질 디바운스 10초 |
| 업데이트 이벤트 | 동일값 스킵 (before == after면 refresh 생략) |

## 구현 항목 (T-841 ~ T-850)
1. **WakeLock 제거** — `acquireWakeLock()`/`WAKE_TIMEOUT_MS`/onDestroy release 삭제
2. **jobs 저장 10초 디바운스** — lastSaveAt 가드
3. **알림 갱신 2초** — 700ms → 2000ms
4. **다운로드 틱 2초** — `TICK_MS = 400 → 2000`
5. **토렌트 폴링 5초** — `delay(1000) → 5000`
6. **Repo 동일값 스킵** — update()에서 before==after 시 refresh 생략
7. **torrent 저장 실변경 가드** — persistDebounced에서 변동 없으면 skip
8. **RssFeedManager 누수 수정** — 필드 보관 + onDestroy stop
9. **가드 주기 120초 + 설정 캐시** — 30s→120s, settings 1회/5분
10. **DebugOverlay 정리** — 이중 startInForeground 제거, 폴링 3초

## 검증
- `./build_and_run.sh debug android` (ktlint + assembleDebug) → 실기기 설치
- `adb shell dumpsys power`에서 DroidRelay WakeLock 부재 확인
- 진단 로그 빈도 비교: `이력 저장` / 토렌트 폴링 전·후
- 다운로드·토렌트 진행 정상 (진행률 UI 갱신 확인)

## 에러코드 영향
- 신규 에러코드 없음. `E-AND-DOWN-2003`(저장 실패) 경로 유지.