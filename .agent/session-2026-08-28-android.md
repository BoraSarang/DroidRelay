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

## v0.13.2 후속 (동일 일자 — v0.13.1 커밋 `7ae0d04` 이후)
- **커밋 `7ae0d04` 후 사용자 발견 이슈 3건 수정**:
  1. **팝업 Enter 미동작/화면 어두워짐**: confirm 버튼 `onkeydown`은 포커스에 의존해 불안정 → **document 전역 `keydown` 리스너**(`__popupOk` 보관)로 Enter=확인/Escape=닫기, 포커스 무관 + preventDefault로 폼 제출/리로드 차단. confirm/prompt 전체에 일괄 적용(새 폴더·이름 변경·영구삭제·비우기·이동·다운로드 삭제·RSS 삭제·설정 초기화)
  2. **휴지통 복구/영구삭제 버튼 스타일 깨짐**: `.file-row .acts .ghost` 34px 폭 규칙이 텍스트 버튼에 적용돼 좁게 줄바꿈 → 인라인 `width:auto;padding:5px 10px`로 해제
  3. **모바일 대응 (T-896)**: `@media (max-width:640px)` CSS 미디어 쿼리 추가 — `.wrap` min-width 해제(가로 스크롤 제거), 정보바 세로 접기, 카드 액션(112px 고정) 아래 재배치, 보관함 `#treePanel` 숨김(경로 이동만), 설정 `settings-nav`→상단 가로 스크롤 칩, `.row`/`.row-torrent` wrap. **HTML/JS 변경 없이 순수 CSS** — 실기기 서버 응답에 `@media(max-width:640px)` 반영 확인
- **검증**: POPUP/트래쉬/모바일 모두 `assembleDebug` 성공, 서버 응답에 새 핸들러(`__popupOk`, `width:auto;padding:5px 10px`, `@media(max-width:640px)`) 반영 확인
- **문서갱신**: TODO T-896, CHANGELOG v0.13.2

## v0.13.3 후속 (동일 일자 — v0.13.2 커밋 `f772e6b` 이후)
- **사용자 실기기 보고 이슈 4건 수정 (T-897)**:
  1. **보관함/휴지통 가로 100% 미충족**: flex column 컨테이너에서 `.storage-main`이 명시 폭 없이(부분)만 채움 → 모바일 CSS에 `.storage-main{width:100%}` 추가
  2. **설정 메뉴 오른쪽 여백 없음**: `.settings-nav`가 stretch로 콘텐츠 폭 100%, 12개 칩이 `flex-shrink:0`+`nowrap` → `overflow-x:auto` 스크롤 시 우측이 화면 가장자리에 닿음. 처음엔 `width:calc(100%+24px)`+`margin-left:-12px`로 시도했으나 **오른쪽이 +12px 초과해 오히려 0px** → `margin-left`/`width:calc` 제거하고 `padding:0 12px`+`box-sizing:border-box`로 확정
  3. **카드 액션 삐져나감 (다운로드·토렌트 동일)**: `.card-acts`가 content-box라 `width:100%`+`padding` 14px 좌우가 더해져 카드 폭(366px)을 초과 → `.card-acts{box-sizing:border-box}` 추가. agent-browser 모바일 실측: acts L=13~R=377(카드 R=378) **삐져나감 없음**
  4. **서브타이틀 문구 축약**: `.sub` → `휴대폰이 직접 다운로드합니다 · 끊겨도 이어받기됩니다` (옵션 B, 사용자 직접 선택)
- **디버그 로그 억제 (RelayServer.kt)**: 폴링(정보 획득용) GET 엔드포인트 `/api/info`, `/api/jobs`, `/api/torrents`, `/api/guard/status`는 웹이 `refresh()`로 빈번히 호출 → API 자동 기록 인터셉터(`DebugLogger.api`)에서 `pollExempt` set으로 제외해 버퍼/로그 낭비 방지. 각 자체 핸들러의 `DebugLogger`는 그대로 유지(오류만).
- **검증**: `assembleDebug` 성공 + 실기기 재설치. agent-browser(iPhone 14, 390px)로 다운로드/토렌트 카드 `.card-acts` 수용 확인. 설정 `.settings-nav` 우측 여백 확보
- **문서갱신**: TODO T-897, CHANGELOG v0.13.3

## v0.13.3 공개 배포 (릴리즈)
- **GitHub Release + 랜딩 + README**: 첫 공개 배포. `github.com/BoraSarang/DroidRelay`(public) — README.md + GitHub Pages 랜딩 `docs/index.html`(순수 단일 HTML, 다크 테마) 작성
- **릴리즈 서명 체계 도입**: `keytool`(AS JBR)로 신규 릴리즈 keystore `apps/android/keystore/droidrelay-release.jks` 생성(SHA-256 `2f1dc84a…`) + `keystore.properties`(gitignore). `build.gradle.kts` `signingConfigs.release` + `buildTypes.release.signingConfig` 연결, 비밀번호는 `keystore.properties`에서 Properties 로드(하드코딩 금지)
- **버전 정리**: `versionName` 0.13.0→**0.13.3** (versionCode 13)
- **보안**: `.gitignore`에 `*.jks`/`*.keystore`/`keystore.properties` 추가 — 키 커밋 방지. git check-ignore로 확인
- **실기기 설치**: debug v0.13.0→릴리즈 v0.13.3 전환. 서명 불일치(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) → `adb uninstall`(데이터 삭제, 사용자 승인) 후 `adb install` 성공. `apksigner verify`로 릴리즈 서명(SHA-256 `2f1dc84a…`) 확인
- **문서갱신**: CHANGELOG v0.13.3 릴리즈 섹션, AGENTS.android.md 배포절차, 세션 로그

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
