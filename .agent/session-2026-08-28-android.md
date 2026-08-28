# 세션 로그 2026-08-28 (Android + Web) — v0.13.1 스트림 진행률 + 배지 + 한글 + 팝업 5종

## 세션 요약
- **무엇을/플랫폼**: [ANDROID+WEB] 커밋 `feat/android-v013-stream-progress`에서 5종 해결: ① 스트림 진행률(HLS 세그먼트 기반 %/남은시간/용량), ② 배지 정렬, ③ 파일명 `____(_____)` 한글 깨짐, ④ confirm/prompt → 팝업 레이어, ⑤ 폴더/파일 rename 팝업
- **빌드**: BUILD SUCCESSFUL (ktlint + testDebugUnitTest 확장 10건 GREEN + assembleDebug). WebAssets JS 추출(615~1977행) `node --check` OK
- **PERF/CACHE**: 해당 없음. 진행률 신호는 FFmpegKit **LogCallback**(HLS 브랜치에서 불안정 — 동일 URL도 64/64 또는 2/64) → **`-progress` 파일 poll로 전환(신뢰성 확보)**
- **남은TODO**: 없음 (실기기 E2E로 전 항목 검증 완료, 커밋 예정)
- **전달로그**: FFmpegKit LogCallback의 HLS segment-open 로그 전달이 비결정적임을 실기기로 증명 → `-progress 파일의 out_time / 총재생시간(ms)` 기반 진행률로 우회. `-progress` 파일은 `handleComplete`에서 삭제
- **문서갱신**: TODO, CHANGELOG v0.13.1, PLAN_v0.13, 본 세션 로그
- **큐상태**: 없음
- **E2E**: 단위 10건 + 실기기 완주 검증 (진행률 1→100% 단조 증가, 한글 파일명 보존, DONE/FAILED 전환 확인)

## 핵심 기술 결정 — 진행률 신호 (회귀 발견)
- FFmpegKit `LogCallback`은 HLS `Opening '...ts'` 로그를 **불안정 전달**: `한글테스트`(직접 미디어 m3u8)는 64/64 전달됐지만 동일 URL `최종확인`은 2/64에서 정체. bytes는 커지는데 segmentsDone이 안 오름 → 로그 기반 세그먼트%는 신뢰 불가
- **해결**: FFmpeg argv에 `-progress <file>` 추가 → `pollProgress`가 1초마다 파일의 `out_time_us`(µs)를 읽고 `totalDurationMs`(미디어 플레이리스트 `#EXTINF` 합)로 나눠 % 계산. `out_time`은 FFmpeg 자체 출력이라 단조·신뢰성 확보
- 진행률 우선순위: `out_time/총재생시간` → 세그먼트(불안정, 보조) → 파일크기/총용량
- `parseOutTimeUs`는 companion 순수 함수로 TDD, `totalDurationMs`는 `StreamDetector.playlistDurationMs/mediaDurationMsFromUrl`(마스터면 첫 variant 팔로우)

## 구현 내역 (v0.13.1)

### ① 스트림 진행률 (T-891, -progress 기반, TDD)
- `StreamDetector`: `playlistDurationMs(playlist)`("`#EXTINF`" 합, 순수) + `mediaDurationMsFromUrl(url)`(마스터면 첫 variant 팔로우) 추가, `EXTINF_RE` 상수
- `Job`(JobsRepository): `totalDurationMs: Long` 필드 추가
- `VideoDownloadManager`:
  - `createAndStart(..., totalDurationMs)` — 총재생시간 저장
  - `start`: argv에 `-progress <progressFile>` 삽입, `progressFiles[jobId]` 맵. LogCallback 버퍼는 세그먼트 **보조 표시용**으로 유지
  - `pollProgress`: 우선순위로 `computeProgress(jobId,job)` → `out_time/총재생시간` → 세그먼트 → 파일크기/총용량
  - companion 순수 함수 `parseOutTimeUs(content)` — 마지막 `out_time_us=` 파싱 (TDD)
  - `handleComplete`에서 `progressFiles[jobId]?.delete()`
- `VideoApi.create`: `mediaDurationMsFromUrl(stream)` 계산해 전달, 디버그 로그에 seg/dur 기록
- `RelayServer` `/api/jobs`: `totalDurationMs` 응답 추가

### ② 배지 정렬 (웹)
- `.badges` 그룹 CSS + render에서 state·video 배지를 `<span class="badges">`로 묶음 (한 줄 정렬). 앱 JobCard는 🎬 이모지로 이미 정렬됨

### ③ 파일명 한글 깨짐 — 회귀 테스트로 확정
- 원인은 현재 코드에 없음(모든 sanitizer가 한글 유지). `safeFilename` 한글 보존 회귀 테스트 추가 → 실기기 `한글파일명_테스트.mp4` JSON에도 깨짐 없이 저장 확인(직접 URL은 url 파일명 기본값 커밋 `96570b6`로 해결)

### ④ confirm/prompt → 팝업 레이어 (웹) + ⑤ rename 팝업
- CSS `.overlay`/`.popup` + JS `makeOverlay`/`closePopup`/`confirmPopup`/`promptPopup`/`delJobConfirm`
- 모든 `confirm(`/`prompt(` 제거: purgeItem, emptyTrash, createFolder, renameItem, delItem, deleteSelected, delJob(780행, `delJobConfirm`), deleteRssFeed, resetSettings

### 검증
- TDD: `playlistDurationMs`(합/0) 2건, `parseOutTimeUs`(마지막값/빈값) 1건, 기존 `segmentsDoneFromLog` 5건 + `safeFilename` 한글 보존 포함 총 10건 GREEN
- WebAssets JS 추출(615~1977행) `node --check` OK
- 실기기 E2E: `진행검증.mp4`(64세그먼트 hq, 634.6s) → **진행률 1→100% 단조 증가**(bytes 동반) 후 `DONE/100%/63.9MB`. segmentsDone은 불안정 로그에도 진행률은 신뢰성 있게 증가
- `한글파일명_테스트.mp4` → DONE/100%/63.9MB, 파일명 JSON 보존

## 문서
- TODO.md: v0.13.1 섹션 T-891(진행률)+T-892(배지)+T-893(한글)+T-894(팝업) 등록/완료
- CHANGELOG.md: v0.13.1 (스트림 진행률-safe·배지·한글 회귀·팝업 레이어·rename)
- PLAN_v0.13_stream_download.md: 진행률 신호를 LogCallback→-progress로 갱신

## 주요 변경 파일
- **변경**: StreamDetector.kt(재생시간/마스터 팔로우), VideoApi.kt(totalDurationMs), VideoDownloadManager.kt(-progress/pollProgress/computeProgress/parseOutTimeUs), JobsRepository.kt(totalDurationMs), RelayServer.kt(응답), WebAssets.kt(배지/팝업/진행률 UI), StreamDetectorTest.kt(신규 테스트), CHANGELOG.md, TODO.md

## 남은 TODO
- 없음 (5종 모두 검증, 커밋 진행)

## 큐 상태
- 없음

## E2E
- 단위 10건 GREEN (진행률 파싱·세그먼트·한글 파일명)
- 실기기 2회 완주: 직접 미디어 m3u8 다운로드(진행률 1→100% 신뢰성), 한글 파일명 보존
