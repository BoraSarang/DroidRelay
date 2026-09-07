# 세션 로그 2026-09-07 (Android) — 폴더 다운로드 속도 개선 (T-939)

## 세션 요약
- **무엇을/플랫폼**: [ANDROID] 폴더 다운로드(`/dl-folder/`)가 600MB에 비정상적으로 느리다는 제보 → 원인 확정: `ZipOutputStream` 기본값(DEFLATED 레벨 6)으로 기압축 영상까지 재압축 → 폰 CPU 병목. 목표(모아서 1개 zip 다운로드) 유지한 채 압축만 제거.
- **수정**: `StorageRoutes.kt` — `zip.setLevel(0)` 패스스루(STORED 제외: size/crc 사전 스캔 2회 읽기 필요) + 버퍼 64KB→256KB + `BufferedOutputStream` + 완료 계측 로그(파일 수·원본 MB·소요 s·MB/s). (PLAN_v0.17_dl-folder-speed_android.md, TODO T-939)
- **빌드**: compileDebugKotlin GREEN(기존 deprecation 경고 1건) + `./gradlew test` GREEN + `assembleDebug` 후 `R5CT215F4QK` 설치 Success.
- **PERF/CACHE**: CPU deflate 제거 → 전송 속도는 네트워크 상한까지. ZIP 크기는 원본 합+헤더 수준(영상은 차이 없음).
- **검증/재현**: ktlint 전체는 `app/build.gradle.kts` 파싱 실패로 RED — 기존 미커밋 리팩토링 분(본인 변경 아님)이라 메인 소스 컴파일+테스트로 대체 검증. 실기기 전송 측정은 서버 미기동(커넥션 refused)이라 미실시 — 앱에서 서버 시작 후 📦 다운로드 1회 + logcat `폴더 다운로드 완료` 라인 확인 필요.
- **남은TODO**: ~~① 실기기 before/after 시간 측정~~ → 완료(서버 54.1MB/s·맥 71MB/s, 정상 확정). ② T-937/T-938 검증과 함께 0.17.0 bump 결정 ③ `?? --viewport` 정리.
- **전달로그**: `adb reverse` 테스트 잔재는 제거함. 폴더명에 성인 콘텐츠 표기 다수 — 언급 불필요.
- **문서갱신**: PLAN_v0.17_dl-folder-speed_android.md, TODO T-939, CHANGELOG 0.17.0에 T-939, 세션 로그.
- **큐상태**: 없음.
- **마무리(추가)**: v0.17.0 확정 — versionCode 25·versionName 0.17.0, assembleDebug GREEN 후 커밋 `59bf5f0` + `origin/feat/android-v014-stability` 푸시. 태그/릴리즈 APK는 미진행.

## v0.18 세션 (T-940~942 구현 + T-943 검증 중)
- **T-940**: `/stream/` Range inline + `StreamContentType` + 웹 ▶/video 오버레이. compile + JS node --check 통과.
- **T-941**: SEND 필터(singleTop) + URL/magnet 추출 → 등록. `dumpsys` 등록 확인. 실전송 E2E는 사용자 동작 대기(잡 생성 부작용 회피).
- **T-942**: `setFileSelection` + `prioritizeFiles` + 재매핑 복원 + files API + 웹/앱 체크박스. compile 통과.
- **T-943**: testDebugUnitTest GREEN(신규 3건 포함) + assembleDebug 설치 Success. 서버 미기동이라 /stream 실전송·선택 다운로드는 E2E 대기. CHANGELOG 0.18.0(미배포) 기록.

## v0.18 E2E 완료 (서버 기동 후)
- **T-940 PASS**: 10KB 테스트 파일 업로드 → `Range: bytes=0-1023` → `206` + `Content-Range: bytes 0-1023/10240` + `video/mp4` + inline disposition, 1024B 내용 정확 일치. 전체 GET 200·전체 일치. `[FEATURE] 스트리밍 시작` 로그 2건 확인. 테스트 파일 삭제+휴지통 비우기로 정리.
- **T-941**: SEND 필터 `dumpsys` 등록 확인 유지. 실전송(잡 생성 부작용)은 사용자 몫.
- **T-942**: live torrent 0건이라 POST 미실시. 다음 토렌트 받을 때 상세 모달 체크박스로 확인 필요.
- 참고: 아침의 영상 폴더 4건이 기기에서 사라짐(이동/삭제 추정) — E2E는 테스트 파일로 대체.

## v0.19 완료 (T-944~948)
- **T-944**: DavRoutes 신규(OPTIONS/PROPFIND/GET). 버그 1건 발견·수정: PROPFIND 루트 href가 lexical relativeTo로 `..` 탈출 → canonical 기준 수정 후 `/dav/` 확인.
- **T-945/946**: StorageJanitor(쿼터·분류) + 설정 2종 + 완료 후킹 3엔진 + 앱/웹 UI + reset 포함.
- **T-947**: normalizedUrl/findDuplicateUrl + 409(E-AND-DOWN-1006) + 전 경로 적용.
- **검증**: unit 4건 GREEN, JS 2블록 OK, assembleDebug 설치. E2E PASS(DAV 206·PROPFIND·설정 roundtrip·409 fragment 정규화). 잔재 정리(잡·파일·휴지통). CHANGELOG 0.19.0(미배포).

## v0.20 완료 (T-949~954)
- **구현**: ThumbManager+/thumb/·TorznabClient+/api/search+torrentUrl·ShareRepository+/api/share+/s/·게스트(설정+인터셉트+앱UI)·위젯+QS타일(+ServerToggle).
- **E2E PASS**: files POST 200(선택 유지·다운로드 계속)·공유 전체수명(발급→일치→삭제→404)·썸네일 실패 404·검색 미설정 502·search 설정 조회.
- **대기**: 진짜 영상 썸네일(토렌트 1% 진행 중)·게스트 매트릭스(웹인증 API 없음, 앱에서 직접)··위젯 탭.
- CHANGELOG 0.20.0(미배포), TODO 전부 ✅.

## v0.21 완료 (T-955~960)
- **감사**: 53필드 → 적용 49·데드 4. 잘린 설정 잔재·고아 키 0건.
- **배선**: 비율 강제(shouldPauseAtRatio 순수함수+테스트 5건)·DHT 토글·저장경로·PEX 문구·프리셋(앱/웹).
- **E2E**: DHT 정지/시작 로그·roundtrip·토렌트 26% PASS. 사용자 비율 0(제한 없음)·업로드 32는 기존 설정 유지.
- CHANGELOG 0.21.0(미배포), TODO 전부 ✅.
