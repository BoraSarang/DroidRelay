# Changelog

## [0.21.1] - 2026-09-07 (미배포)

### Changed [web]
- 브라우저 탭 타이틀에 총속도 표시 (`DroidRelay : 3.2MB/s`, 업로드 있으면 ▲ 추가, 대기 중 원복)

## [0.21.0] - 2026-09-07

> v0.18~0.21 묶음. versionCode 26, versionName 0.21.0.

### Fixed [android] — 설정 감사 + 업로드 최소화 (PLAN_v0.21_settings-audit_android)
- **T-955 감사**: 53필드 전수 — 적용 49, 데드 4(`torrentSavePath`·`torrentSeedRatio`·`torrentDhtEnabled`·`torrentPexEnabled`). 잘린 설정 잔재(유튜브/SHA) 0건, 고아 키 0건
- **T-956 시드 비율 강제**: SEEDING 중 `totalUpload/totalDownload` 도달 시 자동 일시정지 (`0`=제한 없음)
- **T-957 DHT 토글**: `start/stopDht` 배선 (기동 분기 + 실행 중 전환)
- **T-958 저장 경로**: 설정값 사용 (기본 기존 경로)
- **T-959 PEX 안내 + 프리셋**: "API 없어 항상 켜짐·트래픽 미미" 문구 + "업로드 최소화" 원터치(비율 0.5·업로드 32KB/s·DHT 끔, 앱/웹)
- **검증**: SeedRatioTest 5건 GREEN + assembleDebug 설치. 실기기 E2E: DHT 정지/시작 로그·설정 roundtrip·토렌트 26% 진행 PASS. 비율 도달 실전은 실사용 관찰

## [0.20.0] - 2026-09-07 (미배포, 디버그 검증 중)

### Added [android] — v0.20 미리보기·검색·공유 (PLAN_v0.20_thumb-search-share_android)
- **T-949 영상 썸네일**: `ThumbManager`(FFmpeg 10초→1초 프레임, 320px, 300개 캐시) + `GET /thumb/` + 보관함 목록 미리보기(img lazy, 실패 시 제거)
- **T-950 토렌트 검색**: `TorznabClient`(Torznab XML 파싱·시드순 50건) + `GET /api/search` + `torrentUrl` 바로 받기 + 웹 검색 UI + 앱 검색 UI + Jackett/Prowlarr 설정(앱/웹)
- **T-951 만료 공유 링크**: `ShareRepository`(영속) + `/api/share` 발급/목록/삭제 + `GET /s/{token}` (BasicAuth 예외, 토큰이 권한). 발급 팝업은 유지시간 셀렉트(1시간~30일)+설명 문구
- **T-952 게스트 읽기전용**: guest 계정 + GET 열람·다운로드만 허용(설정·발행·제어는 403) — 웹 인증 켜짐 시 유효
- **T-953 위젯/퀵타일**: 홈 위젯 + QS 타일 서버 토글(`ServerToggle` 공용)
- **검증**: compile + test GREEN + assembleDebug 설치. 실기기 E2E: files API 200(선택 유지)·공유 발급→다운로드 일치→삭제→404·썸네일 실패경로 404·검색 미설정 502·설정 조회 PASS, 잔재 정리. 진짜 영상 썸네일·게스트 매트릭스·위젯 탭은 실사용 확인 대기

## [0.19.0] - 2026-09-07 (미배포, 디버그 검증 중)

### Added [android] — v0.19 외부 재생 + 자동 운영 (PLAN_v0.19_dav-quota_android)
- **T-944 WebDAV 읽기**: `DavRoutes` (`OPTIONS`/`PROPFIND` Depth 0·1 + 207 XML + `GET /dav/` Range 재사용) — VLC/nPlayer 외부 재생. PROPFIND 루트 href `..` 탈출 버그 수정(canonical 기준)
- **T-945 보관함 쿼터**: `storageQuotaGb`(0=끔) + 초과 시 오래된 파일 자동 휴지통(`StorageJanitor.enforceQuota`) — 완료 후처리 후킹(HTTP/비디오/토렌트)
- **T-946 자동 분류**: `autoClassify` + 확장자→영상/음악/문서 폴더 자동 이동
- **T-947 중복 감지**: `normalizedUrl` + `findDuplicateUrl` — `/api/jobs` 409(`E-AND-DOWN-1006`), 앱/Share/RSS/MCP 전 경로 안내·차단
- **검증**: compile + testDebugUnitTest GREEN(`StorageJanitorTest` 4건) + assembleDebug 설치. 실기기 E2E: DAV 206/video-mp4·PROPFIND `/dav/`·설정 roundtrip·409(fragment 정규화 동일 id) PASS, 잔재 정리. 파일 선택 live POST·분류 실전송은 다음 토렌트 시 확인

## [0.18.0] - 2026-09-07 (미배포, 디버그 검증 중)

### Added [android] — v0.18 담기↔소비 완성 (PLAN_v0.18_media-share_android)
- **T-940 브라우저 직접 재생**: `GET /stream/{name...}` Range(206) inline 스트리밍(`serveFile` inline+Content-Type 확장, `StreamContentType` 매핑) + 보관함 재생 가능 파일 ▶ 버튼 + video 오버레이 플레이어
- **T-941 Share Intent 받기**: `ACTION_SEND text/plain` 인텐트 필터(singleTop) → URL/magnet 추출 → 다운로드/토렌트 등록 + 스낵바 확인
- **T-942 토렌트 파일 선택**: `TorrentEngine.setFileSelection` + `prioritizeFiles(IGNORE/DEFAULT)` + 재매핑 시 영속 선택 복원 + `POST /api/torrents/{id}/files` + 웹 상세 모달 체크박스(즉시 적용) + 앱 TorrentItem 파일 목록/선택
- **검증**: compileDebugKotlin + testDebugUnitTest GREEN(`StreamContentTypeTest` 3건) + assembleDebug 설치. 실기기 E2E: `/stream/` Range 206(`Content-Range`+`video/mp4`+inline, 1024B 정확 일치·전체 GET 일치) PASS, Share SEND 필터 `dumpsys` 등록 확인, 파일 선택 API는 live torrent 없어 미실시(재시작 복원 로직은 코드 리뷰). 테스트 파일 업로드→삭제→휴지통 비우기로 정리

## [0.17.0] - 2026-09-07

### Added/Fixed [android] — T-937 웹 토렌트 삭제 UX + T-938 폴더 다운로드
- **T-937 웹 삭제 컨펌**: 토렌트 삭제 버튼 즉시 삭제 → `confirmPopup('토렌트 삭제')` 경유. 본문 `'이름' torrent를 목록에서 삭제할까요?` + 미완료 시 `다운로드 중이던 파일도 함께 삭제됩니다.` (앱 문구와 정합)
- **T-937 버튼 앱 통일**: 추출중 일시정지 추가·실패 재개 추가·시딩 일시정지 제거 (앱 TorrentScreen 규칙과 동일)
- **T-937 잔존 정리**: 미완료 삭제 시 `saveDir/<infohash>/` 후보 디렉토리도 제거 (추출중 단계 job.name 불일치로 남던 쓰레기 해소)
- **T-938 폴더 다운로드**: `GET /dl-folder/{name...}` ZIP 실시간 스트리밍(임시파일 없음·canonical 탈출 차단·한글 UTF-8) + 웹 폴더행 📦 버튼 (`폴더명.zip`)
- **T-939 폴더 다운로드 속도 개선**: 영상 등 기압축 파일 재압축이 CPU 병목 → `ZipOutputStream.setLevel(0)` 패스스루 + 256KB 버퍼(`BufferedOutputStream`) + 완료 로그(파일 수·원본 MB·소요 s·MB/s). 버튼/파일명/내부 구조 변경 없음
- **검증**: compileDebugKotlin + ktlint GREEN, assembleDebug 완료. 실기기 설치·동작 검증 대기 (기기 미연결)
- **T-939 실기기 실측(2026-09-07)**: 850.8MB 폴더 → 서버 15.7초(54.1MB/s), 맥 curl 12.5초(71MB/s·570Mbps·HTTP 200). Wi-Fi 실효 상한 근처로 정상 확정

### Refactor [android] — 리팩토링 4묶음 (PLAN_v0.17_refactor_android)
- **Phase1 긴급 버그(T-933)**: 웹 MCP/스케줄 저장 `toast()` 미정의 → `showDlToast` 교체; 숫자 `||기본값` falsy로 0(끔/무제한) 소실 → `!=null` 판별 + `num()` NaN 가드; 전역 속도 슬라이더 저장 바인딩 누락 → input/change 바인딩; TLS 키스토어 PW 하드코딩 → `tls.properties`+`BuildConfig.TLS_KEYSTORE_PASSWORD` (git 미추적); DeviceGate `f.get()` 무타임아웃 → 60초 타임아웃 후 거부
- **Phase2 T-931 후속(T-934)**: 3종 alert handle transient 수명 주석 명시; FINISHED 파일 이동+영속을 게이트 밖으로; `register/unregisterMapping` 단일 헬퍼 (cancel 원자화·polling 무효제거 job.infoHash 폴백·수동매핑 3곳 통합·removedHash 제거); apply* 3종 session null 체크 게이트 안; seedWaitSince 누수 정리
- **Phase3 설정 단일화(T-935)**: `SettingsConstraints` 단일 진실 (업로드 0~1024/step32/기본 512·다운로드 0~20480/step1024/기본 0·동시수 1~4/기본 2·최대활성 1~10/기본 3·upload/downloadLabel·randomEphemeralPort); 앱 속도 UI 프리셋→연속 슬라이더 통일; Repo 기본값 512/2 정합; 서버/앱 reset 리터럴 상수화; 웹 `kblabel()` 0=끔/무제한 표시
- **Phase4 구조 분리(T-936)**: RelayServer 1897→519줄 (TorrentRoutes/JobRoutes/SettingsRoutes/StorageRoutes+StorageGuard/DebugRoutes, serveFile·toJson internal 승격); WebAssets switchTab 중복 삭제·`apiGet/apiPost` 도입(save* 8함수 교체); error_message_ko.json 미등록 8종 추가 + Stor 태그 교정
- **검증**: compileDebugKotlin + ktlint(스크립트 제외) GREEN, assembleDebug 설치. 실기기 API 스모크는 앱 실행 후 진행 예정
- **버전**: versionCode 24 → **25**, versionName **0.17.0**

