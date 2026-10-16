# PLAN v0.55 — 리팩터: 웹 대시보드 분리 + 토렌트 순수 로직 추출

> 생성일: 2026-10-16 | 플랫폼: android · web | 작성자: OpenCode
> 배경: 사용자가 "안드로이드 앱·웹도 리팩터링" 을 요청.
> 실측: `WebDashboardHtml.kt` **3,041줄 단일 Kotlin 원시 문자열**(CSS 15–321 / 마크업 322–955 / JS 956–3039),
> `TorrentEngine.kt` **1,931줄**(순수 함수 + 거대 클래스 혼재).
> 목표: **동작 불변**. 관심사별로 파일을 갈라 탐색·수정 비용을 낮춘다. 위험한 클래스 분해는 하지 않는다.
> 원칙: `pre-refactor-android-web` 태그로 롤백 지점 고정. 각 Phase 는 **순수 이동**만 — 로직·문자열·API 를 바꾸지 않는다.

## 0. 비전 / 제약

- 리팩터는 기능이 아니다 — **깨지면 손해만 본다.** 그래서 검증을 먼저 정하고 시작한다.
- `WebAssets.dashboardHtml: String` 은 **공개 계약**이다(서버가 서빙 + JVM 단위 테스트 6파일이 읽음).
  → 출력 **바이트를 바꾸지 않는다.** `assets/` 파일로 옮기는 것은 이 계약을 깨므로 하지 않는다.
- `PLAN_v0.54` 는 "SettingsRoutes 768행 분할"을 **제외**했다(무인 동작과 무관·깨질 위험만).
  같은 이유로 이 PLAN 도 거대 클래스 분해는 하지 않는다.

## 1. Phase 목록

### Phase 0 — 롤백 지점 + 문서
- `git tag pre-refactor-android-web`
- 본 PLAN + TODO `T-1124~T-1126` 등록

### Phase 1 — 웹 대시보드 분리 (동작 불변)
- 범위: `WebDashboardHtml.kt` → 3조각 + 조립
  - `WebDashboardHead.kt` — `<head>` + `<style>` (원본 5–321행)
  - `WebDashboardBody.kt` — `</style></head><body>` + 본문 마크업 (322–955행)
  - `WebDashboardScript.kt` — `<script>` … `</script></body></html>` (956–3039행)
  - `WebDashboardHtml.kt` — 세 조각을 `"\n"` 로 이어 `content` 구성(기존 인터페이스 유지)
- **금지**: CSS/JS/마크업 **한 글자도 수정 금지**(순수 이동).
- 완료 조건: 조립 결과가 원본 원시 문자열과 **바이트 동일**.
- 검증: 분할 생성 스크립트에서 `조립 == 원본` assert +
  `DashboardRealtimeContractTest` · `DashboardInfoMenuContractTest` · `DashboardInlineHandlerContractTest` ·
  `StorageMoveTest` · `TorrentStorageMoveContractTest` · `FaviconTest` 통과.

### Phase 2 — 순수 로직 추출 (동작 불변)
- 범위: `TorrentEngine.kt` 상단의 **최상위 함수**를 파일로 분리
  - `MagnetParser.kt` — magnet 정규식 · `base32ToHex` · `magnetInfoHash` · `validateMagnet` ·
    `sanitizeMagnetDn` · `sanitizeDnValue` · `dnValueUtf8Valid`
  - `TorrentRules.kt` — `shouldPauseAtRatio` · `isStalledTorrent` · `stallTimedOut` · `shouldAutoResume` ·
    `guardEffective` · `storageDirOf` · `torrentPollIntervalMs` · `persistedSignature` · `DuplicateTorrentException`
- 클래스 `TorrentEngine` 은 같은 파일에 그대로 둔다(Kotlin 에 partial class 가 없다).
- 완료 조건: 공개 시그니처·동작 동일, 기존 테스트 전부 통과.
- 검증: `./gradlew test` (46 테스트 파일).

### Phase 3 — 버전 올리기
- `gradle.properties`: `versionName 0.50.1 → 0.55.0`, `versionCode 36 → 37`
- 사유: v0.54(`a6a15ec`)·탭 개편(`4bef073`)·OOM 수정(`9eb870c`) 이후 **버전을 안 올려**
  두 기기 모두 `0.50.1` 로 남아 있다(버전 문자열이 최신 코드를 반영하지 않음). versionCode 상향이 있어야 새 APK 가 덮인다.
- 검증: `./gradlew assembleDebug` 후 산출 APK 버전 확인.

### 제외 (이번에 안 함)
- `TorrentEngine` 클래스 자체 분해(협력자 추출) — 위험 대비 이득 불명확.
- `RelayServer` · `SettingsRoutes` · `RelayService` · `McpServer` 분할 — 위와 같은 이유(PLAN_v0.54 결정 존중).
- JS 내부 중복 핸들러 정리 · 디자인 변경 — 동작 변경이므로 별도 PLAN.

## 2. 롤백

- 실패 시 `git revert <커밋>` 우선, 또는 `git reset --hard pre-refactor-android-web`(태그).

## 3. DoD 체크리스트

- [ ] 플랫폼 명시(android · web)
- [ ] PLAN + TODO 등록
- [ ] Phase 1 **바이트 동일** 검증
- [ ] Phase 2 `./gradlew test` 전부 통과
- [ ] Phase 3 버전 산출물 확인
- [ ] CHANGELOG 기록 + TODO 상태 갱신
- [ ] PR (본 DoD 체크)

## 4. 정직성 메모

- 이 PLAN 은 **구조 개선**만 한다. 성능·기능·버그는 다루지 않는다(바꾸면 동작 불변이 깨진다).
- 부분 성공을 "완료"라 말하지 않는다 — 못 끝낸 Phase 는 이 문서에 남긴다.
