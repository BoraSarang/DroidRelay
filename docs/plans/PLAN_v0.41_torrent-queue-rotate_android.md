# PLAN_v0.41_torrent-queue-rotate_android.md
> 생성일: 2026-09-25 | 플랫폼: android | 상태: 구현 완료 (2026-09-25)

## 1. 목표 (1줄)
정체(stall)된 토렌트를 자동 일시정지 + 큐 맨뒤로 회전시켜 다음 토렌트가 받도록 하고, 속도 프리셋(무제한/256~20480KB/s)을 앱 레벨 속도 제한·전역 다운로드 제한·토렌트 기본 다운로드 속도·토렌트/작업 개별 제한에 통일한다.

## 2. 원인 (조사 결과)
- `dont_count_slow_torrents=true`(libtorrent 기본) → 저속 토렌트도 active로 카운트
- `incoming_starts_queued_torrents=true` → 새 peer가 들어오면 대기 토렌트까지 기동
- `active_limit=500` → `torrentMaxActive` 설정이 사실상 무력화 (고속 2~3 + 저속 2~4 병렬)
- 표시용 order(`TorrentEngine.reorder`)만 바뀌고 libtorrent `queue_position` 미동기 → 재기동 순서 불일치
- libtorrent 2.x에 QUEUED 상태 없음 → `status.state()`로 대기/정체 판별 불가

## 3. 범위

### P1. 상태·설정 모델
- `TorrentState.STALLED` 신규 (PAUSED/FAILED 사이) — 라벨 "정체 — 다음 torrent로 전환"
- `SettingsConstraints`: stall 기본값(켜짐/2KB/s/60초) + 기준 `[1,2,4,8,16,32,64,128]` KB/s + 지속시간 `[30,60,120,300,600,1800]` 초
- `SettingsRepository`/`SettingsRoutes`/`PersistenceGuard.maskSecrets`: `torrentStallEnabled/ThresholdKbps/TimeoutSec` 3종 입·출력·리셋
- `relay/SpeedLimits.kt` 신규 — 프리셋 단일 진실 `KBPS=[0,256,512,1024,2048,5120,10240,20480]` + `label/labelBps/bpsOptions/kbpsOptions`

### P2. 엔진 (TorrentEngine)
- `applyStallSessionSettings()` — `incoming_starts_queued_torrents=false`, `dont_count_slow_torrents=!stallEnabled`, `inactive_down_rate/up_rate=threshold*1024` (리플렉션 `swigSetting()` 헬퍼, `$` 식별자 우회)
- 폴링 루프: `flags & PAUSED`로 libPaused 판별 → `state` 갱신, 이후 `isStalledTorrent()`/`stallTimedOut()` 순수 함수로 정체 판정
- `rotateStalled()` — 대체 torrent가 있을 때만 `queuePositionBottom()+pause()` 후 STALLED 회전 (요동 방지)
- `maintainSlots()` — 패스마다 active < max이면 QUEUED 승격, active==0일 때만 STALLED 재기동 (하트비트)
- `syncQueueOrder()` — 표시 order를 libtorrent `queuePositionSet`로 동기화
- `setDownloadLimit(id,bps)`/`applyPersistedLimit(id)` — 토렌트 개별 다운로드 제한 (0=무제한, 등록 시 복원)
- `pause()`/`resume()` — AUTO_MANAGED unset/set으로 auto-manage 30초 재기동 방지

### P3. API
- `GET /api/torrents` 목록에 `maxDownBps` 추가
- `POST /api/torrents/{id}/limit` 신규 (`{maxDownBps}` → `RelayApp.getTorrent`에 즉시 반영)
- `reorder` → `reorderTo()`로 변경 (큐 동기 포함)

### P4. UI (앱)
- 공용 `SpeedSelectRow`(KB/s)/`SpeedSelectBpsRow`(B/s) + `SpeedPresetDialog` — 슬라이더 3곳(앱 레벨 속도 제한·전역 다운로드 제한·토렌트 기본 다운로드 속도)을 셀렉트로 교체
- 토렌트 설정에 "정체 torrent 회전" 블록(스위치 + 기준/지속시간 셀렉트)
- 토렌트 카드에 "다운로드 제한" 버튼 + 프리셋 라디오 다이얼로그, STALLED 라벨·색·재개 버튼
- `TaskLimitRow`(작업 개별 제한)도 `SpeedLimits.bpsOptions()`로 통일
- `resetSettings` torrent/all 분기에서 stall 기본값 복원

### P5. UI (웹 대시보드)
- 속도 제한 3곳(range) → `<select>` (프리셋 동일) + 토렌트/작업 개별 제한 select
- "정체 torrent 회전" 설정 블록(체크박스 + 기준/지속시간 셀렉트)
- `.STALLED` 카드 배지/색/재개 버튼 + `setTorrentLimit()`
- `renderTorrents`에 `uiBusy(el)` 가드 (select 조작 중 틱 리렌더 방지)

## 4. 리스크
- 리플렉션 `swigSetting()` 실패 시 세션 세팅 미반영 → 코드는 안전하게 패스, 런타임 로그 확인 필요
- `dont_count_slow_torrents=false`로 바꾸면 stall 중에도 active 카운트에 잡혀 슬롯 누수 → `maintainSlots()`가 STALLED를 active에서 제외하므로 정합
- 정체 토렌트가 하나뿐이면 회전해도 다음이 없음 → `rotateStalled()`가 대체 torrent가 있을 때만 회전
- 슬라이더 제거로 기존 저장값(예: 3000KB/s)은 폴백 라벨로 표시 (값 보존)

## 5. 검증
- `./build_and_run.sh test android` GREEN (신규 `TorrentStallTest` 9건 포함)
- `./build_and_run.sh lint android` GREEN (ktlint)
- `compileDebugKotlin` GREEN
- 실기기 설치 + 정체 회전/큐 동기/개별 제한 수동 확인
- CHANGELOG·TODO·세션로그 갱신
