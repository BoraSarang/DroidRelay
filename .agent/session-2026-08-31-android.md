# 세션 로그 2026-08-31 (Android) — 웹 설정 미러 1차 + MD3 디자인 개편 (v0.16.x)

## 세션 요약
- **무엇을/플랫폼**: [ANDROID] 웹 대시보드에만 있던 설정을 앱에 미러링 **v0.16.0**(전역 속도 제한·토렌트 고급 4종·가드 보호 — 백엔드 setter는 기존 완비, UI만 추가) → 이어서 **MD3 디자인 개편 v0.16.1**(TopAppBar 도입·디버그 패널 Root 이전·Downloads 단일 LazyColumn·Torrent 중첩 Scaffold 제거·카드색 통일·이모지 제거·Theme 표면 토큰/셰이프 정렬). 1차 완료 후 **2차(스케줄/Debrid/터널/MCP/기본값 복원) 확장 예약**, RSS는 별도 저장소로 분리 검토.
- **빌드**: BUILD SUCCESSFUL — `testDebugUnitTest` GREEN, `ktlintCheck` GREEN(`-x :app:ktlintKotlinScriptCheck -x :app:runKtlintCheckOverKotlinScripts`), `assembleRelease` OK. 실기기 `R5CT215F4QK`에 v0.16.0(18)→v0.16.1(19) 인플레이스 `adb install -r` Success, `dumpsys`로 versionCode=19/versionName=0.16.1 확인.
- **PERF/CACHE**: 해당 없음(UI+설정만, 성능 예산 영향 없음). dynamicColor(Material You)는 "기기 테마 따라감 유지".
- **남은TODO**: ① v0.16.x 실기기 동작 확인(설정 저장·디자인) — **사용자 직접** ② 2차 설정(스케줄/Debrid/터널/MCP/기본값 복원, RSS는 분리) ③ git 커밋 분리(v0.14~v0.16 변경이 미커밋으로 main에 누적 — 사용자 요청 시) ④ AGENTS.android.md 재검토.
- **전달로그**: 설정 미러의 저장 단위는 Mbps×1_048_576(bps, 웹과 동일), 0=무제한. `torrentListenPort` 변경은 토렌트 엔진 재시작 필요(Text). 파일 저장 경로는 `File.exists/mkdirs`로 테스트. 디버그 진입을 "5연속 탭"에서 topBar 아이콘으로 변경 — 기존 규칙 10.2(다중 선택 복사)는 `ui/DebugPanel.kt`로 이전 유지.
- **문서갱신**: docs/TODO.md T-916~T-921 ✅ + 2차 예약 메모, `docs/plans/PLAN_v0.16_settings-mirror_android.md` 생성, CHANGELOG v0.16.0/0.16.1 기재, 세션 로그 갱신.
- **큐상태**: 없음.
- **E2E**: 단위테스트 GREEN(신규 테스트 없음, UI 개편). 실기기 마무리 확인은 사용자 담당.

---

# 세션 로그 2026-08-31 (Android) — v0.14 안정성 7종 + 웨일 HTTPS 접속 복구

## 세션 요약 (이전)
- **무엇을/플랫폼**: [ANDROID] 브랜치 `feat/android-v014-stability`(main `0cfba15`에서 생성)에서 **7개 안정성/기능 이슈** 구현 + 확정, **iPad 웨일 HTTPS 접속 불가** 원인 진단 및 HTTP 폴백으로 복구. 빌드·ktlint·단위테스트·릴리즈 빌드·기기 재설치(v0.14.0)까지 완료.
- **빌드**: BUILD SUCCESSFUL — `compileReleaseKotlin` OK, ktlint(소스) OK, `testDebugUnitTest` GREEN, `assembleRelease` OK, 기기 `R5CT215F4QK`에 v0.14.0 재설치(`adb install -r`).
- **PERF/CACHE**: 해당 없음(안정성 위주). watchdog는 1분 주기 헬스체크(`isHealthy()` 127.0.0.1/api/info)로 서버 응답 없으면 `restart()`. 토렌트 자동중단은 시더 부재 시 `torrentMinSeedWaitSec`(기본 0=꺼짐) 대기 후 `pause`.
- **전달로그**: **웨일(Chromium)은 HTTP→HTTPS 강제 리다이렉트에서 자체서명 mkcert 인증서(`NET::ERR_CERT_AUTHORITY_INVALID`)를 하드 차단**. SAN은 `DNS:localhost, IP:10.64.228.42, IP:127.0.0.1`(핫스팟 swlan0 IP 미포함). → 새 설정 `forceHttpsRedirect`(기본 false=HTTP 폴백 허용) 도입으로, 꺼짐이면 LAN HTTP를 그대로 서빙해 인증서 미신뢰 브라우저도 접속 가능. 켜짐이면 기존처럼 301→HTTPS(8443). 사파리는 경고 후 진행 가능, 웨일/크롬은 차단 → 토글로 해결. (v0.15.x에서 웨일 접속 복구 가이드·모바일 개선 계속 진행)