# PLAN v0.53 — Plugin SDK v2 승격 (T-1096)

> 생성일: 2026-10-08 | 플랫폼: android | 작성자: OpenCode
> 규칙 원천: RelayConsole `docs/PLUGIN_SDK.md` v2 (유일 원천 — 본 PLAN·계약문서는 구현 기록만)
> 전제: T-1095 (v1 제공자) 구현·푸시됨. 본 작업은 v1 → v2 승격이다.

## 1. 목표 (1줄)

SDK v2 L2 체크리스트를 충족하고 DroidRelay 제공자를 v2로 승격한다 (프로브 v2+`appVersion`, 메타 Provider, 브로드캐스트 액션, EVENT `level=`, 단일 emit).

## 2. 범위

- 플랫폼: android (Kotlin, `apps/android/`)
- v1 → v2 차이 7건:
  1. 프로브 수신기 `.plugin` → `.receiver` (§2.2)
  2. 프로브 `version=2` + `appVersion` 필수 (§2.2)
  3. 메타데이터 Provider 신규 `content://<pkg>.plugin/info` (§3)
  4. 액션 호출 브로드캐스트 전환 (`PLUGIN_ACTION` cmd/arg), MainActivity 전면 경로 제거 (§4.3)
  5. EVENT `level=` 필수 (완료 info·실패 warning) (§6)
  6. 단일 emit — `DebugLogger` 미러 제거 (§8 이중로그 실측 지적)
  7. 파일명 공백 sanitize (§5.2 값 규칙 — 값은 공백 전까지)
- 범위 밖: `announce` (T-1090 대기 유지), 아이콘 제공 (`iconBase64` 빈값 → 소비자 폴백)

## 3. 문서 위치

- PLAN: 본 문서
- TODO: docs/TODO.md v0.53 절 T-1096 등록
- 계약: docs/PLUGIN_CONTRACT.md — 규칙은 SDK 원천을 가리키고 구현 기록만 유지
- CHANGELOG: Unreleased 절 1항

## 4. 성능 예산

- budgets.json 참조, override 없음. 브로드캐스트·Provider 모두 1회성, 상시 폴링 없음.

## 5. 에러 코드

- 기존 `E-AND-PLG-0001~0003` 유지 (SDK §5.1 `E-*-PLG-*` 적합 예시로 명시됨).

## 6. 빌드 & 검증 계획

- unit (`PluginContractTest` v2 벡터로 갱신 + actionsJson·sanitize 신규)
- ktlint + assembleDebug + 실기기: 프로브 v2 줄·`content query` 1행·브로드캐스트 액션 전면 전환 없음(`brought to the front` 0건)·거부 경로
- 릴리즈 서명 APK가 아니므로 §8 릴리즈 확인은 debug logcat 동등 경로로 갈음 (직접 Log 출력은 빌드 타입 무관)

## 7. 함정

- Provider `query()`는 메인 스레드 호출 가능 — DataStore 읽기는 `firstBlocking`이 블로킹이므로 `runBlocking` 단시간 사용, 실패 시 기본값 (죽지 않는다).
- MainActivity 제거 시 공유받기·OPEN_TAB은 유지 (플러그인 분기만 삭제).
