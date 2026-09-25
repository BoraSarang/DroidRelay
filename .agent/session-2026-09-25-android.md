# 세션 로그 — 2026-09-25 (android, 토렌트 정체 회전 + 속도 프리셋 통일)

## 1. 목표
정체(stall) 토렌트를 자동 일시정지 + 큐 맨뒤 회전시켜 다음 토렌트가 받도록 하고, 속도 프리셋을 앱·웹 4곳에 통일

## 2. 수행
- **모델/설정**: `TorrentState.STALLED` 신규, `SpeedLimits.kt`(프리셋 단일 진실 `0·256·512·1024·2048·5120·10240·20480 KB/s`), stall 설정 3종(`torrentStallEnabled/ThresholdKbps/TimeoutSec`, 기본 켜짐/2KB/s/60초) + `speedPresets` API + `maskSecrets` + `resetSettings` 복원
- **엔진**: `applyStallSessionSettings`(리플렉션 `swigSetting()`으로 `dont_count_slow_torrents`·`inactive_*_rate`·`incoming_starts_queued_torrents`), 폴링에서 `isStalledTorrent`/`stallTimedOut` 순수 함수 → `rotateStalled`(대체 있을 때만 `queuePositionBottom()+pause()`), `maintainSlots`(QUEUED 승격→active 0일 때 STALLED 재기동), `syncQueueOrder`+`reorderTo`, `setDownloadLimit/applyPersistedLimit`, pause/resume은 AUTO_MANAGED unset/set
- **개선**: `FETCHING_METADATA`(메타데이터 미수신)도 정체 판정에 포함 — 죽은 마그넷이 슬롯을 영구 점유해 STALLED 재기동이 영구 막히는 데드락 차단 (파일 검사 CHECKING_*는 제외)
- **API/UI(앱)**: `GET /api/torrents`에 `maxDownBps`, `POST /api/torrents/{id}/limit`, 공용 `SpeedSelectRow`/`SpeedSelectBpsRow`로 슬라이더 3곳(앱 레벨 속도·전역 다운로드·토렌트 기본 다운로드) 교체, 토렌트 정체 회전 블록, 카드 개별 제한 다이얼로그, `TaskLimitRow` 프리셋 통일, STALLED 라벨·색·재개
- **UI(웹)**: 속도 3곳 range→`<select>`, 정체 설정 블록, `.STALLED` 배지+재개 버튼, 토렌트/작업 카드 제한 select, `renderTorrents` `uiBusy` 가드, `saveDownloadSettings`/`saveTorrentSettings`에 stall 본문 + change 리스너
- **문서**: `PLAN_v0.41_torrent-queue-rotate_android.md`, `TODO` T-1050~T-1054, `CHANGELOG` Unreleased 4섹션

## 3. 빌드 검증
- `compileDebugKotlin` / `test`(신규 `TorrentStallTest` 9건 포함) / `ktlintCheck --rerun-tasks` 전부 GREEN
- 웹 JS `node --check` OK (Kotlin 보간 제거 후)
- 실기기 설치 후 API 실측: 정체 토렌트 `b23ec9df` 60초 후 `STALLED` → 나머지 `DOWNLOADING` 승격 확인, `/limit` 적용·해제, stall 설정 4KB/s 저장 후 복원, 웹 셀렉트·배지·정체 블록 렌더 확인

## 4. 이슈·해결
- Kotlin 식별자에 `$` 불가 → `settings_pack$bool_types` 리플렉션 `swigSetting()` 헬퍼로 우회
- libtorrent `set_download_limit(0)`=무제한 확인 → 프리셋 0 통일
- `reorder()`가 표시 order만 바꾸던 결함 → `reorderTo`+`queuePositionSet` 동기
- resume이 AUTO_MANAGED 안 되면 30초 뒤 재기동 → pause/resume에서 unset/set로 해결
- 틱 리렌더가 카드 내 select 조작을 끼끗이 → `uiBusy(el)` 가드

## 5. 미완료
- (해소) 버전 상향 `0.38.0(30)` → **`0.39.0(31)`** (`bump-version.sh`)
- (후보) `maintainSlots`가 maxActive 초과분을 내려놓지 않음 — resume 후 활성 3/2 상태 관찰됨 (기존 동작, 사용자 선택 범위)

## 6. 커밋
- `230b2b1` feat(android): 토렌트 정체 자동 회전 + 속도 프리셋 통일 (T-1050~T-1054) — v0.41 관련 20파일만
- 공유 파일 혼재 정리: `SettingsConstraints`/`SettingsRepository`/`CHANGELOG`는 헌크 단위로 선행 세션분(포트 기본값 3000/HTTPS 끔·재시도 메시지 문서) 제외 후 스테이징

## 6-1. 후속 (같은 날)
- 웹 카드 제한 select 폭 불일치 지적 → 인라인 폭(104px/100%) 제거로 `.card-acts` 규칙에 통일, 실측 118px(모바일)/112px(데스크톱) 3개 일치 + node --check/ktlint GREEN
- 문서 정리 + 워킹트리 전체 커밋(선행 세션 미커밋분 포함) → 브랜치 `feat/android-v041-torrent-stall` push → PR → squash 머지 예정

## 7. 다음
- 실기기에서 정체·회전을 몇 시간 관찰 (기준/지속시간 프리셋 조정 여부)
- 선행 미커밋분(서버 기본값 3000/HTTPS 끔·재시도 메시지·문서) 별도 커밋

## 8. 상태
✅ 구현·문서·버전 0.39.0(31)·test/ktlint/실기기 검증·커밋(230b2b1) 완료
