# PLAN_v0.39_stats-ledger-v2_android.md (P2 초안)
> 생성일: 2026-09-22 | 플랫폼: android | 상태: 구현 완료 (2026-09-22)

## 1. 목표 (1줄)
traffic.json 스키마 v2 + 신규 계측 테이블로 최고속도·일별건수·피크피어·가동시간·저장공간추이·단절/스로틀을 통계에 추가한다.

## 2. 스키마 변경 (호환 마이그레이션 필수)
- `TrafficDay` 확장: `maxDownBps, maxUpBps (+시각), doneHttp, doneVideo, doneTorrent, failCount`
- 로드 파서 `TrafficLedger.kt:174` 정규식 확장 + 구버전(5필드) → 신버전 기본값 0 마이그레이션 + 손상 시 `.bak` 보존 (PersistenceGuard 규칙)
- 속도 샘플러: DownloadEngine EMA + TorrentEngine 5초 폴링값을 1초 집계 → 일별 max 갱신 (10초 디바운스 영속 재사용)
- 완료/실패 카운터: `RelayService` 완료·실패 웹훅 지점 (196-307)에 `addDone/addFail` 훅

## 3. 신규 계측 (별도 JSON, traffic.json 비대화 방지)
| 테이블 | 내용 | 기록 지점 |
|---|---|---|
| `peers.json` | 5분 스냅샷: 시각, 합산 seeds/peers, 피크 | TorrentEngine 5초 폴링 |
| `uptime.json` | 부트 시각, 종료 시각, 일별 가동분 | RelayService onCreate/onDestroy + BootReceiver |
| `storage.json` | 일별 dirSize, free, quotaMoved | StorageJanitor.enforceQuota 반환값 + 일별 스냅샷 |
| `net.json` / `throttle.json` | 단절 시작/종료+복구결과, 스로틀 시작/종료+사유 | NetworkMonitor onLost/Available, GuardDaemon 상태전이 |

## 4. 리스크
- 저전력 기기에서 1초 샘플러 배터리 영향 → 포그라운드+전송 중일 때만 샘플링
- 구버전 원장 마이그레이션 실패 → 빈 복구 + 백업 보존, 통계 `—` 폴백
- 보관 상한: 일별 400일 유지, 스냅샷系 90일 prune

## 5. 검증
- 단위테스트: 마이그레이션 구→신, 속도 max 갱신, prune
- E2E: 1MB up/down 후 일별 max·건수 반영, 재시작 후 누적 유지