## [0.16.6] - 2026-09-06

### Fixed [android] — magnet 추가 시 SIGSEGV 근본 원인(alert.handle() dangling) + libtorrent JNI 직렬화 게이트
- **재현(크래시 2건 분석)**: magnet 추가 → ADD_TORRENT 매핑 수백 ms 후 netty 웹 스레드(`eventLoopGroupP`)에서 SIGSEGV. v0.16.5는 `torrent_status::state()`(status 경유), v0.16.6 게이트 적용 후엔 `torrent_handle_is_valid`(`pieceInfo → torrentFile()`)로 crash 지점 변경 — **스레드 경합이 아니라 native handle 수명 문제**임을 확인
- **근본 원인(libtorrent4j SWIG 소유권)**: `AddTorrentAlert.handle()`은 `new torrent_handle(cPtr, false)`(swigCMemOwn=false) — **alert C++ 객체 내부 멤버 메모리를 가리키는 참조**를 반환. 이를 `handleMap`에 long-lived로 보관하면 alert가 `pop_alerts` 후 소멸되며 **dangling** → 이후 장기 보관된 handle로 JNI 호출 시 `__shared_weak_count::lock()` UAF → SIGSEGV. 반면 `session.find(hash)`는 `new torrent_handle(cPtr, true)`로 **독립 heap 카피**를 만들며 세션·알림 수명과 무관하게 안전(공식 문서 "alert handle may be invalid" + 알려진 이슈 확인). T-930(T-996) 게이트 serialization만으로는 UAF를 막을 수 없음
- **수정(T-931)**: `ADD_TORRENT`에서 alert handle을 저장하지 않고 `session.find(Sha1Hash.parseHex(hash))` 기반 handle로 `handleMap`에 보관(`session.find` null이면 폴링 자동 매핑 대기). alert 내 handle은 id/hash 추출(`infoHash()`)에만 사용 → alert 생존 기간 내 일시 사용으로 제한
- **함께 적용**: T-930 libtorrent JNI 전체 ReentrantLock 직렬화(sessionGate + withGate/withGateAlert(tryLock 300ms)), RelayService `onDestroy/onTaskRemoved`의 `stopForeground(STOP_FOREGROUND_REMOVE)`(FGS DidNotStopInTime 방어), 업로드 속도 프리셋 32KB/s 추가(설정 화면)
- **검증**: assembleDebug GREEN → `R5CT215F4QK` debug 재설치(데이터 기존 유지, 서명 동일 업그레이드). **이전 크래시를 일으킨 동일 magnet으로 재현 테스트**: 취소→재추가→즉시 폴링 20회 연속 HTTP 200 + 프로세스 생존, `ADD_TORRENT 매핑(id=find 기반)` 로그 확인, 메타데이터 수신 → DOWNLOADING → pieceInfo(5/1267) 정상
- **버전**: versionCode 24, versionName **0.16.6** (현재 디버그 설치본)

## [0.16.5] - 2026-09-05

