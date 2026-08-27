# 세션 로그 2026-08-27 (Android + Web) — v0.10.1 디버그 시스템 완료 + v0.10.2

## 세션 요약
- **v0.10.1**: 디버그 시스템 부트스트랩 전면 구현 + 로그 증강 ✅ 빌드·설치·API 검증 완료
- **v0.10.2**: 웹 정보 바 리디자인(온도/배터리/대기중) + RSS 진단/버그수정 + 보관함↔설정 디자인 통일 + TUNNEL_GUIDE.md ✅
- **구현 범위**: DebugLogger 확장, 디버그 API 세트, 웹 디버그 패널(별도 페이지), API 호출 트래킹, 안드로이드 플로팅 오버레이, 8개 컴포넌트 로그 증강
- **빌드**: BUILD SUCCESSFUL (2회), 기기 설치 완료, 모든 디버그 API 응답 검증
- **PERF/CACHE**: 디버그 로그 링 버퍼 300줄 유지, API 호출 버퍼 별도 분리(동일 300줄)

## 구현 내역

### DebugLogger 확장
- MAX_LINES=300 유지, `api()`/`apiLines()`/`apiDump()`/`apiCount()` 신설
- API 호출 버퍼는 buf와 별도 격리 (로그 300줄 압박 방지)

### 디버그 API 세트 (RelayServer.kt)
- `GET /api/debug/logs?lines=&level=&tag=&text=` (필터 지원)
- `GET /api/debug/api-calls?limit=` / `GET /api/debug/status`
- `POST /api/debug/clear` / `GET /api/debug/overlay` / `POST /api/debug/overlay/toggle`
- API 호출 자동 기록: `intercept(ApplicationCallPipeline.Call)` + 명시적 `proceed()` 후 status 캡처 (`call.request.httpMethod` 접근 불가 → method "?" 표기)

### 웹 디버그 패널 (/debug)
- 독립 HTML 페이지 (`WebAssets.debugHtml`), 다크 테마, 1초 폴링
- 로그/API 호출 탭, 레벨·태그·텍스트 필터, 일시정지/재개, 복사, 내보내기(파일 저장)
- 설정 사이드바 "🐛 디버그" 섹션 — 패널 열기 + 상태 표시

### Android 플로팅 오버레이
- `DebugOverlayService.kt` — TYPE_APPLICATION_OVERLAY, 드래그 이동, 탭 시 일시정지, 1초 폴링
- `SYSTEM_ALERT_WINDOW` 권한 + AndroidManifest 서비스 등록
- 설정에서 권한 요청 + 시작/중지 토글

### 디버그 로그 증강 (8개 컴포넌트)
- McpServer: 요청진입/도구실행/완료+소요시간/권한 차단
- TunnelManager: 설치 감지 결과, 인터페이스 개수, Tailscale IP 매칭, 상태 조회 상세
- StorageDetector: getExternalFilesDirs 개수, 마운트 발견(경로/여유GB), bestPath 결과
- GuardDaemon: 30초마다 센서 수치 (thermal/battery/storage vs 임계치)
- DebridClient: API URL 호출, 응답 code/bodyLen/소요시간, 계정 확인
- SchedulerManager: 크론 파싱, 각 제약 설정 여부, JobScheduler 등록 결과(0=실패), 제약 체크 상세
- ScheduleJobService: 잡 ID, 제약 결과, 스케줄 건너뜀 사유
- RelayService: 컴포넌트 초기화/시작/정리 로그

## v0.10.2 — 정보 바 + RSS 진단 + 디자인 통일

### 웹 정보 바 리디자인 (WebAssets.kt)
- 좌/우 고정 레이아웃: 좌(가변) = 진행 N건 + 총 속도(0 시 회색 "대기중..."), 우(고정) = 저장공간 여유 · 온도·배터리(`/api/guard/status`) · 버전
- 가드 상태 색상: `temp-ok` 초록 / `temp-warn`+`⚠ 스로틀링` 빨강(45°C 경계 오르내리며 실시간 전환 확인) / `temp-off` 회색 "가드 끔"
- `.wrap{min-width:480px}` 모바일 보호, `.info-left/.info-right/.info-item(throttle-badge)` 클래스 신설, `updateInfoBar()` + `refresh()` 가드 페치

