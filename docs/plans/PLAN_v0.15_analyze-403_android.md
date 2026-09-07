# PLAN v0.15.0 — 비디오 분석 403 조기 노출 + fetch 최적화 (android)

> 작성: 2026-08-31 · 브랜치: `feat/android-v014-stability` (기존 워킹트리에 얹는 단일 관심사)
> 성격: 중형 수정 (StreamDetector 리팩터링 + 테스트 + 문구)
> 플랫폼: Android (앱 + 웹 대시보드 JS)

---

## 1. 배경 / 문제

wowstream2.cloud m3u8 진단 중 발견한 코드 결함.

- `StreamDetector.fetch`는 403을 `VideoException(E-AND-VID-0200)`으로 정상적으로 던진다.
- **그러나** `parseManifestVariants`(92), `resolveSegmentsCount`(153), `mediaDurationMsFromUrl`(143)이 이 예외를 `catch`로 삼켜 0/빈 목록을 반환한다.
- 결과: 직접 m3u8 입력 시 분석이 "성공"처럼 보이고, 이후 `VideoApi.create`가 같은 403 URL을 **4~5회 재fetch**하며 조용히 실패 → FFmpeg로 보내 `E-AND-VID-0202("다운로드 실패")`만 표시.
- 사용자 경험: "분석은 됐는데 다운로드 실패" — 원인 불명.

## 2. 변경 범위 (사용자 승인: M1+M2+M4)

| ID | 변경 | 핵심 |
|----|------|------|
| M1 | 직접 매니페스트의 403/네트워크 실패 즉시 전파 | `parseManifest` 신설, 실패 삼킴 제거, fetch 403 → `E-AND-VID-0206` |
| M2 | fetch 중복 제거 + 분석 결과 재사용 | analyze에서 fetch 1회(마스터면 +첫 variant 1회), `Found.durationMs`, `VideoApi.create`의 재분석 재사용 |
| M4 | 에러 코드/문구 | `E-AND-VID-0206` 신설, WebAssets 오류 안내 보강 |

제외(M3): Referer 전달 — wowstream2는 OkHttp TLS 봇 점수로 원천 불가, 스코프 아웃.

## 3. 설계

### 3.1 StreamDetector.kt
```kotlin
data class ManifestResult(
    val variants: List<Quality>,
    val segments: Int,        // HLS 세그먼트 전체 (미디어=실측, 마스터=첫 variant)
    val durationMs: Long,     // 총 재생 시간 (EXTINF 합, 마스터=첫 variant)
    val mediaBody: String?,   // 미디어 플레이리스트 본문 (null이면 마스터라 variant 추적 필요)
)

fun parseManifest(manifestUrl: String): ManifestResult
// - fetch(manifestUrl) → 403/오류 시 VideoException 그대로 전파 (삼키지 않음)
// - 본문이 미디어(#EXTINF 존재)면 그대로 계측
// - 마스터면 첫 variant만 1회 추가 fetch로 segments/duration 계측
// - fetch 접근 1회 + (마스터 시) variant 1회
```
- `Found`에 `durationMs: Long = 0` 필드 추가.
- `analyze` 직접 매니페스트 경로(60-64)와 페이지 스니핑 경로(86-88)가 모두 `parseManifest` 사용.
- `parseManifestVariants`/`resolveSegmentsCount`/`mediaDurationMsFromUrl` 제거 (사용처는 `VideoApi.create`뿐, `parseManifest`로 통합).
- `fetch` 403 코드 `E-AND-VID-0200` → `E-AND-VID-0206` (메시지 갱신).
- `parseHlsMaster`/`parseDashManifest`/`countHlsSegments`/`playlistDurationMs`는 본문 주입 기반 유지(테스트 커버됨).

### 3.2 VideoApi.kt
```kotlin
val found = StreamDetector.analyze(url)          // 403 → 즉시 VideoException
val stream = streamUrl?.ifBlank { found.url } ?: found.url
val m = if (stream == found.url)
    ManifestResult(emptyList(), found.segmentsTotal, found.durationMs, null)
else
    StreamDetector.parseManifest(stream)          // 선택 variant만 1회 fetch
val segments = m.segments; val durationMs = m.durationMs
```
- 분석 대상과 다운로드 대상이 같으면 재fetch 없음. (403 사이트: 조용한 3회 실패 → 1회 실패로 즉시 종료)

### 3.3 에러 코드/문구 (M4)
- `error_message_ko.json`: `E-AND-VID-0206` 신설. `E-AND-VID-0200`은 웹페이지 스니핑 실패용으로 유지.
- `WebAssets.analyzeVideo` catch에서 0206 감지 시 차단 안내 박스 추가(구조 변경 없음).
- `RelayServer` `/api/video/analyze`·`/create`는 VideoException → 422 계약 그대로 (변경 없음).

## 4. 검증 게이트

1. **TDD** — `StreamDetectorTest`에 로컬 `HttpServer`(JDK 내장, 신규 의존성 불필요) 스텁:
   - 403 매니페스트 → `analyze`가 `VideoException("E-AND-VID-0206")`
   - 200 미디어 플레이리스트 → `segmentsTotal=3`, `durationMs=23540`, kind=stream
   - 200 마스터 → 첫 variant 팔로우로 segments/duration 계측
2. `./gradlew :app:testDebugUnitTest` (전체 GREEN)
3. `ktlintCheck` (KotlinScript 우회 플래그)
4. WebAssets JS `node --check`
5. `assembleRelease` → 실기기 `R5CT215F4QK` 릴리즈 인스톨 (인플레이스, v0.15.0/versionCode 16)
6. 실기기 검증(사용자): 403 사이트 즉시 0206 안내 + 정상 m3u8 회귀

## 5. 성능/캐시 영향
- 직접 매니페스트 분석: 요청 수 2~3회 → 1~2회 (perf 상향). 403 사이트는 조용한 3회 실패 → 1회 즉시 종료.

## 6. 문서/후속
- `docs/TODO.md` T-907~910, `docs/CHANGELOG.md` v0.15.0, `.agent/session-2026-08-31-android.md` 후속 세션 추가.
- 커밋: 사용자 요청 시 분리 (①v0.14 7종 ②T-906 ③v0.15).

## 7. 위험
- `parseManifest`의 마스터-첫-variant 팔로우는 variant fetch 1회를 추가 — 스트림이 변동형(VOD 아님)이면 추정치일 뿐이라 진행률 근사값 오차 허용 (기존 동작과 동일).
- HttpServer 정리(try/finally) 미비 시 테스트 포트 유출 — `@After`/자동 close.