### Fixed [android] — v0.16.4 방어 코드의 한계 극복: FGS 5초 의무 타임아웃 크래시 + 배터리 UI 개선
- **발견(v0.16.4 재현 테스트 후 실사용 70분 만에 크래시)**: `MainActivity.onCreate`의 `startForegroundService()` 호출에 대해 서비스 생성 시점 `startForeground()`가 **백그라운드 시작 제한으로 DENIED**되면, v0.16.4처럼 try-catch로 `ForegroundServiceStartNotAllowedException`을 삼켜도 **시스템의 5초 `startForeground()` 의무 타이머는 취소되지 않음** → 이후 `ForegroundServiceDidNotStartInTimeException`(스택 `MainActivity.kt:69 → RelayService.start`)으로 앱 전체가 강제 종료. `dumpsys activity services`에서 `infoAllowStartForeground=[code:DENIED]`, `isForeground=false`인 좀비 ServiceRecord로 확정
- **근본 수정**: `startForegroundService()`의 5초 의무 타이머 자체를 회피 — `RelayService.start()`는 **일반 `startService()`를 기본**으로 사용하고, `onStartCommand` 첫 줄에서 `startInForeground()`로 **기회적 FGS 승격**(허용 시 알림 복구, 거부 시 백그라운드로 무해하게 동작). `service.onCreate`에서도 채널 생성보다 FGS 승격을 먼저 실행(5초 창 최대 확보). 백그라운드 start가 제한되는 경우만(IllegalStateException) `startForegroundService`로 재시도
- **배터리 UI 개선**: 허용 상태 시 "배터리 무제한 **해제**" 버튼 추가(`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), 설정 화면에 `LifecycleEventObserver`(ON_RESUME)로 `isIgnoringBatteryOptimizations` **재조회 → 시스템 화면에서 돌아오면 상태/버튼 즉시 갱신**
- **검증**: 빌드 3종 GREEN → `R5CT215F4QK` 설치(versionCode 23). 배터리 whitelist 허용 확인(`dumpsys deviceidle whitelist`), 배터리 예외 허용 UI로 최적화 예외 상태 표시
- **버전**: versionCode 22 → **23**, versionName **0.16.5**

## [0.16.4] - 2026-09-05

### Fixed [android] — FGS 크래시 루프(백그라운드 사망) + 배터리 최적화 예외 유도
- **원인(로그 확정)**: 프로세스가 시스템(삼성/Android 16 배터리 최적화)에 강제 종료되면 `START_STICKY`로 `RelayService`가 재생성 → `onCreate`의 `startForeground()`가 **백그라운드 FGS 시작 제한(`ForegroundServiceStartNotAllowedException`)에 걸려 예외 미처리로 프로세스 즉시 사망 → 무한 재시작 루프**. 실기기 logcat: 09:19/13:32(서비스 재시작)·13:42(활동 시작) 크래시 확인
- **수정**: `startInForeground()`를 try-catch로 방어 → FGS 시작 거부 시에도 **백그라운드로 계속 구동**(크래시 루프 중단, `[W] FGS 시작 거부 — 백그라운드 모드로 계속 동작: ... (E-AND-SRV-0101)`). `onStartCommand`에서 매 실행 시 `startInForeground()` 재시도(사용자 재진입/재시작 시 알림 복구, `isForeground` 플래그). `RelayService.start()`의 `startForegroundService` 호출도 try-catch + `startService` fallback(`E-AND-SRV-0102`)
- **배터리 최적화 예외 유도**: 설정 화면 '서버' 섹션에 상태 표시(허용됨/미허용) + 미허용 시 "**배터리 무제한 허용 요청**" 버튼(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 권한 추가 — 삼성/Android 16에서 밤새 안정 동작의 전제
- **검증**: 빌드 3종 GREEN → `R5CT215F4QK` 설치(versionCode 22) → **`adb shell am crash` 재현 테스트**: 강제 크래시 후 1초 만에 시스템이 `for service`로 재생성, 크래시 없이 전 프로세스에서 `onCreate` 완료 → 생존 확인(이전엔 재시작 시 재사망)
- **버전**: versionCode 21 → **22**, versionName **0.16.4**

## [0.16.3] - 2026-08-31

### Added [android] — 다운로드 QR 확대/축소 토글
- **서버 주소 QR 이미지를 탭하면** 전체 화면 반투명(검정 alpha 0.6) 오버레이에 화면 폭 80% 크기로 **확대 표시**, 오버레이 아무 곳(QR 포함)을 다시 클릭하거나 우상단 **닫기 버튼**을 누르면 사라짐
- 확대 전환 시 0.85f→1f **scale 모션**(tween 180ms) 적용, 닫기 아이콘은 `Close`
- **QR 비트맵 1회 캐시 재사용**: `qrBitmap()` 인코딩(512×512, CPU 작업)을 재컴포지션마다 하던 것을 `remember`로 1회만 수행, 축소 이미지(96dp)·공유·확대 오버레이가 같은 비트맵 공유
- **버전**: versionCode 20 → **21**, versionName **0.16.3**, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 설치(성능 영향 없음, 재인코딩 폐기로 오히려 개선)

## [0.16.2] - 2026-08-31

### Added [android] — 웹 설정 미러 2차: 스케줄 · Debrid · 터널 · MCP · 기본값 복원 (PLAN_v0.16_settings-mirror_android.md)
- 웹 대시보드 설정 탭의 남은 항목을 앱 설정으로 이전(백엔드 필드/setter/라우트 기존 완비, UI만 추가)
- **스케줄**(신규 섹션): 활성화 스위치, Cron 입력+적용(`CronParser.isValid`로 실시간 유효성 표시), Wi-Fi 연결 시에만/충전 중에만 스위치, 최소 배터리 슬라이더(5~100%)
- **Debrid**(신규 섹션): 활성화 스위치, 제공자 FilterChip 3종(Real-Debrid/AllDebrid/Premiumize), API 키 저장
- **터널**(신규 섹션): 활성화 스위치, 제공자 FilterChip 2종(Tailscale/Cloudflare Tunnel)
- **MCP 서버 권한**(신규 섹션): 프라이버시 모드 스위치, 도구 5종 활성화 스위치(file_list/file_read/download_add/download_list/download_control → `setMcpToolDisabled`)
- **기본값 복원**(신규 섹션): 다운로드/토렌트/전체 초기화 버튼 → **경고 AlertDialog 확인 후** 서버 `/api/settings/reset`과 동일한 조합 실행(다운로드: 동시2·속도해제·알림On; 토렌트: 업로드512·다운무제한·활성3·ratio2.0·DHT/PEX On·랜덤 포트 49152~65535·저장경로 기본) + RelayApp 엔진 즉시적용
- **버전**: versionCode 19 → **20**, versionName **0.16.2**, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 설치(성능/캐시 영향 없음, 설정 UI 추가)

## [0.16.1] - 2026-08-31

### Changed [android] — MD3 디자인 전면 정돈 (PLAN_v0.16_settings-mirror_android.md)
- **TopAppBar 도입**: `RootApp`에 탭 타이틀 + 우상단 **디버그 패널 아이콘(BugReport)** 추가, 하단 탭 라벨 한글화("Torrent"→"토렌트")
- **디버그 패널 이전**: DownloadsScreen의 "📡 DroidRelay + 제목 5연속 탭" 숨김 진입 제거 → `RootApp`의 `ModalBottomSheet`로 이동, `ui/DebugPanel.kt` 공용화(다중 선택 복사/전체 복사/비우기 동일)
- **다운로드**: 중첩 `LazyColumn`(Column+하단 리스트) 제거 → **단일 LazyColumn**(서버카드→URL→비디오→작업 목록→빈 상태 아이콘 안내), 비디오 잡에 `VideoLibrary` 아이콘, ⚡/⏱ 이모지 제거
- **Torrent**: 중첩 `Scaffold` 제거 → `Box`+하단 우측 FAB(스크롤 끝에 80dp 마진), 스낵바를 Root 콜백(`onShowSnack`)으로 이관, ⏱ 제거
- **보관함**: 헤더 이모지 제거, 목록 카드 색 `surfaceContainerHigh`→`surfaceContainer` 통일(강조 카드만 high 유지), 스낵바 콜백 이관, 빈 상태 아이콘 정돈
- **Theme.kt**: `surfaceContainer*` 표면 토큰 Light/Dark 명시 지정, 셰이프 MD3 기본값(4/8/12/16/28) 정렬 — Material You(dynamicColor)는 기기 테마 따라감 유지
- **버전**: versionCode 18 → **19**, versionName **0.16.1**, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 설치(성능/캐시 영향 없음, UI 개편)

## [0.16.0] - 2026-08-31

### Added [android] — 웹 설정 미러 1차: 전역 속도 제한 · 토렌트 고급 · 가드 (PLAN_v0.16_settings-mirror_android.md)
- 웹 대시보드 설정 탭에만 있고 앱에 없던 항목을 앱 설정으로 이전(백엔드 필드/setter는 기존 완비, UI만 추가)
- **전역 속도 제한**('다운로드' 섹션): 다운로드/업로드 Mbps 각각 스위치+슬라이더(1~10) → `setMaxDownloadBps/setMaxUploadBps`(1_048_576 단위, 0=무제한)
- **토렌트 고급**('Torrent' 섹션): 시퀀셜 다운로드 스위치, 시더 부재 대기(0~3600초), 리슨 포트(1024~65535, 랜덤 버튼), 저장 경로(존재/생성 확인) → 각 setter
- **가드 보호**(신규 섹션): 활성화 스위치, 임계값 슬라이더(열 50~70°C / 배터리 5~50% / 스토리지 50~99%), watchdog 주기(15~3600초), HTTP→HTTPS 강제 스위치
- **버전**: versionCode 17 → **18**, versionName **0.16.0**, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 설치(성능/캐시 영향 없음)

## [0.15.1] - 2026-08-31

### Fixed [web] — 비디오 분석 주소 초기화 · 업로드 대상 라벨 제거 · 분석 카드 UX 압축
- **비디오 분석 주소 초기화**: 분석 후 다운로드(추가)/재시도 성공 시 `#vurl` 주소 입력란이 남아있던 문제 → `clearVideoUrlInput()`로 초기화(+ `__videoState`/해상도 선택 리셋)
- **업로드 대상 라벨 제거**: 파일 올리기 버튼 옆 항상 표시되던 "대상: 📁 폴더명"(`#uploadTargetLabel`) 요소·갱신 코드 삭제 — 파일 올리기는 항상 현재 폴더로 동작해 정보가 상태 표시와 중복되고, 폴더명이 길면 지저분했음
- **분석 카드 UX 압축**: 스트림 주소를 화면에 길게 표시하던 meta 제거 → **📋 주소 복사** 버튼 하나로 대체(`copyVideoUrl`, execCommand + clipboard 폴백), 해상도 선택 radio → `<select>`(큰 목록도 한 줄), 파일명·복사·다운로드를 **한 줄 flex** 배치로 간결화
- **0206 차단 안내 1회·단문화**: `E-AND-VID-0206`(브라우저 외 접근 차단)이 **빨간 실패 메시지 + 💡 안내 2개로 중복 출력**되던 문제 → `showVideoBlocked()`로 💡 안내 **1개만** 표시. 문구를 `"💡 해당 사이트는 폰/앱(비브라우저) 접근을 차단하고 있습니다."`로 단축하고, analyze/create/retry 전 경로에 동일 적용. `StreamDetector.fetch`의 예외 메시지·`error_message_ko.json`도 같은 짧은 문구로 통일
- **버전**: versionCode 16 → **17**, versionName **0.15.1**, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 업데이트

## [0.15.0] - 2026-08-31

### Fixed [android+web] — 비디오 분석 403 조기 노출 + fetch 최적화 (PLAN_v0.15_analyze-403_android.md)
- **원인**: `StreamDetector.fetch`는 403을 `VideoException`으로 던지지만 **직접 m3u8/mpd 분석 경로의 `parseManifestVariants`/`resolveSegmentsCount`/`mediaDurationMsFromUrl`이 예외를 `catch`로 삼켜** "분석 성공"처럼 표시. 이후 `VideoApi.create`가 같은 403 URL을 4~5회 재fetch하며 조용히 실패 → FFmpeg로 보내 "다운로드 실패(E-AND-VID-0202)"만 노출되어 원인 불명의 혼동을 유발 (wowstream2.cloud m3u8 진단에서 확인)
- **해결**: `parseManifest()` 신설 — 매니페스트 **fetch 1회**(마스터면 첫 variant 1회 추가)로 variant/세그먼트 개수/총 재생시간을 계측하고 실패를 그대로 전파. `analyze` 직접/페이지 경로에 적용, `Found.durationMs` 추가, fetch 403은 새 코드 **`E-AND-VID-0206`**(브라우저 외 접근 차단 안내)으로 변경
- **create 재사용**: 다운로드 대상이 분석 대상과 동일하면 재fetch 없이 계측값 재사용 — 403 사이트는 분석 단계에서 즉시 실패(조용한 3회 실패 제거), 정상 사이트도 요청 수 감소
- **웹**: analyze 오류 시 0206이면 차단 원인/대안(브라우저에서 m3u8 직접 복사 or PageKit) 안내 박스 표시
- **테스트**: `ManifestFetchTest` 신규 3건 — JDK 내장 HttpServer 스텁으로 403 전파(0206)·미디어 플레이리스트 계측·마스터 첫 variant 팔로우 검증
- **버전**: versionCode 15 → **16**, versionName **0.15.0**, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 업데이트

## [0.14.1] - 2026-08-31

### Fixed [android+web] — 보관함(웹) 다운로드 비영문 파일명 깨짐
- **원인**: `/dl-file`(RelayServer.kt)과 `serveFile`이 `Content-Disposition: attachment; filename="${file.name}"`으로 **비ASCII(한글/일본어/중국어) 파일명을 raw로 헤더에 삽입** — HTTP 헤더는 ASCII 계열이라 브라우저가 인코딩 정보 없이 바이트를 해석, 웨일(Chromium)에서 저장 파일명이 다르게 나옴
- **해결**: RFC 6266 준수 `DispositionHeader.make()` 추가 — `attachment; filename="<ASCII percent-encoded>"; filename*=UTF-8''<percent-encoded>` 형태로 `/dl-file`·`serveFile`에 적용. 최신 브라우저는 `filename*`에서 원본 복원, 구형 fallback은 ASCII 안전
- **웹 보강**: `<a download="파일명">`에 실제 파일명 힌트 추가 + `dlBase` 하드코딩(`https://host:8443`) → **현재 프로토콜 따라 자동 선택**(HTTP 폴백 모드 `forceHttpsRedirect=false`에서도 다운로드가 자체서명 인증서로 막히는 별개 문제 동시 해결)
- **테스트**: `ContentDispositionTest` 신규 4건(한글/일본어/중국어/이모지/특수문자) — 결과 ASCII-only + `filename*` percent-decode 시 원본 복원 확인
- **버전**: versionCode 14 → **15**, versionName 0.14.1, 릴리즈 서명 APK `R5CT215F4QK` 실기기 인플레이스 업데이트

## [0.14.0] - 2026-08-31

### Added [android] — 안정성 7개 이슈 (PLAN_v0.14_stability_android.md)
- **부팅 자동시작 + watchdog**: `BootReceiver`(BOOT_COMPLETED → `autoStart` 확인 후 RelayService.start) + 서버 헬스체크(`isHealthy()` 127.0.0.1/api/info) 1분 주기 실패 시 `restart()` — 24시간 연속 동작 문제 해소. 주기 `watchdogIntervalSec`(기본 60, 15~3600)으로 설정 가능
- **토렌트 교차 매핑 레이스**: 받는 중 동일 마그넷 추가 시 `FETCHING_METADATA` 영구잔류 → `addMagnet` 중복 가드 + `findJobIdForNewTorrent`가 `infoHash==hash && FETCHING_METADATA` 정확 일치 우선(빈 hash FIFO 폴백) + `/api/torrents/add` 중복 시 409 + 웹 alert
- **속도제한 오버플로우**: `applySpeedLimit`/`applyRateLimits`/`applySettings`의 `*1024 .toInt()` Long→Int 오버플로우 → `coerceIn(0, Int.MAX_VALUE.toLong())` (설정 시 무기한 다운로드 제한 미반영 증상 해소)
- **시더 부재 자동 중단**: 웹 토렌트 상세 피어 행에 `progress` %(100%면 초록·굵게 "시더") 표시 + `torrentMinSeedWaitSec`(기본 0=꺼짐) 경과 후 `pause()` — 씨앗 없는 개인 시더 토렌트가 무한 업로드로 배터리 소모하는 문제
- **업로드 진행률**: `uploadFiles`를 XHR + `xhr.upload.onprogress`로 전환(진행률 바 + 완료/실패 toast), multipart→**raw-upload 스트리밍**(`X-File-Name`/`X-File-Path` encodeURIComponent) + 웹 `#uploadTargetLabel`(대상 폴더)·`#uploadProgressLabel`
- **폴더 날짜 표시**: `/api/storage`의 `modified`를 웹 보관함(`count개 · 날짜`)·앱 FilesScreen 메타 텍스트로 표시

### Fixed [android] — 웨일(Whale) HTTPS 접속 불가
- **증상**: iPad 사파리는 경고 후 접속(GitHub Pages와 다른 엔드포인트), **웨일은 접속 불가**
- **원인**: HTTP→HTTPS 강제 301 리다이렉트 + **mkcert 자체서명 인증서** — Chromium(웨일/크롬)은 `NET::ERR_CERT_AUTHORITY_INVALID`로 하드 차단
- **해결**: `forceHttpsRedirect`(기본 **false**) 신규 설정 — 꺼짐이면 LAN HTTP를 그대로 서빙(자체서명 미신뢰 브라우저 호환), 켜짐이면 기존 301→HTTPS(8443). 리다이렉트 대상 호스트도 `lanAddress()`(핫스팟 swlan0) 우선 보정
- **버전**: versionCode 13→14, versionName 0.13.3→0.14.0, 릴리즈 서명 v0.14.0 실기기 재설치

## [0.13.3] - 2026-08-28

### Release — 공개 배포 시작
- **첫 공개 GitHub Release** (v0.13.3): README + GitHub Pages 랜딩(docs/index.html) 추가
- **릴리즈 서명 체계 도입**: 신규 릴리즈 keystore(`apps/android/keystore/droidrelay-release.jks`, gitignore) + `keystore.properties`(gitignore) → `build.gradle.kts` `signingConfigs.release` 연결. 실기기 `R5CT215F4QK`에 서명 릴리즈 v0.13.3 설치·서명(SHA-256 `2f1dc84a…`) 확인
- **버전 정리**: `versionName` 0.13.0 → **0.13.3** (versionCode 13 유지)
- **보안**: `.gitignore`에 `*.jks`·`*.keystore`·`keystore.properties` 추가 — 서명 키 커밋 방지

### Fixed [web] — 모바일 레이아웃 후속
- **보관함/휴지통 가로 100%**: `.storage-main{width:100%}` — flex column에서 `.storage-main`이 명시 폭 없이 일부만 채워 비어 보이던 문제 해결
- **설정 메뉴 오른쪽 여백**: `.settings-nav`에 `padding:0 12px` + `box-sizing:border-box` — 12개 칩이 가로 스크롤될 때 우측이 화면 가장자리에 붙던 문제 해결
- **카드 액션 삐져나감 (다운로드·토렌트)**: `.card-acts`에 `box-sizing:border-box` — `width:100%` + 좌우 `padding`이 content-box로 더해져 카드 폭(366px)을 벗어나던 문제 해결 (모바일 390px 실측: acts 폭 364px, 카드 안에 정확히 수용)

### Changed [web] — 서브타이틀 문구 축약
- `.sub`: `이 페이지에서 요청하면 휴대폰이 직접 다운로드합니다 · 끊겨도 이어받기됩니다` → **`휴대폰이 직접 다운로드합니다 · 끊겨도 이어받기됩니다`**

### Changed [android] — 폴링 API 디버그 로그 억제
- 정보 획득용 GET 폴링 엔드포인트(`/api/info`, `/api/jobs`, `/api/torrents`, `/api/guard/status`)를 `DebugLogger.api` 자동 기록에서 제외 — 웹 `refresh()`에 의해 빈번히 호출되어 API 버퍼/Logcat을 채우던 노이즈 제거 (RelayServer.kt `pollExempt`)

### Verified (E2E)
- `assembleDebug` 성공 + 실기기 재설치. agent-browser 모바일(iPhone 14, 390px) 실측: 다운로드/토렌트 카드 `.card-acts` L=13~R=377 (카드 R=378) 삐져나감 없음. 설정 `.settings-nav` 우측 여백 확보 확인

## [0.13.2] - 2026-08-28

### Added [web] — 모바일 대응
- **`@media (max-width:640px)` 미디어 쿼리 추가** — CSS 기반으로 모바일 레이아웃 재배치 (HTML/JS 변경 없음):
  - `.wrap` `min-width:480px` 해제 → 가로 스크롤 제거
  - 정보바 `.info` 세로 접기 + 좌/우 항목 wrap
  - 다운로드/토렌트 카드 `.card` 세로 + `.card-acts`(112px 고정) 해제, 버튼 카드 아래 가로 배치
  - 보관함 `#treePanel` 숨김 → 경로 이동만으로 탐색 (데스크톱은 유지)
  - 설정 `settings-nav`(140px 사이드바) → 상단 가로 스크롤 칩, 콘텐츠 전체 폭
  - 입력 행(`.row`/`.row-torrent`) wrap + 입력 전폭

### Verified (E2E)
- `assembleDebug` 성공, 실기기 서버 응답에 `@media(max-width:640px)` 반영 확인. 모바일 4탭·보관함·설정 시각 검증은 폰 뷰포트에서 권장

## [0.13.1] - 2026-08-28

### Added [android+web] — 스트림 진행률 · 배지 정렬 · 한글 파일명 · 팝업 5종
- **스트림 진행률 (재작업·신뢰성 확보)**:
  - **회귀 발견**: FFmpegKit `LogCallback`은 HLS `Opening '...ts'` 로그를 **불안정 전달** — 동일 미디어 m3u8도 실행마다 64/64 또는 2/64에서 정체(로그 기반 세그먼트%는 신뢰 불가, 실기기 증명)
  - **해결**: FFmpeg argv에 `-progress <file>` 추가 → `pollProgress`가 1초마다 파일의 `out_time_us`를 읽고 총 재생시간으로 나눠 % 계산(신뢰/단조). 우선순위: `out_time/총재생시간` → 세그먼트(보조) → 파일 크기/총용량
  - `StreamDetector.playlistDurationMs`(`#EXTINF` 합)·`mediaDurationMsFromUrl`(마스터면 첫 variant 팔로우), `Job.totalDurationMs`, `VideoDownloadManager.parseOutTimeUs` 순수 함수(TDD), `/api/jobs`에 `totalDurationMs` 응답
  - **웹 UI**: 비디오 카드에 재생시간 %·용량·경과/남은시간 표시(세그먼트는 불안정 로그라 보조만). 실기기 `진행검증.mp4` 1→100% 단조 증가·DONE/63.9MB 검증
- **배지 정렬**: 웹 다운로드 카드 state·video 배지를 `.badges` 그룹으로 묶어 한 줄 정렬
- **파일명 한글 깨짐 — 회귀 테스트로 확정 처리**: 현재 코드에는 조각화 원인 없음(모든 sanitizer가 한글 유지, 직접 URL은 `96570b6`로 해결). `safeFilename` 한글 보존 회귀 테스트 추가 + 실기기 `한글파일명_테스트.mp4` JSON에 깨짐 없이 저장 확인
- **confirm/prompt 제거 → 팝업 레이어**: 웹에서 브라우저 `confirm()`/`prompt()`를 9곳 전부 제거하고 커스텀 `.overlay`/`.popup`(makeOverlay·confirmPopup·promptPopup·delJobConfirm)으로 대체 — 휴지통 비우기·폴더 생성/rename·파일 삭제·다운로드 삭제·RSS 삭제·설정 초기화 등

### Verified (E2E)
- ktlint + `testDebugUnitTest` 10건 GREEN(진행률 파싱·세그먼트·한글 파일명) + assembleDebug + WebAssets JS `node --check`
- 실기기 2회 완주: 직접 미디어 m3u8 다운로드(진행률 1→100% 신뢰성 증가, DONE/63.9MB), 한글 파일명 보존 확인

## [0.13.0] - 2026-08-28

### Added [android+server] — 비디오 다운로드 개선 (MP4 직접 · 해상도 선택 · 재요청)
- **MP4 직접 다운로드**: `.mp4/.webm/.mov` 직접 URL 및 웹페이지 임베디드 동영상 스니핑(정규식) 지원. `StreamDetector.kindOf` = `stream`|`mp4`|`page`, `Found(kind, qualities)` 추가
- **해상도 선택**: HLS 마스터(`#EXT-X-STREAM-INF:RESOLUTION=`)·DASH(`Representation` width/height) 매니페스트를 GET해 variant(해상도) 목록 파싱 — `parseHlsMaster`/`parseDashManifest` 순수 함수(TDD, 신규 `StreamDetectorTest` 8건 GREEN). 스트림 인식 시에만 라디오 해상도 선택 제공(MP4는 파일명만)
- **파일명 변경**: `create` 시 `filename` 지정 가능. 웹 `#vname` input·앱 `OutlinedTextField`로 편집
- **재요청(재시도)**: FAILED video 잡에서 `VideoApi.create(url)` 재호출로 재분석→재다운로드(부분 재개 불가). 웹 `retryVideo`·앱 `JobCard` video 분기 구현
- **에러코드**: `E-AND-VID-0205`(비디오 재시도 실패) 추가 — `error_message_ko.json` 반영
- **직접 주소 flow 강화**: `fetch`에 브라우저 헤더(Referer·Sec-Fetch-*·Accept-Language 등) 보강 — 직접 m3u8/mpd·접근 가능한 페이지의 봇 차단 일부 통과. `E-AND-VID-0200` 403 안내 문구를 Cloudflare/봇 차단 안내로 명확화
  - 참고: Cloudflare Turnstile(토렌트씨)처럼 JS 챌린지를 요구하는 사이트는 OkHttp로 원천 불가 — 브라우저에서 m3u8/mpd 주소를 직접 복사해 입력해야 함

## [0.12.2] - 2026-08-28

### Removed [android+server] — YouTube/yt-dlp 지원 전면 제거
- **Mac yt-dlp 서버 의존 제거**: `ytdlp_server.py`, `/proxy` 스트리밍 프록시, `YtDlpClient.kt` 삭제 — 폰은 외부 서버 없이 독립 동작. (yt-dlp 방식은 PoToken/봇가드 등 유튜브 정책 변화로 막힐 위험이 커 완전 폐기)
- **YouTube 관련 코드 제거**: `SettingsRepository.ytdlpEnabled/ytdlpServerUrl/ytdlpApiKey` 필드·키·세터, `/api/settings/ytdlp` GET/POST, `VideoApi`의 유튜브/yt-dlp 분기와 `formatId` 파라미터 제거 → 스트림(m3u8/mpd) 단일 경로로 통합
- **UI 제거**: 앱 `SettingsScreen` "비디오 (YouTube)" 설정, `DownloadsScreen` 유튜브 포맷 바텀시트, 웹 설정 "🎬 yt-dlp 서버" 섹션·연결 테스트, 웹 `analyzeVideo` 유튜브 포맷/해상도 선택 UI
- **에러코드 정리**: `E-AND-VID-0102`(yt-dlp 분석 실패)·`0203`(YouTube 차단) 제거, `0101` 문구를 유튜브 무관 "지원하지 않는 URL"로 정정 — `error_message_ko.json`/코드 반영

### Fixed [android] (유지)
- **실패 알림 반복 재발송 + 무의미 재시도 루프 차단**: FAILED로 고정된 잡이 진행률성 갱신을 받을 때마다 `실패 알림`이 **2초 간격으로 무한 반복**(소리·노티 폭주)되던 문제 — 실패 알림을 **상태 전이 시에만**(이전 상태가 FAILED가 아닐 때) + **동일 원인(에러코드+메시지) 재발신 금지** 가드로 1회만 발송. `retryFailed()`는 `type=="video"` 잡 제외(FFmpeg/VideoDownloadManager 소관) → 반복 재다운로드 루프 원천 차단

### Verified (E2E)
- ktlint + assembleDebug 통과, 앱/웹에서 yt-dlp·YouTube 참조 0건, 스트림(m3u8/mpd) 분석→다운로드 회귀 확인

## [0.12.1] - 2026-08-28

### Added [android] — 앱 Compose 비디오 UI (T-863)
- **DownloadsScreen '🎬 비디오' 섹션**: URL 입력 → 분석(스트림/YouTube) → 제목 확인 → **유튜브는 포맷 바텀시트**(자동 병합/해상도·확장자·대략 크기 표시) 선택, 스트림은 원본 copy 즉시 다운로드. JobCard에 `🎬` 배지 *(0.12.2에서 유튜브 포맷 시트 제거)*
- **SettingsScreen '비디오 (YouTube)' 설정**: yt-dlp 서버 사용 토글 + 서버 URL/API 키 저장 *(0.12.2에서 제거)*
- **공용 `VideoApi` 오케스트레이션**: RelayServer의 /api/video/analyze·create 인라인 로직을 별도 모듈로 추출 — 웹·앱이 동일 경로(에러코드 일치) 사용, 실패 응답 `{code}: {msg}`(422)로 통일
- **VideoDownloadManager 진행 폴링 전환(버그 수정)**: `StatisticsCallback`은 `-c copy` 리먹스에서 이벤트를 내지 않아 진행률이 0에 머물던 문제 — 출력 파일 크기 **1초 폴링**(`SessionState` 종료 감지)으로 교체. 50MB급 스트림에서 0→206MB 진행·254KB/s 실측 갱신

### Removed [android+web] — (History) 유튜브 yt-dlp 서버 연동
- **(2026-08-28, 0.12.2에서 전체 제거됨)** ~~`ytdlp_server.py` 연동, `/proxy` 프록시, 웹 유튜브 포맷 선택~~ — 해당 기능은 v0.12.2에서 폐기

### Changed [android+web]
- **SHA-256 검증 기능 제거**(사용자 요청): `Job.expectedSha256/verified` 필드, `JobsPersistence` 저장/복원, `DownloadEngine` 스트리밍 체크섬 검증·`sha256()` 함수, `/api/jobs`의 `sha256` 입력·`hasChecksum/verified` 응답, 웹 UI 입력·`✓ 검증됨` 배지 전부 제거 (웹훅 `X-DroidRelay-Signature` 서명 유지)
- **토렌트 단일 파일 보관함 이동 수정**: `moveToStorage`가 `isDirectory` 가드로 단일 파일 토렌트를 스킵하던 버그 — 파일은 `copyTo+delete`, 디렉토리는 기존 복사 분기 처리
- **비디오 진행률 실시간화**: `TICK_MS` 2000→1000 + StatisticsCallback 연결 — 0→100% 점프 없이 1초 단위 갱신 확인(HTTP 50MB, Mux HLS)
- **웹 정보 바 토렌트 반영**: HTTP 잡이 없으면 무조건 "대기중..."으로 나오던 허위 표시 해소 — 토렌트 DOWNLOADING/SEEDING/FETCHING_METADATA 건수·속도 합산 표시, 토렌트 카드에도 FETCHING_METADATA 속도 표시
- **RelayService 기동 실패 원인 표출**: "포트 8080 이미 사용 중" 하드코딩 문구 → 실제 예외 메시지 노출. (원인: `adb reverse tcp:8080`이 폰 8080을 점유 — reverse 제거로 해소)

### Verified (E2E)
- **HTTP 다운로드 진행률**: 50MB 파일 0→100% 1초 단위 갱신, DONE 확인, `/api/jobs`에서 `verified`/`hasChecksum` 제거 확인
- **토렌트 실속도**: sintel(.torrent 업로드) DOWNLOADING down=104→418KB/s·up=7~18KB/s·시드/피어 실측 갱신 확인

### Notes
- ~~yt-dlp 서버 운영 안내·`adb reverse`~~ *(0.12.2에서 유튜브 제거로 폐기)*

## [0.12.0] - 2026-08-27

### Added [android+web] — 비디오 다운로드 (범용 스트림 : 유튜브 제외)
- **비디오 API 2종**: `POST /api/video/analyze`(URL → 스트림 URL·제목), `POST /api/video/create`(`{url, streamUrl?, filename?}` → Job). 웹 UI 다운로드 탭에 "🎬 비디오" 섹션(URL 입력 → 분석 → 원본 그대로 다운로드 + 파일명 설정)
- **StreamDetector**: 웹페이지 HTML에서 `.m3u8`/`.mpd` 주소 정규식 스니핑(상대경로 절대화, 브라우저 UA) + 직접 스트림 URL 입력 병행 — 403/Cloudflare 차단 시 직접 입력 안내 (E-AND-VID-0200)
- **VideoDownloadManager**: FFmpegKit `executeAsync`로 스트림을 `-c copy -movflags +faststart` 원본 회차 다운로드. 진행률(`-progress` Statistics→downloadedBytes/speedBps) · 취소(`FFmpegKit.cancel`) · 완료 시 기존 DownloadEngine 방식대로 보관함(MediaStore) 게시. Job에 `type: VIDEO` 도입(하위호환) + `🎬` 배지
- **비디오 엔진 의존성**: `dev.ffmpegkit-maintained:ffmpeg-kit-https:8.1.7` — `full` 변형은 TLS 미포함(`https or dtls protocol not found`) 확인 후 **`https` 변형 채택**
- **에러코드** `E-AND-VID-0100/0101/0200/0201/0202/0300/0400/0401` → `error_message_ko.json`

### Removed [android+web]
- **유튜브 지원 제거**: NewPipeExtractor 0.26.5(최신) + visitor_id 주입 + ANDROID_VR 클라이언트·UA 스푸핑 우회를 모두 시도했으나, 2026 유튜브 PoToken 강제 + 통신사 LTE NAT IP 평판 차단(`Sign in to confirm you're not a bot`, WAN IP 2종에서 동일)으로 비로그인 추출이 불가 — **기능 제외**. `TubeEngine.kt` 삭제, analyze/create 유튜브 분기 제거, `newpipe-extractor`·JitPack 의존성 제거, 웹 UI 유튜브 안내·스타일 정리, 유튜브 전용 에러코드(0102) 정리

### Verified (E2E)
- **m3u8 스트림 다운로드**: Mux HLS `url_0/193039199_mp4_h264_aac_hd_7.m3u8`로 analyze(kind:stream) → create(잡 `mtbvvjq622`) → RUNNING 진행·속도 리포트(162.37MB) → DONE → `Download/DroidRelay/mux_hls_test.mp4` 게시 → `ffprobe` 무결성(mov,mp4, duration 634.6s, 170,260,672B)
- **유튜브 제거 후 회귀 스모크**: 재설치 후 analyze→create(RUNNING 22MB 진행)→DELETE 취소(REMOVED_OK) 정상. ktlint + assembleDebug 통과

### Notes
- APK 크기 네이티브(FFmpeg) 포함 증가 — 개인 배포(GitHub) 대상이라 영향 없음
- 유튜브 대체: m3u8/mpd 직접 주소 또는 스트리밍 페이지 경로 *(0.12.2에서 유튜브 지원 완전 폐기)*

## [0.11.1] - 2026-08-27

### Added [android+web] — HTTPS 다운로드 (웨일 "안전하지 않은 다운로드" 경고 해결)
- **Ktor 엔진 CIO→Netty 전환**: CIO는 HTTPS 미지원(`UnsupportedOperationException: CIO Engine does not currently support HTTPS`) — Netty 엔진으로 교체하고 HTTP(8080)+HTTPS(8443) **이중 커넥터** 구성. Netty 4.2의 `META-INF/INDEX.LIST` 등 merge 충돌은 `packaging.resources.excludes`로 해결
- **자체 서명 TLS 인증서**: `mkcert` 로컬 CA 서명으로 `apps/android/app/src/main/assets/certs/server.p12` 배포 (SAN: `localhost, 10.64.228.42, 127.0.0.1, ::1`, 별칭 `relay`). 맥 브라우저는 최초 1회 "고급→계속" 후 다운로드 정상 — 맥 login 키체인 CA 등록은 선택사항(현재 미등록, 경고 1회 감수)
- **다운로드 링크 HTTPS 절대 경로 전환 (웹)**: 보관함 `renderStorage()`의 받기 링크를 `https://<location.hostname>:8443/dl-file/...`로 변경 — HTTP 페이지에서 열어도 다운로드는 항상 HTTPS로 전송되어 Mixed Content/Insecure Download 차단 회피
- **`/dl-file` 보안 헤더**: `X-Content-Type-Options: nosniff` + `Cache-Control: no-store, must-revalidate` 추가
- **HTTP→HTTPS 자동 리다이렉트 (307)**: LAN 클라이언트가 `http://…:8080`으로 접속하면 `https://…:8443`으로 이동시켜 페이지·다운로드 모두 안전 채널 유지. 단 **loopback(localhost/127.0.0.1/자기 LAN IP)은 예외** — 터널(tailscaled)·앱 자체 점검은 자체 서명 인증서를 신뢰하지 않으므로 HTTP 그대로 (보안 파이프라인 최상단 intercept, `RequestConnectionPoint.scheme` 기준)

### Verified (E2E)
- 맥에서 `https://10.64.228.42:8443/` 200, ISO(7.1MB) HTTPS 전체 다운로드 200 — HTTP/HTTPS **MD5 동일**(`b61fe3fe…`), `openssl s_client`로 SAN 정합 확인. 맥 인증서 신뢰는 최초 1회 경고 후 사용자가 "계속" 선택하는 방식(선택적 CA 등록 없음)
- `http://10.64.228.42:8080/` 접속 → **307 Location `https://10.64.228.42:8443/`**, follow 시 200 — `/dl-file`도 동일 리다이렉트 후 ISO 전체 수신(7112896B). 기기 내부 `curl http://127.0.0.1:8080/`는 200(리다이렉트 예외) 확인

## [0.11.0] - 2026-08-27

### Changed [android] — 배터리/성능 최적화 (v0.10.2 대비 방출·디스크 I/O 대폭 감소)
- **WakeLock 완전 제거**: 24시간 강제 대기 유지(`PARTIAL_WAKE_LOCK`) 제거 — Foreground Service(`DATA_SYNC`)만으로 실행 유지. `dumpsys power`로 WakeLock 0건 확인
- **jobs.json 저장 실질 디바운스 구현**: 기존에는 주석("디바운스")과 달리 매 방출마다 전체 JSON을 디스크에 쓰던 은닉 버그 — 10초 디바운스 + 상태 전이 시 즉시 저장으로 수정. 다운로드 중 저장 빈도: 틱당(400ms) → ~10초 간격(13초 전송 중 3회: 전이 2 + 주기 1). **디스크 쓰기 약 90% 감소 (PERF: CACHE)**
- **다운로드 진행 틱 400ms→2000ms**: 진행률 StateFlow 방출 5배 감소. E2E로 ~2초 간격 진행 갱신 확인
- **토렌트 상태 폴링 1초→5초** + `persistNow/persistDebounced` 실변경 가드(저장 간격 10초) — 폴링/저장 부담 5배 감소
- **Repository 동일값 스킵**: `update()`에서 `after == before`면 `refresh()`(StateFlow 방출) 생략 — 불필요 방출 제거
- **가드 데몬 30초→120초 폴링** + `settingsNow()` 5분 캐시 TTL (WorkManager 미도입, in-process 확대 방식 결정) — "가드 데몬 시작 (120초 폴링)" 확인
- **RSS 매니저 누수 수정**: onCreate의 지역 변수 생성으로 서비스 종료 후에도 폴링이 지속되던 것 → 필드 보관 + onDestroy stop
- **알림 700ms→2000ms 스로틀**: 알림 갱신 동기화 부하 감소
- **DebugOverlay**: 이중 `startInForeground()` 제거 + 상태 폴링 1초→3초
- **가드 온도 임계치 30~60 → 50~70°C 확장**: 실제 폰 온도가 60°C에 근접하므로 70까지 설정 가능하게 확장, 기본값 45→50°C. `setGuardThermalLimit`의 누락된 coerceIn(50,70) 포함 모든 지점 반영 (웹 슬라이더, 서버 검증, 로드 클램프)
- **목록 우측 버튼 크기 통일 (웹)**: 다운로드/토렌트 카드 우측 버튼 영역을 `.card-acts` 클래스로 통합 — 컬럼 폭 112px 고정 + 버튼 `width:100%` (받기는 초록 강조색 유지), 보관함 파일 행 아이콘 버튼(`📥`/`✏️`/`🗑`)은 34px 정사각으로 통일. `box-sizing:border-box`로 픽셀 완전 일치. CDP 좌표 검증: 받기·삭제 112px 동일 폭·중심 정렬, 아이콘 3종 34px 확인

### Fixed [android]
- **전역 속도제한 설정 시 다운로드 0Byte 즉시 완료**: `ThrottleInterceptor`의 `ThrottledResponseBody.source()`가 `delegate.source().buffer()` → `ThrottledSource` → `.buffer()` **이중 버퍼 래핑**으로 okhttp 응답 body가 즉시 EOF(0Byte) 되던 버그 — 표준 단일 래핑(`ThrottledSource(delegate.source(), …).buffer()`)으로 수정. 속도제한(5MB/s·무제한) 모두에서 8KB/100MB 완전 수신 확인. 기존에는 maxDownloadBps>0면 모든 엔진 다운로드가 필연 실패했음
- **다운로드 로그 용량 표기 오류**: `DownloadEngine.fmt()`의 MB 분기가 **GiB 제수(1,073,741,824)**를 사용 — 1MiB 초과 파일이 실제의 약 10분의 1로 표기(100MB→「0.1MB」, 평균 속도도 왜곡)되던 것 → MiB 제수(1,048,576)로 교정

### Verified (E2E)
- WakeLock 부재(`dumpsys power` 0건), 다운로드 100MB 전체 수신 + 저장 빈도 감소, 2초 틱, 토렌트 초기 저장 후 스킵, 가드 120초 폴링

## [0.10.2] - 2026-08-27

### Added [android+web]
- **웹 정보 바 리디자인**: 상단 요약바 좌/우 고정 레이아웃 — 좌측(가변) = 진행 건수 + 총 속도(대기 시 회색 "대기중..."), 우측(고정) = 저장공간 여유 · 온도/배터리 · 버전. 폰 온도·배터리 실시간 표시 (`/api/guard/status` 연동). 보호 영역 min-width 480px로 모바일 압축 방지
- **가드 상태 색상 구분**: 임계치 초과 시 `⚠ 스로틀링` 배지 + 빨강 강조, 가드 비활성 시 회색 "가드 끔", 정상 시 초록
- **RSS 자동 다운로드 확장**: 항목 URL이 `magnet:` 또는 `.torrent`면 일반 다운로드 대신 TorrentEngine(`addMagnet`/`.torrent 추가`)으로 라우팅
- **터널 웹 UI 설정 섹션 (🔗 터널)**: 설정 사이드바에 터널 메뉴/패널 추가 — 터널 사용 토글, 제공자 선택(Tailscale/Cloudflare, 하이라이트), 저장 시 `POST /api/settings/tunnel`, "🔍 현재 상태"로 `GET /api/tunnel/status` 연동(연결 시 IP·접속 URL 표시, 미연결 시 이유 포함). `loadSettings()/switchSettingsSection()`에 터널 연동. 실기기 E2E — ON/OFF·제공자 전환·비활성 상태 표기·Tailscale 미설치/cloudflared 필요 구분 모두 검증
- **TUNNEL_GUIDE.md**: Tailscale/Cloudflare 터널 시나리오·현재 한계점·로드맵 문서 신설

### Changed [android+web]
- **보관함↔설정 디자인 통일**: 보관함 오른쪽 콘텐츠를 설정 콘텐츠 스타일(라운드 카드 `.sg` + 섹션 헤더 `.sh`)로 재구성 — "🗂 파일 관리" 액션 카드 + "📄 폴더 내용/🗑️ 휴지통" 목록 카드, 파일 행을 리스트 그룹 스타일로 변경 (구분선+호버 배경, 단일 행엔 구분선 생략)
- **RSS "지금 확인" 버튼 수정**: `/api/rss/0/check`가 "피드 없음"으로 무응답하던 버그 — id "0"을 전체 피드 확인의 특수값으로 허용
- **RSS 에러 표면화 개선**: HTTP 비 2xx(403 등)/HTML(Cloudflare JS 챌린지) 응답을 구분해 의미 있는 오류 메시지로 표시 (`HTTP 403 (Cloudflare/보안 챌린지 차단 가능)` 등), 브라우저 UA 채택, 실패 시에도 `lastCheckedAt` 갱신해 UI에 확인 시각 노출

### Fixed [android]
- **디버그 오버레이 토글 크래시**: `DebugOverlayService`가 `startForegroundService()`로 기동되면서도 `startForeground()`를 호출하지 않아(ForegroundServiceDidNotStartInTimeException) 프로세스가 즉시 종료되던 버그 — `startInForeground()` 추가 (`relay_status` 채널·`NOTIF_ID 2002`, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`)
- **오버레이 토글이 OFF 불가**: `/api/debug/overlay/toggle`이 항상 시작만 하고 중지는 불가능했음 — `DebugOverlayService.isRunning` 상태 기반 진짜 ON/OFF 토글로 수정
- **토글 응답 비동기 경합**: start/stop 직후 `isRunning` 플래그가 뒤늦게 변해 응답이 뒤집히던 것(`running` 반전) — 요청 시점의 `wasRunning` 기준 결정적 응답으로 수정
- **오버레이 권한 안내 문구 오타**: `权限이 없습니다`(중문) → `권한이 없습니다`(국문)로 교정
- **RSS 자동 다운로드 미동작 (enclosureUrl 빈 문자열)**: enclosure 태그가 없는 항목에서 `enclosureUrl`이 빈 문자열(`""`)이라 `?:`(null 전용)가 link로 대체하지 않아 다운로드 URL이 공백이 되던 버그 — `enclosureUrl?.takeIf { it.isNotBlank() } ?: link`로 수정. 정상 공개 피드 + 로컬 테스트 피드(magnet/`.torrent`/일반 URL 3종)로 E2E 검증 완료

## [0.10.0] - 2026-08-27

### Added [android+web]
- **Debrid 클라우드 다운로드 연동 (Phase 1.3)**: Real-Debrid / AllDebrid / Premiumize 공통 클라이언트. 설정 탭 > Debrid 섹션에서 API 키 입력, 계정 확인, 제공자 선택. `POST /api/debrid/unrestrict`로 언리스트링크 변환 → 고속 다운로드. 다운로드 추가 시 자동 적용
- **MCP 서버 내장 (Phase 2.1)**: JSON-RPC 2.0 프로토콜, `/mcp` + `/mcp/call` 엔드포인트. 도구: file_list, file_read, download_add, download_list, download_control. AI 에이전트 연동 지원
- **MCP 권한 설정 (Phase 2.1 확장)**: 도구별 ON/OFF + 프라이버시 모드 (file_read 차단). `GET/POST /api/settings/mcp`
- **웹훅/콜백 API (Phase 2.2)**: 다운로드 완료/실패 시 POST 콜백, HMAC-SHA256 서명, 지수 백오프 재시도(최대 3회), 데드레터 큐. `GET/POST /api/settings/webhook`
- **터널 매니저 (Phase 2.3)**: Tailscale / Cloudflare Tunnel 상태 관리, Tailscale 앱 감지 + IP 자동 탐지. `GET/POST /api/settings/tunnel` + `GET /api/tunnel/status`
- **가드 데몬 (Phase 2.4)**: 열/배터리/스토리지 임계치 모니터링 (30초 폴링). 임계치 초과 시 자동 다운로드 일시정지, 정상 복귀 시 재개. 설정 탭 > 가드 섹션에서 임계치 조정, 현재 상태 확인
- **스케줄/조건부 다운로드 (Phase 3 확장)**: 크론 표현식 파서 (5필드, step/comma/range 지원) + Wi-Fi/충전/배터리 제약. `GET/POST /api/settings/schedule`. JobScheduler 기반 자동 실행
- **외장 스토리지 자동 감지 (Phase 3 확장)**: USB OTG / SD카드 / 외장 SSD 마운트 감지, 여유 공간 표시, 권장 다운로드 경로 제안. `GET /api/storage/external`
- **메트릭스 API (Phase 3)**: `/api/metrics` — 서버 가동시간, 다운로드/토렌트 통계, 바이트 처리량, 실시간 속도. Prometheus/Grafana 연동 준비
- **설정 사이드바 확장**: Debrid(☁️), 가드(🛡), MCP(🤖), 스케줄(⏰), 스토리지(💾) 메뉴 추가 — 10개 섹션

### Changed [android+web]
- **다운로드 엔진 Debrid 통합**: POST /api/jobs 시 Debrid 활성화 상태면 자동 언리스트링크 → 고속 다운로드 URL로 변환 후 큐잉
- **설정 데이터 모델 확장**: AppSettings에 debridEnabled/debridProvider/debridApiKey, guardEnabled/guardThermalLimit/guardBatteryLimit/guardStorageLimit, webhookEnabled/webhookUrl/webhookSecret, tunnelEnabled/tunnelProvider 필드 추가
- **RelayService 가드 연동**: 가드 데몬 시작 + 상태 변경 시 다운로드 자동 일시정지/재개
- **디버그 모드 로그 증강 (Phase 3)**: McpServer(도구 호출/실행/완료 시간), TunnelManager(설치/인터페이스/IP 감지), StorageDetector(마운트/여유공간/bestPath), GuardDaemon(센서 수치 30초 주기), DebridClient(API 요청/응답), SchedulerManager(크론 파싱/제약/JobScheduler 등록 결과), ScheduleJobService(잡 ID/제약 결과), RelayService(컴포넌트 시작/정리) 전 구성요소 상세 로그 추가

### Added [android+web] (디버그 패널)
- **웹 디버그 패널 (별도 페이지)**: `GET /debug` — 독립 창으로 실시간 로그 모니터링 (1초 폴링), 로그/API 호출 탭, 레벨·태그·텍스트 필터, 일시정지, 복사, 내보내기
- **디버그 API 세트**: `GET /api/debug/logs`, `GET /api/debug/api-calls`, `GET /api/debug/status`, `POST /api/debug/clear`, `GET /api/debug/overlay`, `POST /api/debug/overlay/toggle`
- **API 호출 자동 기록**: Call 파이프라인 인터셉터 — 모든 `/api/*` 요청의 method/path/status/MS 기록 (`DebugLogger.api()` 링 버퍼 300줄)
- **설정 사이드바 "🐛 디버그" 섹션**: 디버그 패널 열기 버튼 + 실시간 상태 표시 + 오버레이 권한/토글

### Added [android] (플로팅 오버레이)
- **디버그 플로팅 오버레이**: `DebugOverlayService.kt` — `TYPE_APPLICATION_OVERLAY` 투명 오버레이. 최근 로그/API 호출 실시간 표시, 드래그 이동, 탭 시 일시정지, 1초 폴링
- **`SYSTEM_ALERT_WINDOW` 권한 + 서비스 등록**: AndroidManifest에 추가. 설정에서 오버레이 권한 요청 + 시작/중지 제어

## [0.9.0] - 2026-08-27

### Added [android+web]
- **시퀀셜 다운로드 지원**: 토렌트 설정 > 고급에 "시퀀셜 다운로드 (스트리밍 프리뷰)" 토글 추가. 활성화 시 첫 번째 조각부터 순서대로 다운로드하여 미디어 파일 재생 미리보기 지원
- **RSS/Atom 피드 자동 다운로드**: 설정 탭에 RSS 피드 관리 카드 추가. 피드 URL 등록, 키워드/정규식 필터, 15분 주기 자동 폴링, 매칭 시 자동 다운로드. `GET/POST/DELETE /api/rss` + `POST /api/rss/{id}/check`
- **PLAN_v0.9_android.md**: "Server Edition" 로드맵 문서 — MCP 서버, 가드 데몬, 터널링, 웹훅, RSS 피드, Debrid 연동 계획

### Changed [android]
- **TorrentEngine 시퀀셜 모드**: `TorrentFlags.SEQUENTIAL_DOWNLOAD` 플래그 적용 — magnet/torrent 파일 추가 시 설정에 따라 자동 적용, 기존 토렌트도 실시간 전환

## [0.8.0] - 2026-08-27

### Added [android+web]
- **설정 탭 UI 전면 재설계**: 4개 풀폭 카드(전역 속도 제한/다운로드 설정/토렌트 설정/기본값 복원), 서브그룹 분리(속도/연결/고급/포트/경로), 일관된 CSS 클래스 체계(.sg/.sh/.sr/.si/.sl/.sv/.ck/.sb/.ti/.ss/.ft/.fp/.rb), 인라인 스타일 제거
- **토렌트 엔진 기본값 권장값 적용**: 동시 2, 업로드 512KB/s, 시드비율 2.0, 랜덤 포트(49152~65535), 기본 저장 경로 /sdcard/Download/DroidRelay, DHT/PEX 활성화
- **경로 쓰기 권한 테스트**: `POST /api/storage/test-path` — 웹에서 경로 입력 후 즉시 테스트 가능

### Changed [android+web]
- **설정 API 부분 업데이트 보장**: `has()` 가드로 미지정 필드 보존(불리언 리셋 버그 수정)
- **전역 속도 제한 토스트 피드백**: 모든 설정 변경 시 저장 확인 토스트 표시(기존 토렌트만)

### Removed [macos]
- **macOS 네이티브 앱 완전 삭제**: apps/macos/, docs/DESIGN_v1.0_macos.md, docs/TODO_v1.0_macos.md, docs/plans/PLAN_v*_macos.md, docs/screenshots/macos/, .agent/session-*_macos.md, ~/Applications/DroidRelayClient.app, Xcode DerivedData — 웹 전용 아키텍처로 전환

## [0.7.1] - 2026-08-26

### Fixed [android]
- **토렌트 추가 직후 시드·피어·진행률 0 지연**: 매핑 성공 즉시 `forceReannounce`+`forceDHTAnnounce` 킥 추가(수동 매핑·폴링 자동 매핑 모두), 트래커 발표 전까지 통계 0으로 고정되던 문제 해소
- **파일 토렌트 매핑 레이스**: `delay(300)` 후 단 1회 `session.find()` → 최대 5초 재시도 루프로 교체
- **조각 정보 API 부재**: `/api/torrents`에 `piecesDone`/`piecesTotal` 신설(`pieceInfo()` = totalDone/pieceLength 기반)

### Added [macos]
- **토렌트 행 조각 표시**: 🧩 n/m 배지 추가(piecesDone/piecesTotal), 시드·피어 헬프툴팁
- **로컬 받기 진행률 실시간 표시**: `URLSession.download`(콜백 없음) → `bytes(for:)` 스트리밍 + 256KB 청크 쓰기, 0.25초 간격 진행률 갱신, Content-Length 기반 전체 크기, `.part` 이어받기 개선
- **편집 메뉴 누락 수정**: 커스텀 메인 메뉴에 편집 메뉴(⌘Z/⌘X/⌘C/⌘V/⌘A) 추가 — 없으면 모든 TextField 붙여넣기 불가

## [0.7.0] - 2026-08-26

### Added [macos]
- **메인 윈도우 사이드바 리디자인**: NavigationSplitView 5섹션(개요/다운로드/토렌트/보관함/설정), 개요 탭 통계 카드 4종 + 빠른 액션, 기본 창 1120×700
- **보관함 보기 전환**: 리스트/그리드 토글(AppStorage 기억, 기본 리스트)
- **보관함 파일→폴더 드래그 이동**: 행/카드 onDrag + 폴더 드롭 타깃 하이라이트, `POST /api/storage/move` 연동
- **다운로드·토렌트 드래그 순서 변경**: 잡/토렌트 행 드래그 → `/api/jobs/reorder`·`/api/torrents/reorder` 연동(순서 정렬 모드에서만 드래그 허용)
- **메뉴바 아이콘**: 백업 AppIcon.icns → StatusBarIcon.imageset(16x16@1x/2x, 템플릿 렌더링)

### Fixed [macos]
- **NSPanel 초기화 오류**: `NSPanel(contentView:)` 미존재 → contentRect 이니셜라이저 + `.contentView` 대입으로 수정(WindowFactory·DebugPanelWindow)
- **SwiftUI onDrop 오버로드 불일치**: macOS 26 SDK에서 `onDrop(of:isTargeted:delegate:)` 부재 → `onDrop(of:delegate:)` 형태로 전면 수정
- **접근 제어 충돌**: AppState `pollOnce`/`requireAPI` private → internal, MainView 중복 requireAPI 확장 제거
- **재귀 뷰 opaque type 에러**: StorageView 폴더 트리 재귀 → AnyView 타입소거

## [0.6.0] - 2026-08-26

### Added [android+web]
- **웹 SSE 실시간 푸시 (T-701)**: `/api/events` text/event-stream 1초 tick → 웹 즉시 갱신, 끊기면 1초 폴링 폴백 + 5초 재연결
- **다중 URL 일괄 추가 (T-702)**: 웹 입력에서 줄바꿈/공백/쉼표 구분 여러 URL 순차 추가, 결과 집계 토스트
- **보관함 휴지통 (T-703)**: 삭제 시 `.trash/` 이동(삭제 실수 방지), 복구(보관함 루트로), 개별 영구삭제, 전체 비우기 API + 웹 UI
- **체크섬 검증 (T-704)**: 다운로드 추가 시 선택 SHA-256 입력(64자리 검증), 완료 후 스트리밍 digest 비교 — 일치 시 ✓검증됨 배지, 불일치 시 FAILED `E-AND-DOWN-1005`
- **웹 폴더 트리 사이드바 (T-608)**: `/api/storage/tree` (깊이 3, .trash 제외) + 보관함 탭 2단 레이아웃, 클릭 탐색 + 현재 경로 하이라이트

### Added [macos]
- **다운로드 추가 시트**: 다중 URL(줄바꿈/공백 구분) + SHA-256 선택 입력 필드, 단일 URL에만 체크섬 적용

### Fixed [android]
- **HTTP 다운로드 전면 차단 버그**: Android cleartext 정책으로 `http://` URL 다운로드 실패 → `usesCleartextTraffic="true"` 추가
- **일시정지 진행바 0% 리셋 회귀 (T-601 재발 방지)**: 앱 재시작 시 복원된 작업의 progress가 0으로 표시 → `JobsRepository.restore()`에서 `downloadedBytes/totalBytes` 재계산 (근본 수정)
- **대용량 업로드 멈춤**: Ktor CIO multipart 63MB 버퍼 정체 → `POST /api/storage/raw-upload` 스트리밍 엔드포인트 신설 (196MB 11초), 맥 클라이언트 전환

## [0.5.0] - 2026-08-25

### Added [macos]
- **macOS 네이티브 클라이언트**: DroidRelayClient 메뉴바 앱 (MenuBarExtra .window)
- **서버 자동 스캔**: 실행 시 LAN /24 서브넷 동시 프로브 (128 동시, 0.7초 타임아웃)
- **수동 서버 입력**: IP:port 직접 입력으로 연결 (설정에 저장)
- **다운로드 관리**: URL 추가 → 잡 목록 → 진행률/속도 표시 → 일시정지/재개/삭제
- **⬇ 받기**: 완료된 파일을 로컬 저장 폴더로 스트리밍 (Range 이어받기, .part 임시파일)
- **토렌트 관리**: magnet 링크 입력 + .torrent 파일 추가 → 목록 표시 + 제어
- **보관함 탐색**: 서버 /sdcard/Download/DroidRelay 폴더/파일 브라우징
- **보관함 관리**: 폴더 생성, 이름 변경, 잘라내기/붙여넣기(이동), 삭제, 파일 업로드
- **설정**: 저장 폴더 지정, 자동 스캔 토글, Basic Auth 인증, 로그인 시 실행(SMAppService), 메뉴바 속도 표시
- **디버그 로그 창**: os.Logger + 500줄 순환 버퍼, 전체 복사
- **앱 아이콘**: CoreGraphics + iconutil 생성 (다운로드 화살표 디자인)
- **테스트**: Models JSON 디코딩 + Discovery URL 생성 + 포맷 계산 등 10건

## [0.4.0] - 2026-08-25

### Added [android]
- **Torrent 클라이언트**: libtorrent4j 기반 torrent 다운로드 엔진 추가
- **magnet 링크 지원**: magnet:?xt=... 직접 입력 → 다운로드
- **.torrent 파일 지원**: 파일 선택 → torrent 추가
- **4탭 내비게이션**: 다운로드 / Torrent / 파일 / 설정
- **Torrent 탭 UI**: torrent 목록, 상태 표시, 일시정지/재개/삭제
- **대역폭 제한**: 다운로드 무제한 / 업로드 0KB/s (기본, 설정에서 변경 가능)
- **DHT/PEX**: 분산 해시 테이블 + 피어 교환 지원
- **웹 대시보드 Torrent API**: /api/torrents, /api/torrents/add, /api/torrents/{id}/*, DELETE
- **Torrent 알림**: torrent 완료/실패 시 알림 (별도 채널)
- **Torrent 설정**: 업로드/다운로드 속도, 최대 활성 torrent, 시드 ratio, DHT/PEX 설정
- **Torrent 상태 영구 저장**: torrent.json 기반 저장/복원
- **GitHub Releases**: APK 자동 빌드/배포

## [0.3.0] - 2026-08-25

### Added [android]
- **네트워크 복구 자동 재시작**: ConnectivityManager 감지 → FAILED 작업 자동 재시도 (에어플레인 모드/네트워크 끊김 후 자동 복구)
- **클라이언트 접속 범위 설정**: 같은 핫스팟만 (기본) / 암호만 있으면 / 승인만 — 설정 화면에서 선택
- **시작/완료 시간 표시**: Job.startedAt/finishedAt 필드 + Persist 저장
- **네트워크 타입 실시간 표시**: Wi-Fi / LTE / 5G / 3G / 2G 감지 → 서버 카드에 표시 (5초 폴링)
- **서버 주소 공유**: 공유 버튼 → QR 이미지+텍스트 Intent → 카카오톡/문자/이메일 등 모든 앱으로 전송
- **확장자별 아이콘·색상**: 🎬비디오(빨강) 🎵음악(보라) 🖼이미지(파랑) 📄문서(주황) 📦압축(초록) ⚙실행(회색)
- **파일 이름변경**: 수정 아이콘 → 다이얼로그 → MediaStore 업데이트
- **삭제 확인 다이얼로그**: 삭제 전 AlertDialog 확인 → MediaStore 삭제
- **배지 제어**: 서버실행 알림은 배지 OFF, 다운로드 완료/실패 알림은 배지 ON

### Changed [android]
- AndroidManifest.xml: ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, READ_PHONE_STATE 권한 추가
- FileProvider 설정 (QR 이미지 공유용 cache 디렉토리)
- 알림 채널 분리: relay_status (배지 OFF) + relay_result (배지 ON)

## [0.2.0] - 2026-08-25
- MD3 디자인 시스템·설정·보안·다운로드 매니저 고도화 (커밋 5956cfc)

## [0.1.0] - 2026-08-25
- 초판 (커밋 7574486)