### RSS 진단 (사용자 보고: "응답이 없다던가")
- **원인 2가지 발견**: ① 웹 "지금 확인" 버튼이 `/api/rss/0/check`로 id=0 → "피드 없음" 무응답 버그, ② LimeTorrents가 Cloudflare JS 챌린지로 403 + 기존에는 오류가 "항목 없음"으로 삼켜짐
- **수정**: `/api/rss/{id}/check`에서 id "0" = 전체 확인 허용 / `fetchFeed`가 비2xx(403 힌트)·HTML 응답(챌린지) 구분해 throw / 브라우저 UA + Accept 헤더 / 오류 시에도 `lastCheckedAt` 갱신
- **검증**: 피드 추가 → "지금 확인" 클릭 → `마지막: ... | 항목: 0` + `⚠ HTTP 403 (Cloudflare/보안 챌린지 차단 가능)` UI 표시, 디버그 로그 `RssFeedMgr` 에러 경로 확인
- **자동 다운로드 확장**: RSS 항목 URL이 `magnet:`/`.torrent`면 Torn... `RelayApp.getTorrent(ctx).addMagnet/addTorrentFile` 라우팅 (fetchTorrentFile 헬퍼 신설)

### 보관함↔설정 디자인 통일
- 보관함 오른쪽 콘텐츠를 설정 스타일로: 카드 1 ".sg 최대 관리" + ".sh", 카드 2 "폴더 내용/휴지통"(동적 제목) + 파일 리스트 그룹(구분선/호버배경, 단일 행 시 last-child 구분선 없음)
- `.storage-main`(flex column gap 12px) 래퍼 + `#fileList .file-row` 리스트 그룹 오버라이드. 트리(좌)는 이미 설정 내비 스타일
- 카드/행 마크업 검증: sg bg #101E3A radius 14px, 행 pad 16px 내부 정렬 확인

### TUNNEL_GUIDE.md
- Tailscale/CF 시나리오·API 사용법·현재 한계(바이너리 미번들/웹 UI 섹션 없음/자동시작 없음)·트러블슈팅·로드맵

