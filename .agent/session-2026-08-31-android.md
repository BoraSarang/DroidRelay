# 세션 로그 2026-08-31 (Android) — 웹 설정 미러 1차+2차 + MD3 디자인 개편 (v0.16.x)

## 세션 요약
- **무엇을/플랫폼**: [ANDROID] 웹 대시보드에만 있던 설정을 앱에 미러링 **v0.16.0**(전역 속도 제한·토렌트 고급 4종·가드 보호) → **MD3 디자인 개편 v0.16.1** → **2차 v0.16.2**(스케줄 Cron+유효성/Debrid/터널/MCP 도구 5종/기본값 복원 AlertDialog) → **v0.16.3 다운로드 QR 확대/축소 토글**(QR 탭 → 반투명 오버레이 확대 + scale 모션, 재클릭/닫기 버튼으로 축소, QR 비트맵 1회 캐시 재사용). RSS는 별도 저장소로 분리 검토.
- **빌드**: BUILD SUCCESSFUL — `testDebugUnitTest` GREEN, `ktlintCheck` GREEN(`-x` 우회 플래그), `assembleRelease` OK. 실기기 `R5CT215F4QK` 인플레이스 v0.16.0(18)→v0.16.1(19)→v0.16.2(20)→**v0.16.3(21)**, `dumpsys` versionName **0.16.3** 확인.
- **PERF/CACHE**: QR 인코딩(512×512)을 재컴포지션마다 하던 것을 `remember` 1회로 캐시(축소/공유/확대 공유) — UI 추가지만 오히려 CPU 절감.
- **남은TODO**: ① v0.16.3 실기기 동작 확인(QR 확대 토글·신규 설정 섹션) — **사용자 직접** ② git 커밋(v0.16.2·v0.16.3 포함, 경고: 루트에 `--viewport` PNG untracked 존재 — 제외 필요) + push(목적지 확인 필요) ③ AGENTS.android.md 재검토.
- **전달로그**: 설정 저장 단위 Mbps×1_048_576(0=무제한). `torrentListenPort` 변경은 토렌트 엔진 재시작 필요. 디버그 진입은 5연속 탭 → topBar 아이콘. Cron 유효성은 `CronParser.isValid`. 기본값 복원은 파괴적 동작이라 AlertDialog 확인 필수. QR은 `DownloadsScreen`에서 `remember`로 비트맵 1회 생성해 ServerCard·공유·오버레이가 공유.
- **문서갱신**: docs/TODO.md T-916~**T-928** ✅, `docs/plans/PLAN_v0.16_settings-mirror_android.md`, CHANGELOG v0.16.0~**v0.16.3** 기재, 세션 로그 갱신.
- **커밋**: v0.16.0~0.16.1 **커밋 5개 완료**(branch `feat/android-v014-stability`). **v0.16.2·v0.16.3 미커밋** (사용자 요청 시).
- **큐상태**: 없음.
- **E2E**: 단위테스트 GREEN(신규 테스트 없음, 설정 UI만). 실기기 마무리 확인은 사용자 담당.

---

# 세션 로그 2026-08-31 (Android) — v0.14 안정성 7종 + 웨일 HTTPS 접속 복구

## 세션 요약 (이전)
- **무엇을/플랫폼**: [ANDROID] 브랜치 `feat/android-v014-stability`(main `0cfba15`에서 생성)에서 **7개 안정성/기능 이슈** 구현 + 확정, **iPad 웨일 HTTPS 접속 불가** 원인 진단 및 HTTP 폴백으로 복구. 빌드·ktlint·단위테스트·릴리즈 빌드·기기 재설치(v0.14.0)까지 완료.
- **빌드**: BUILD SUCCESSFUL — `compileReleaseKotlin` OK, ktlint(소스) OK, `testDebugUnitTest` GREEN, `assembleRelease` OK, 기기 `R5CT215F4QK`에 v0.14.0 재설치(`adb install -r`).
- **PERF/CACHE**: 해당 없음(안정성 위주). watchdog는 1분 주기 헬스체크(`isHealthy()` 127.0.0.1/api/info)로 서버 응답 없으면 `restart()`. 토렌트 자동중단은 시더 부재 시 `torrentMinSeedWaitSec`(기본 0=꺼짐) 대기 후 `pause`.
- **전달로그**: **웨일(Chromium)은 HTTP→HTTPS 강제 리다이렉트에서 자체서명 mkcert 인증서(`NET::ERR_CERT_AUTHORITY_INVALID`)를 하드 차단**. SAN은 `DNS:localhost, IP:10.64.228.42, IP:127.0.0.1`(핫스팟 swlan0 IP 미포함). → 새 설정 `forceHttpsRedirect`(기본 false=HTTP 폴백 허용) 도입으로, 꺼짐이면 LAN HTTP를 그대로 서빙해 인증서 미신뢰 브라우저도 접속 가능. 켜짐이면 기존처럼 301→HTTPS(8443). 사파리는 경고 후 진행 가능, 웨일/크롬은 차단 → 토글로 해결. (v0.15.x에서 웨일 접속 복구 가이드·모바일 개선 계속 진행)