# PLAN v0.32 — Content-Disposition 파일명 우선 (android)

> GitHub Release-Assets 같은 서명 URL에서 `file-ts.githubusercontent.com-*` 폴백 이름이 되는 버그 수정. 자동 추론만, 수동 파일명 UI 추가 없음.

## 배경/원인
- `JobsRepository.filenameFromUrl()` (`JobsRepository.kt:104`)는 `?` 이전 path만 보고 이름을 결정.
- 문제 URL path 끝은 확장자 없는 UUID (`e898f508-...`) → `!contains('.')` 로 범용 판정 → `file-<host>-<ts>` 폴백.
- 진짜 파일명이 있는 `response-content-disposition` 쿼리 파라미터와 HTTP 응답 `Content-Disposition` 헤더를 모두 무시.
- `DownloadEngine.enqueue()` (`DownloadEngine.kt:109`)는 요청 전에 이름을 확정하고, `runOnce()` (`DownloadEngine.kt:231`)는 응답 헤더 교정이 없음.

## 목표
1. 쿼리 `response-content-disposition` 안의 `filename=` / `filename*=` 우선 파싱.
2. HTTP 응답 `Content-Disposition` 헤더로 1회 교정 (폴백 이름일 때만).
3. 기존 정상 path/폴백 동작 회귀 없음. UI 변경 없음.

## 비범위
- `POST /api/jobs` `filename` 옵션 추가 없음.
- 앱/웹 다운로드 입력 UI 변경 없음.
- 비디오 경로 변경 없음. 신규 에러코드 없음.

## 설계
### 우선순위 (filenameFromUrl)
1. 쿼리 `response-content-disposition` → `filename*=` (RFC 5987) 우선, 없으면 `filename=` → 디코드 → 정제 → 반환.
2. 일반 쿼리 `filename=` / `file=` / `name=` (값에 `.` 포함 시만).
3. 기존 path 로직 (디코드 + GENERIC_NAMES + 확장자 체크).
4. 기존 폴백 `file-$host-$ts`.

### 응답 헤더 교정 (DownloadEngine.runOnce)
- `res.header("Content-Disposition")` 파싱 (`parseDispositionFilename`, `filename*=` 우선).
- 현재 이름이 폴백 패턴(`file-*-MMdd-HHmmss`)일 때만 `uniqueName()` 으로 갱신 + `.part` rename (바디 읽기 전 1회).
- 정상 path 이름은 덮어쓰지 않음.
- `[INFO] [FEATURE] Content-Disposition 교정` 1개 이상, 실패는 기존 재시도 경로 유지.

### 안전
- 정제: 제어문자 제거, `\\/:*?"<>|` + 공백 → `_`, `..`/절대경로 무력화, 앞뒤 `./_/공백` trim, 120자 cap, 한글 유지.
- URL 전체 로그 금지 (서명 쿼리 토큰 노출 방지) — 파일명만 로그.

## 테스트
- `JobsRepositoryTest`: 문제 URL → `Godot_v4.7.2-stable_export_templates.tpz`, `filename*` 한글 복원, 일반 쿼리 회귀, 범용 폴백 회귀.
- `ContentDispositionTest` 확장 또는 `DispositionParseTest`: `filename=`/`filename*=`/따옴표/대소문자/공백.
- 스텁 `HttpServer` (가능 시): 헤더 교정 → `.part` rename + Repository 갱신.

## 검증 게이트
1. `./build_and_run.sh test android` (smoke+unit) GREEN.
2. `./build_and_run.sh lint android` (ktlint) GREEN.
3. `./build_and_run.sh debug android` assemble+설치 (디바이스 없으면 APK까지).
4. DebugPanel ERROR 0 + `[FEATURE]` 로그 확인.
5. TODO T-1004 + CHANGELOG + 세션 로그.

## PERF/CACHE 영향
- 이름 파싱만, 전송 경로 무변경 → 예산 영향 없음.