### 터널 웹 UI 설정 섹션 (🔗 터널)
- 설정 사이드바 "☁️ Debrid" 뒤에 "🔗 터널" 메뉴 + `settings-tunnel` 섹션 추가 — `.sg` 카드: 터널 사용 토글 / 제공자 선택(Tailscale·Cloudflare 하이라이트 버튼) / 저장 / "🔍 현재 상태" / 상태 표시
- `loadSettings()`에 `/api/settings/tunnel`(res[4] → 이후 인덱스 당겨짐: schedule `sc`→`sch`로 이름 변경) 연동, `switchSettingsSection()` 배열에 'tunnel' 삽입(중복 정의 2곳 모두)
- 신규 JS: `tunnelSelectedProvider` 전역 + `highlightTunnelProvider()` / `saveTunnelSettings()` / `checkTunnelStatus()`
- **E2E (실기기, chrome-devtools로 기기 대시보드 접근)**: 사이드바 🔗 터널 표시 → 섹션 열면 "터널 사용" checked(API 값 반영) + Tailscale 버튼 하이라이트(#2F80ED/#122A4D) → "🔍 현재 상태" → "✗ Tailscale 미연결 또는 미설치" → 체크 해제+저장 → API `tunnelEnabled:false` + "터널 비활성화" → 원복. API로 CF 전환 시 "cloudflared 바이너리 필요" 구분 확인
- 산출물: `docs/screenshots/android/v0.10.1_tunnel_settings.png` + TUNNEL_GUIDE 한계점/로드맵 갱신

### 정상 RSS 피드 자동 다운로드 E2E (버그 1건 발견·수정)
- **검증 구성**: 맥 로컬 `http.server 8099` + `adb reverse`로 3종 항목(매그넷/우분투 .torrent/일반 URL) RSS 피드 서빙 → 기기 서버 피드 추가(autoDownload=true) → "지금 확인"
- **발견 버그**: `item.enclosureUrl ?: item.link`에서 enclosure 없는 항목의 `enclosureUrl=""`(빈 문자열)이 `?:`(null만 대체)를 무력화 → url 공백 → 자동 다운로드 루프 미진입(다운로드=0). `enclosureUrl?.takeIf{isNotBlank()} ?: link`로 수정
- **수정 후 검증**: magnet→`addMagnet`(토렌트 "추출 중..." FETCHING_METADATA) / .torrent→`fetchTorrentFile`(ubuntu ISO 토렌트 DOWNLOADING) / 일반 URL→`enqueue`(잡 생성) 3종 라우팅 전부 동작 확인. 테스트 후 2 토렌트(우분투 4.7GB 다운로드 시작분) + 1 잡 + 피드 삭제·adb reverse 해제로 정리

### 오버레이 토글 E2E (실기기 SM-S901N, USB)
- **발견 버그 2개**: ① `startForegroundService()`인데 `startForeground()` 미호출 → ForegroundServiceDidNotStartInTimeException으로 **앱 프로세스 자체가 크래시** (pid 소멸 확인), ② 토글 API가 시작만 하고 OFF 불가
- **수정 (DebugOverlayService.kt + RelayServer.kt)**: `startInForeground()` 신설(relay_status 채널·`NOTIF_ID 2002`·`FOREGROUND_SERVICE_TYPE_DATA_SYNC` 재사용), `@Volatile isRunning` 상태 기반 진짜 ON/OFF 토글, 응답은 요청 시점 `wasRunning` 기준 결정화(비동기 플래그 경합으로 응답이 뒤집히던 것 수정), JS 오타 `权限이`→`권한이`
- **E2E 흐름**: `appops set SYSTEM_ALERT_WINDOW allow`(adb) → `GET /api/debug/overlay` `{"hasPermission":true}` → 토글 ON `{"running":true}` → dumpsys: ServiceRecord 3건 + `ty=APPLICATION_OVERLAY (0,100)(fillxwrap) gr=TOP START` + 프로세스 생존 → 토글 OFF `{"running":false}` → 서비스 0건 + 오버레이 윈도우 제거 + 프로세스 생존
- **시각 검증**: ON/OFF 스크린샷 상단(100~300px) 평균색 — ON `(23,32,47)` 어두운 청색(오버레이 bg 0xE60A1428 혼합) / OFF `(35,37,43)` 앱 배경. 오버레이 로그 시그니처 `[17:37:55.184][I][DebugOverlay] 오버레이 서비스 시작` 확인
- 산출물: `docs/screenshots/android/v0.10.1_overlay_on.png` / `_off.png`

## 검증 산출물
- `curl /api/debug/status` → logCount=51, apiCallCount=27 (정상 기록)
- `curl /api/debug/logs` → 가드 센서 체크(thermal=47°C), 스케줄러 크론+제약+JobScheduler result=1, MCP 도구 실행 로그 확인
- `curl /api/debug/api-calls` → `? /api/info → 200` 정상 기록
- MCP tools/call → JSON-RPC 정상 응답 + 상세 로그 (요청진입 138ms)
- /storage/external → StorageDetector 상세 로그 + bestPath=null (외장 미연결 정상)

## 문서
- CHANGELOG.md: v0.10.1 디버그 시스템 + v0.10.2(정보 바/RSS/디자인 통일/TUNNEL_GUIDE/오버레이 토글 수정) 기록
- TODO.md: T-821~T-829 + T-830~T-837 등록 + 전부 완료
- TUNNEL_GUIDE.md 신설

## 주요 변경 파일
- **변경**: DebugLogger.kt, RelayServer.kt (디버그 API + 인터셉터 + RSS id=0 허용 + 오버레이 토글), WebAssets.kt (debugHtml·디버그 섹션·정보 바·보관함 카드 구조·오타 수정), McpServer.kt/TunnelManager.kt/StorageDetector.kt/GuardDaemon.kt/DebridClient.kt/SchedulerManager.kt/ScheduleJobService.kt/RelayService.kt (로그 증강), RssFeedManager.kt (에러 표면화 + magnet/.torrent 라우팅), AndroidManifest.xml (오버레이), DebugOverlayService.kt (startForeground + isRunning + stop)
- **신규**: TUNNEL_GUIDE.md, docs/screenshots/android/v0.10.1_*.png+a11y dump

## 남은 TODO
- 터널 웹 UI 설정 섹션 완료 (🔗 터널, E2E 검증) — 다음 후보: 터널 자동 시작(RelayService), 바이너리 번들
- 커밋 (터널 UI 변경분 — feat/android-v010-debug-rss 브랜치에서 진행)

## 큐 상태
- 없음

## E2E
- 디버그 API 전체 통과 확인
- 오버레이 토글 E2E 통과 (권한 adb 부여 → ON→OFF, 크래시 없음, 시각 확인)
- 정상 RSS 피드 E2E 통과 — BBC 34항목 파싱, magnet/.torrent/일반 URL 3종 자동 다운로드 라우팅 (enclosureUrl 빈 문자열 버그 수정 후)
- 터널 웹 UI E2E 통과 — 사이드바/섹션 렌더링, loadSettings 반영(체크+하이라이트), 상태 확인, 저장(ON/OFF) 반영

---

# 보충 세션 (20:20~) — v0.11 배터리/성능 최적화 + ThrottleInterceptor/FMT 버그 수정

## 세션 요약
- **무엇을/플랫폼**: [ANDROID] 배터리 드레인 1순위 개선 — WakeLock 24h 제거, jobs.json 무디바운스 은닉 버그(매 틱 전체 JSON 디스크 쓰기) → 10초 디바운스, 폴링 대폭 축소(토렌트 5s·가드 120s·오버레이 3s·알림 2s·틱 2s), Repository 동일값 스킵, RSS 매니저 누수 수정. E2E 중 ThrottleInterceptor 0Byte 다운로드 버그 + fmt() MB 제수 오류 발견·수정
- **빌드**: BUILD SUCCESSFUL(ktlint 포함, 2회) + install Success × 3. `./build_and_run.sh debug`
- **PERF/CACHE**: 다운로드 중 저장 400ms→10초(디스크 쓰기 ~90%↓), 진행 방출 5배↓, 토렌트 폴링 5배↓, 가드 폴링 4배↓. WakeLock `dumpsys power` 0건 확인
- **남은TODO**: T-850(커밋)만 남음 — 커밋 미수행
- **전달로그**: Ktor 서버 HEAD는 미지원(404), curl GET은 정상 — 확인 위해 HEAD 대신 GET 사용
- **문서갱신**: PLAN_v0.11_perf_android.md 작성, TODO T-840~T-852, CHANGELOG v0.11.0, 본 세션 로그
- **큐상태**: 없음
- **E2E**: 100MB 전체 수신, 2초 틱, 전이+주기 저장(13s 전송에 3회), 8KB/8192 즉시 확인, 퍼블리시 정상

## 검증 요지
- [후속] 가드 온도 임계치 30~60 → 50~70°C 확장 (기본 45→50, setGuardThermalLimit coerceIn 누락분 포함 전 지점 반영, POST 70 허용·71 거부 확인, 사용자 원래 설정 guardEnabled=false·battery50으로 복원)
- [후속] 목록 버튼 크기 통일 — `.card-acts` 112px 픽스+100%, 보관함 아이콘 34px, box-sizing:border-box (받기/삭제 112px·중심 정렬, 아이콘 34px CDP 좌표 검증). 안드 infosec: 웨일 CDP(localabstract:9222)로 확인 후 forward 정리
- 속도제한 인터셉터 원인 격리: limit=0 통과 / limit=5MB/s·999999999 실패 → 토큰 수학이 아닌 래핑 구조 결함 → 단일 래핑으로 수정 · 재검증 통과
- fmt() 0.1MB 미스터리 = MB 분기가 GiB 제수 사용(100MB→0.1MB, 평균속도 왜곡) — MiB 제수로 교정
- 테스트 산출물 전부 정리: 테스트 잡 12건 삭제, storage dr_test*/small*/perf_test 삭제, 공용 DroidRelay/* 정리, 호스트 python 8081 종료
---

# 보충 세션 (21:10~) — HTTPS 다운로드 (웨일 "안전하지 않은 다운로드" 경고 해결)

## 세션 요약
- **무엇을/플랫폼**: [ANDROID] 웨일에서 HTTP로 ISO를 받으면 "안전하지 않은 다운로드" 경고 — 근본 원인은 HTTP(Insecure Origin/Download)로 판명. mkcert 자체 서명 인증서(맥 login 키체인 신뢰) + Ktor 엔진 **CIO→Netty 전환**(CIO는 HTTPS 미지원 예외 발생) + HTTP 8080·HTTPS 8443 이중 커넥터. 웹 받기 링크를 `https://<host>:8443` 절대 경로로, 응답에 nosniff·no-store 헤더 추가
- **빌드**: BUILD SUCCESSFUL × 2 (CIO→Netty 후 Netty 4.2 META-INF 충돌 → packaging exclude, ktlint multiline wrap 수정) + install Success. `./gradlew ktlintCheck test` 통과
- **PERF/CACHE**: Netty 전환으로 엔진 교체 — 기능 동일, HTTPS 커넥터 추가. 다운로드 7.1MB @ 14MB/s (LAN)
- **남은TODO**: 없음 (T-855 완료). 커밋 미수행 — 사용자 확인 후 커밋
- **전달로그**: Ktor HEAD 미지원(404)은 그대로 — curl 검증은 GET 사용. HTTPS 인증서 만료 2028-11-27. 폰 IP 변경 시 assets/certs/README.md 절차로 재생성 필요. 맥 CA 등록은 login 키체인(무 sudo) — 네트워크 재부팅 시 유지됨
- **문서갱신**: TODO T-855, CHANGELOG v0.11.1, assets/certs/README.md, 본 세션 로그
- **큐상태**: 없음
- **E2E**: 맥에서 https://10.64.228.42:8443/ 200, ISO HTTPS 전체 다운로드 MD5 7.1MB 일치(b61fe3fe…), `security verify-cert` success, SAN 확인(localhost/10.64.228.42/127.0.0.1/::1)

## 검증 요지
- CIO는 `UnsupportedOperationException: CIO Engine does not currently support HTTPS` — Netty로 강제 이관
- Netty 4.2.16에서 16개 jar `META-INF/INDEX.LIST` 충돌 → `packaging.resources.excludes`(INDEX.LIST·io.netty.versions.properties·native-image·versions/9)
- 맥에서 `curl -sk`로만 접근(터미널 curl은 keychain 미사용) — 브라우저는 keychain 사용하므로 Whale에서 경고 없음
- **후속**: 맥 login 키체인 mkcert CA 제거(사용자 요청 — 경고 1회 감수). `security delete-certificate -c "mkcert lee@lees-MacBook-Pro.local"` 후 `verify-cert` = NOT_TRUSTED 확인. docs(README/CHANGELOG/TODO)를 "CA 등록은 선택"으로 정정. 앱 코드 변화 없음
- **후속2**: HTTP→HTTPS 리다이렉트(307) 추가 — LAN 요청은 https://…:8443으로 이동, loopback(127.0.0.1/localhost/자기 IP) 예외로 터널·자체 점검 보호. `call.request.local.scheme`+`isLocalHost(remoteHost)` 기준. 맥: http root/ISO → 307 Location 확인 + -skL follow 200/7112896B. 기기: loopback 200. T-856/TODO/CHANGELOG 갱신

---

# 보충 세션 (23:00~) — v0.12 비디오 다운로드 (범용 스트림 · 유튜브 제외 결정)

## 세션 요약
- **무엇을/플랫폼**: [ANDROID+WEB] 비디오 다운로드 v0.12 — StreamDetector(웹페이지 m3u8/mpd 스니핑 + 직접 입력) + VideoDownloadManager(FFmpegKit 내장, 진행률/취소/MediaStore 게시) + API 2종 + 웹 UI 구현. **유튜브는 최종 제외**: NewPipeExtractor 0.26.5(최신 확인)로 시작했으나 2026 유튜브 PoToken 강제 + LTE NAT IP 평판 차단에 visitor_id 주입·ANDROID_VR 스푸핑 우회까지 시도 후 실패(재생 시그니처 401→visitor→ANDROID_VR→player 200 도달 → 마지막이 `LOGIN_REQUIRED "Sign in to confirm you're not a bot"`, WAN IP 2종 모두) → 제거
- **빌드**: BUILD SUCCESSFUL × n(ktlint 포함, ffmpeg 하위 종속 88개 해소). `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`, gradlew는 apps/android. ffmpeg-kit-full **TLS 미포함 확인**(`https or dtls protocol not found`) → `ffmpeg-kit-https`로 교체 재빌드
- **PERF/CACHE**: m3u8 다운로드 RUNNING에서 진행·속도 리포트 정상(갱신 1초 통합 페이로드, 162.37MB). 유튜브 제거로 newpipe(+94개 전이적)·JitPack 동시 제거 — APK/빌드 가벼워짐
- **남은TODO**: T-864/세션 로그/커밋 — 커밋 미수행(사용자 확인 필요). 307 리다이렉트는 분리 커밋 완료 `af29009`
- **전달로그**: 유튜브 재시도 시 TubeEngine.kt가 git 기록에 있음(visitor_id 주입은 `{"responseContext":{"visitorData":…}}` 형태 필수, 평면 객체는 ParsingException). 델리게이트 폰/서버 우회는 v0.13+ 후보(WAN IP 우회). Apple devstreaming m3u8은 S3 AccessDenied(테스트 소스 부적합) — Mux test-streams 사용
- **문서갱신**: PLAN_v0.12(유튜브 제외 반영), TODO T-857~T-866, CHANGELOG v0.12.0, 본 세션 로그, error_message_ko.json(0102·0203 삭제, 0101 문구 스트림 전용)
- **큐상태**: 없음
- **E2E**: m3u8 Mux HLS — analyze(kind:stream)→create(잡 mtbvvjq622)→RUNNING 162.37MB→DONE→`Download/DroidRelay/mux_hls_test.mp4`→ffprobe(mov,mp4, 634.6s, 170,260,672B) 무결성. 유튜브 제거 후 회귀 스모크: 재설치→analyze→create(RUNNING 22MB)→DELETE 취소→REMOVED_OK. WebAssets 잔여 참조 없음

## 검증 요지
- 유튜브 우회 경로 확정: ① `onFetchPage` 순서 문제 — android fetch(성공) 후 `getJsonPostResponse(NEXT)`가 WEB 클라이언트로 visitorData 요구 → ② visitor_id POST 가로채 응답의 `responseContext.visitorData` 주입(홈 HTML에서 길이 520 추출), ③ 그래도 reel_item_watch 401 → ④ ANDROID→ANDROID_VR(clientVersion 1.56.0, VR UA) 스푸핑 → **player 200 도달**, 마지막 장벽 = IP 평판(구조 아님). Kotlin 함정: `replaceFirst(regex){lambda}` 미존재 → `.replace(versionRegex){…}`
- m3u8은 전혀 다른 파이프라인 — 세그먼트 파싱 없이 FFmpeg가 원본 copy(파일 퍼블리시 후 MediaStore 게시는 기존 DownloadEngine 경로)
- 307 커밋 선별 스테이징(hunk 1 respond import + hunk 7 redirect 블록) 후 `af29009` 단일 파일 커밋, 이후 유튜브 제거까지 미커밋 상태로 진행
