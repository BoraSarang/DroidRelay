# PLAN v0.34 — 홈 실행상태 + HTTPS 포트 설정 (android)

> 홈 상단(ServerCard) 실행상태 표시, 설정에 HTTP·HTTPS 두 포트 표시 + HTTPS 포트 설정(기본 8443),
> 하드코딩 8080 정리 + 알림 이중 포트 버그 수정.

## 배경/원인
- 홈 `ServerCard` (`DownloadsScreen.kt:190`)에 실행 상태 표시 없음 (설정 탭에만 `●` 상태줄).
- `RelayServer.HTTPS_PORT = 8443` 하드코딩 (`RelayServer.kt:180`) — `AppSettings`·DataStore·설정 UI 어디에도 없음.
- 설정 상태줄이 HTTP 포트만 보여줘서 서버가 포트 1개만 쓰는 것처럼 보임.
- 홈 주소/QR이 `RelayService.PORT`(8080) 하드코딩 (`DownloadsScreen.kt:96,194`) — 포트 변경 시 QR 틀어짐.
- 알림 이중 포트 버그: `updateRunningNotification`이 `"$ip:$port"`를 넘기는데 `runningNotification`이 `:$PORT`를 또 붙임 (`RelayService.kt:390,396,408`).

## 목표
1. `ServerCard` 최상단에 `●` + `HTTP {port} · HTTPS {httpsPort} 실행 중/대기 중/에러`.
2. `AppSettings.httpsPort`(기본 8443) + DataStore + 설정 UI 행 + 서버 배선 + 재시작 감시.
3. `PORT` 상수 제거, 알림 문구 단일화.
4. 웹 응답(`GET /api/settings`·`/api/info`)에 `httpsPort` 포함 (확인 후 최소).

## 비범위
- 웹 대시보드 설정 탭 포트 입력 UI 없음 (app 전용으로 유지).
- TLS 인증서 재생성 불필요 (IP 기반, 포트 무관).
- DataStore 마이그레이션 불필요 (신규 키 기본값 처리, `CURRENT_VERSION=1` 유지).

## 설계
### 모델·제약 (T-1011)
- `AppSettings.httpsPort: Int = 8443`, Keys `HTTPS_PORT("https_port")`, 읽기 `clampPort` 재사용, `setHttpsPort()` (1024~65535 clamp).
- 충돌 규칙 순수함수: `SettingsConstraints.validPorts(http, https)` → http==https면 false.
- 신규 에러코드 `E-AND-SRV-0111` + `error_message_ko.json` 등록.
- 테스트: clamp 경계, 충돌 판정, `ServerState` httpsPort 기본값.

### 서버 배선 (T-1012)
- `RelayServer(ctx, port, httpsPort = 8443)`. `:201` 기동 로그·`:253` 충돌회피·`:336` 리다이렉트 타깃을 인스턴스 값으로.
- `ServerState`에 `httpsPort` 추가. `RelayService` 생성 3곳 + 상태 갱신 5곳에 전달, 감시 `lastPort` → (http, https) 쌍 비교.
- `[INFO] [FEATURE] HTTPS 포트` 로그 1개 이상.

### 설정 UI (T-1013)
- HTTP 행 패턴 그대로 HTTPS 행 추가 (입력+랜덤+적용). 충돌 시 적용 거부 + `E-AND-SRV-0111` 로그.
- 상태줄 → `"{http}(HTTP) · {https}(HTTPS) 실행 중"`. 클릭 주소 HTTP 유지.

### 홈·알림 (T-1014)
- `ServerCard` 최상단 상태 행 (`serverState` collect). 주소/QR 설정 포트 기반 (`remember(port)`).
- `runningNotification(ip, port)` 단일화 → `"http://$ip:$port"`. `PORT` 상수 삭제. 390px 확인.

### 웹 응답 (T-1015)
- `SettingsRoutes.kt:28` 주변 + `/api/info` 응답 확인 후 `httpsPort` 추가.

## 검증 게이트
1. `./build_and_run.sh test android` GREEN.
2. `./build_and_run.sh lint android` GREEN (kts 파서 기존 이슈 제외).
3. `./build_and_run.sh debug android` + 실기기 설치.
4. 실기기: 포트 변경→재시작, `https://폰IP:8443` 접속, 홈 dot, 알림 문구.
5. DebugPanel ERROR 0 + `[FEATURE]` 로그.
6. TODO + CHANGELOG + 세션 로그 + `error_message_ko.json` 갱신.

## PERF/CACHE 영향
- 설정값 배선만, 전송 경로 무변경 → 예산 영향 없음.
