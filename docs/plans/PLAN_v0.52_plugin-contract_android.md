# PLAN v0.52 — adb 플러그인 계약 제공자 구현 (T-1095)

> 생성일: 2026-10-08 | 플랫폼: android | 작성자: OpenCode
> 계약 문서: `docs/PLUGIN_CONTRACT.md` v1 (RelayConsole 전달용, 문서 우선 작성됨)

## 1. 목표 (1줄)

SpotShift 계약 패턴으로 DroidRelay 제공자(프로브+액션 4종+결과/이벤트 로그)를 구현하고 릴리즈에서도 계약 로그가 보이게 한다.

## 2. 범위

- 플랫폼: android (Kotlin, `apps/android/`)
- 기술 스택: BroadcastReceiver(goAsync) + MainActivity(singleTop) 인텐트 + DataStore 설정 + `android.util.Log` 직접 출력. 선정 이유: 기존 진입점(`RelayService.start/stop`, `DownloadEngine.enqueue`, `TorrentEngine.addMagnet`) 재사용이라 신규 권한·서버 변경 없음.
- design_profile: 해당 없음 (UI는 설정 토글 1행만, 기존 MD3 유지)
- 범위 밖: `announce` 액션 (T-1090 미머지라 v1.1로 연기, 계약 문서에 명시)

## 3. 문서 위치

- PLAN: 본 문서
- TODO: docs/TODO.md v0.52 절 T-1095 등록
- 계약: docs/PLUGIN_CONTRACT.md v1 (상태 `구현됨`으로 갱신)
- CHANGELOG: Unreleased 절 1항

## 4. 성능 예산

- budgets.json 참조, override 없음. 프로브·액션·이벤트 모두 이벤트 구동 1회성이라 상시 폴링·배터리 영향 없음.
- 제공자 측 폴링 추가 금지 (완료 분기 옆 1회 emit만).

## 5. 에러 코드

- 신규: `E-AND-PLG-0001` 연동 거부(OFF), `E-AND-PLG-0002` 입력 무효(빈값·비URL·비magnet), `E-AND-PLG-0003` 실행 실패(원인 포함). `error_message_ko.json` 등록.

## 6. 빌드 & 검증 계획

- `./build_and_run.sh test android` (unit, `PluginContractTest` 신규 포함)
- `./build_and_run.sh lint android` (ktlint)
- `./build_and_run.sh debug android`는 실기기 연결 시 (무선 adb 우선). 미연결이면 assembleDebug까지.
- DebugPanel 검증: 계약 로그가 릴리즈 logcat(`logcat -d -s DroidRelay:*`)에 보이고, 디버그 패널에도 미러되는지.
- 계약 회귀 테스트: 형식 문자열을 바꾸면 테스트가 깨지게 고정 (필드 순서·키 변경 = 버전 올림).

## 7. 핵심 함정 (실측 기반)

- `DebugLogger.write`는 릴리즈에서 조기 반환이라 계약 로그가 사라진다. 계약 3종(`[PLUGIN]`·`[REMOTE]`·`[EVENT]`)은 `android.util.Log` 직접 출력 + 디버그 패널용 `DebugLogger` 미러를 병행한다.
- `BootReceiver` 이중 `finish()` 전례 (T-1088): `PluginProbeReceiver`도 `goAsync` 1회 `finish()`만. `finally` 1곳.
- 복원 전 저장 금지 (T-1085): 설정 읽기는 `firstBlocking`/읽기 전용이라 저장 경로 없음. 문제없음.
