# 🩺 종합 안정성 점검 리포트 — DroidRelay v0.39.0

- **점검일**: 2026-09-26
- **대상**: `apps/android/` (Kotlin + Compose Material 3 + Ktor 3.5.2 + libtorrent4j 2.1.0-39, targetSdk 36)
- **방식**: 3개 축 병렬 정적 분석 (CPU/전력 · 크래시/안정성 · 설정 적용) + 전 항목 실코드 대조 검증
- **점검 규모**: Kotlin 79파일 / 19,546행
- **결과**: **46건 수정** (크래시 12 · CPU 11 · 설정 8 · 안정성 14 · 실기기 발견 1) · 테스트 184 → 213건
- **커밋**: `fix/torrent-audit-2026-09-26` → `refactor/stability-cpu-settings`

> ⚠️ 이 리포트의 모든 결함은 서브에이전트 분석 결과를 **원본 코드로 1:1 대조 검증**한 뒤에만 반영했다.
> 검증 없이 보고된 항목은 없다.

---

## 1. 요약

| 축 | 판정 | 핵심 발견 |
|---|---|---|
| **앱 안정성** | 🔴 위험 | `SupervisorJob` + 무처리 `launch` 조합 10곳. 예외 1건으로 **실행 중 프로세스 사망** |
| **CPU / idle** | 🔴 위험 | 무활동 상태에 **분당 12회 디스크 쓰기**, 대시보드 탭 1개에 **분당 264 HTTP 요청** |
| **토렌트 안정성** | 🟡 보통 | 게이트를 쥔 채 디스크 읽기(교차 대기), 무제한 락이 메인 스레드까지 블로킹 |
| **설정 적용** | 🔴 위험 | **터널 설정이 완전 no-op**, 앱 화면의 토렌트 설정 2종이 **저장만 되고 미적용** |
| **기능 동작** | 🟡 보통 | 웹 대시보드 속도 제한 토글의 방향 오염, 웹 인증 스위치가 열려 있으나 무인증 |

**가장 심각한 3건**

1. **`/api/storage/rename {"from":"","to":"x"}`** — LAN 클라이언트 요청 한 번으로 **보관함 전체가 `/sdcard/Download` 로 이동**. 이후 모든 StorageGuard 경로가 404.
2. **`WebDashboardHtml` + SSE** — 대시보드 탭을 열어 둔 상태로 폰을 잠그면 서버가 **무한히 깨어 있다** (브라우저는 `setInterval` 만 throttle 하고 `EventSource` 는 throttle 하지 않음).
3. **`/api/storage/upload`** — 크기 제한 없이 전체를 힙에 올려, LAN 클라이언트 하나가 앱을 OOM 으로 죽일 수 있음.

---

## 2. Phase 1 — 크래시 확정 12건

프로세스 사망 경로. `SupervisorJob` 는 **형제 상쇄만** 막고 예외 자체는 기본 핸들러로 가므로, 아래 각 항목은 독립적으로 앱을 종료시킨다.

| # | 위치 | 결함 | 도달 경로 |
|---|---|---|---|
| 1 | `WebhookManager.kt` | 스킴 없는 URL 이 `Request.Builder.url()` 에서 `IllegalArgumentException` — `try/catch` 밖이라 전파 | 사용자가 웹훅 URL 입력 → **다운로드 완료마다** 사망 |
| 2 | `WebhookManager.kt` | `"%02x".format(Byte)` 부호 확장 → `0xFF` 가 `ffffffffffffffff` 로 서명 | 모든 HMAC 서명이 무효 |
| 3 | `DownloadEngine.enqueue` | 이중 `URLDecoder.decode` 중 미가드 호출 | `POST /api/jobs {"url":"...?filename=a%.txt"}` (LAN) · 안드로이드 공유시트 |
| 4 | `DebugOverlayService` | `onCreate` 내 `startForeground` 무방어 (호출측 `runCatching` 은 다른 스레드 예외를 못 잡음) | 웹에서 오버레이 토글 |
| 5 | `DebugOverlayService` | `onDestroy` 에 `stopForeground` 누락 | 시스템이 `ForegroundServiceDidNotStopInTimeException` |
| 6 | `ScheduleJobService` | 예외 시 `jobFinished` 미호출 → `onStopJob=true` 로 **재스케줄 반복 = 크래시 루프** | 스케줄 조건 충족 시 |
| 7 | `RssFeedManager` | `catch(_: Exception)` 이 `StackOverflowError`(=`Error`) 통과 | 사용자가 깊게 중첩한 필터 정규식 1개 |
| 8 | `NetworkMonitor.register` | 콜백 등록 실패 시 `Service.onCreate` 에서 throw | uid당 콜백 상한 초과 |
| 9 | `RelayService` 가드 콜백 | 항목별 예외 격리 없음 — 무효 핸들에 JNI throw | 가드 스로틀 발동 중 |
| 10 | `RelayService` watchdog / `RelayServer.restart` | 예외가 튀면 **재시작을 담당하는 루프 자체가 죽음** | FFmpeg 세션 cancel 실패 시 |
| 11 | `TorrentEngine.stop` | `scope` 미취소 → 파괴된 세션에 JNI 호출 | 앱 종료 직후 |
| 12 | `DebugRoutes` | `?limit=-1` 이 `takeLast` 에서 throw (하한 없음) | 디버그 로그 요청 |

### 데이터 손상·보안 (동일 Phase)

| # | 위치 | 결함 | 결과 |
|---|---|---|---|
| 13 | `TorrentPersistence.save` | `@Synchronized` 없음 — 알림 스레드·폴러·이벤트루프가 공유 `.tmp` 에 교차 쓰기 | **토렌트 전체 손상** |
| 14 | `TrafficLedger.save` | `rename` **앞에** `delete()` — 두 문장 사이 크래시 시 | **트래픽 이력 전체 소실** |
| 15 | `VideoDownloadManager` | `publishToDownloads` 반환값 무시, 원본을 무조건 삭제 | 게시 실패 시 **영상 영구 소실** |
| 16 | `McpServer.fileList` | `StorageGuard` 미사용 | `"path":"../../.."` → **루트 전체 목록 노출** |
| 17 | `McpServer.fileRead` | `startsWith` 경로 구분자 누락 | `DroidRelay.bak` 같은 접두 동명 우회 |
| 18 | `StorageRoutes` rename/delete/move | `storageFile("")` 이 루트를 반환 | **보관함 전체 이름 변경** |
| 19 | `RelayServer` | `StatusPages` 없음 | 미처리 예외가 **"본문 없는 연결 끊김"** (9곳 `JSONException` 포함) |

> 19번은 개별 패치 대신 **전역 `StatusPages` 안전망**을 설치해 라우트 전체를 한 번에 보호했다.

---

## 3. Phase 2 — CPU / idle 비용 11건

### 유휴 상태 비용 (다운로드 0건 기준)

| 항목 | 이전 | 이후 |
|---|---|---|
| `peers.json` 디스크 쓰기 | **분당 12회** (하루 17,280회) | 값 변화 없으면 0회 |
| 대시보드 탭 1개 HTTP | **분당 264회** | 유휴 시 **0회** |
| `/api/guard/status` 시스템콜 | 분당 60 sysfs + 60 binder + 120 statfs | 15초 캐시 |
| `/api/info` binder IPC | 분당 60회 | 1회 (lazy) |
| watchdog | 분당 1회 왕복 HTTP | 프로세스 상태 확인 |
| 메인 스레드 웨이크업 (앱 foreground) | 분당 52회 | 0회 |
| `SimpleDateFormat` 할당 | `daily(400)` = **400개/호출** | 스레드당 1개 |

### 상세

| # | 위치 | 결함 | 조치 |
|---|---|---|---|
| 1 | `StatsSnapshots.recordPeers` | 5분 데번스 구간 **안에서도** `savePeers()` 호출 — 데번스가 디스크 쓰기를 막지 못했다 | 값 불변 시 조기 반환 + 저장을 데번스 통과 시점으로 |
| 2 | `StatsSnapshots.save*` | 5개 전부 절단적 `writeText` (복구 수단 없음) | 공통 원자 쓰기(tmp→rename) |
| 3 | `/api/events` | 무조건 1Hz tick → 클라이언트가 tick마다 4요청 | 상태 서명 기반 변경 감지 + 15초 beat |
| 4 | `WebDashboardHtml` SSE | `visibilitychange` 미처리 | 숨김 시 SSE·타이머 정리, 복귀 시 재연결 |
| 5 | `WebDashboardHtml` 재연결 | 고정 5초 재시도, pending 타이머 미정리 | 지수 백오프 + 지터, 고아 `EventSource` 제거 (서버 측 코루틴 누수 해소) |
| 6 | `/api/guard/status` | 데몬의 15초 캐시를 **우회하고 인라인 재구현** (dead code) | `GuardSensorCache` 공유 |
| 7 | `/api/info` | 매 요청 `getPackageInfo` binder 왕복 | `lazy` 캐시 |
| 8 | `RelayServer.isHealthy` | 루프백 HTTP 가 Ktor 파이프라인 + 핸들러 전체 실행 | 기동 상태 플래그. 진단용 `isHealthyHttp` 분리 |
| 9 | `MainActivity` `RootApp` | 1.5초 폴링으로 pending 4종 감시 (사용자 이벤트에서만 채워짐) | `MutableSharedFlow` 구독 |
| 10 | `DownloadsScreen` | 5초 폴링이 **메인 스레드에서 통신사 binder** 호출 | `networkTypeFlow(NetworkCallback)` |
| 11 | `TorrentRepository` | torrent N개 = 5초마다 N회 정렬 + N회 리컴포지션 | `JobsRepository`와 동일한 스로틀 이식 |

> **설계 근거**: 서버는 더 이상 "무언가 일어날 때만" 알린다. UI 는 재컴포지션 불필요.
> 대시보드와 디버그 페이지 모두 `visibilitychange` 가드를 두어, 폰을 잠그면 실제
> 네트워크 활동이 0 으로 떨어진다.

---

## 4. Phase 3 — 설정 적용 8건

**"저장되는데 적용되지 않는"** 설정. 대부분 저장소에는 값이 있고 UI 에도 표시되지만 실제 동작 경로가 연결되지 않았다.

| # | 설정 | 증상 | 원인 |
|---|---|---|---|
| 1 | **터널 사용** + 프로바이더 | **완전 no-op** — 켜도 아무 일도 안 일어남 | `TunnelManager.start()` 의 **호출이 코드베이스 전체에 없었음** |
| 2 | 최대 활성 torrent | 앱 화면에서 바꿔도 무반응 | `activeDownloads()` 가 `applySettings` 안에만 있고, 그 함수는 웹 라우트에서만 호출 |
| 3 | 시드 부재 대기 | 앱 화면에서 바꿔도 무반응 | `torrentMinSeedWaitSec` 갱신이 `applySettings` 안에만 존재 |
| 4 | 가드 임계치 4종 | **최대 5분** 지연 반영 | `GuardDaemon` 이 5분 TTL 캐시 — UI 는 최신값으로 계산해 **대시보드와 실제 동작이 불일치** |
| 5 | 웹 속도 제한 토글 | "다운로드 제한" 켜면 **업로드 제한까지 0(끔)으로 덮어씀** | 한쪽 키만 아닌 양쪽을 0과 함께 전송 |
| 6 | 웹 인증 스위치 | 스위치는 켜졌는데 **인증이 전혀 적용되지 않음** | 암호 미설정 상태로 켜면 `webPassword.isNotEmpty()` 게이트로 무인증 |
| 7 | 속도 4·토렌트 속도 3종 | 상한 없음 → **임의 값 저장 가능** | `ThrottleInterceptor` 토큰 버킷의 `limit*2` 가 Long 오버플로 → 음수 고정 → **제한이 조용히 무력화** |
| 8 | 기본값 복원 | 앱 리셋 후 일부 값이 남음 | 앱/웹이 별개 구현이고 키 집합이 어긋남 |

**구조적 근본 원인**: 설정 적용의 **단일 choke point 가 없었습니다**. `RelayApp.applySettings()` 가 정의돼 있었으나 **호출자가 0건**이었고, 각 라우트가 엔진을 개별 적용하다 보니 앱/웹이 갈라졌습니다. `SettingsResetter.applyAll()` 로 통합해 재발을 막았습니다.

---

## 5. Phase 4 — 토렌트 JNI · 영속성 14건

### libtorrent 세션 게이트 (`sessionGate`)

JNI 는 스레드 안전하지 않아 모든 세션 접근을 단일 락으로 직렬화하고 있다 (T-930, 올바른 설계). 문제는 **락 보유 경로**였다.

```
alert 스레드: withGateAlert → registerMapping → applyExtraTrackers
                                                      └─ settings.firstBlocking()  ← runBlocking (디스크 읽기)
   ★ 게이트를 쥔 채 alert 스레드가 디스크 I/O 대기
     → 그 동안 5초 폴러 · 모든 /api/torrents · UI 스레드 전부 대기
```

| # | 결함 | 조치 |
|---|---|---|
| 1 | 게이트 보유 중 `runBlocking` 디스크 읽기 | Flow 스냅샷(`latestTrackerSync`)으로 대체 |
| 2 | `withGate` 무한 대기 — UI `onClick`(메인 스레드)에서 호출 | `withGateRead(2초)` 도입, UI 는 전부 IO 로 이관 |
| 3 | `reorder` 가 게이트를 쥔 채 전체 `queuePosition()` 순회 | 동일 |
| 4 | `/api/torrents` 가 토렌트마다 게이트 2회 × JNI 2회 (1Hz 폴링) | `pieceInfo` 는 읽기 게이트로 |

### 그 외

| # | 결함 | 조치 |
|---|---|---|
| 5 | MCP `runBlocking` 이 이벤트루프를 **최대 40초** 블로킹 | `suspend` 로 전환 |
| 6 | 업로드가 크기 제한 없이 전체를 힙에 (OOM) | 64KB 스트리밍 + 사전 거절 |
| 7 | `RssFeedRepository.save` 교차 쓰기 + `delete` 선행 | `@Synchronized` + 원자 rename |
| 8 | 공유 토큰이 **예측 가능한 PRNG** — 토큰이 곧 권한 | `SecureRandom` |
| 9 | `ensureLoaded` 실패해도 `loaded=true` | 성공 후에만 설정 |
| 10 | 스케줄러 `stop`→`start` 후 **취소된 스코프**에 launch → 예약 조용히 소멸 | `start()` 에서 스코프 재생성 |
| 11 | `BootReceiver` `finish()` 미호출 시 시스템 ANR | `withTimeout` + `invokeOnCompletion` 백stop |
| 12 | `CronParser` 가 `matches` **10,080회** 마다 Regex 컴파일 — `onValueChange` 에서 호출 | 필드 분리 루프 밖으로 이동 |

---

## 6. 테스트

| | 이전 | 이후 |
|---|---|---|
| 단위 테스트 | 184 | **204** (신규 20) |

- `SettingsClampTest` (6) — 상한 적용 / Long 오버플로 방지 / 범위 일치
- `SseSignatureTest` (7) — **유휴 시 서명 불변**(tick 정지의 근거), 상태 전이 즉시 감지, 속도 제외
- `CronParserPerfTest` (7) — 절대 매칭 불가 표현식, 필드 수 조기 거부, 매칭 정확성, 무상태성

> ⚠️ **기존 검증 절차의 문제 발견**: `ktlintCheck` 가 **`.kts` 스크립트만 검사**하고
> Kotlin 소스셋은 검사하지 않는다 (실행 태스크가 `runKtlintCheckOverKotlinScripts` 뿐).
> 즉 이전 세션 로그의 "ktlintCheck GREEN" 은 19,546행의 Kotlin 코드를 검증한 적이 없었다.
> 활성화 시 기존 위반이 대량 surfaced 하므로 별도 과제로 분리했다.

---

## 6-1. 실기기 검증에서 추가 발견 (수정 완료)

`f554000` — 자동 분석이 아니라 **설치 후 로그 관찰로 발견**한 항목.

| 증상 | 원인 | 조치 |
|---|---|---|
| 토렌트 3건 전부 QUEUED(무활동)인데 `[TorrentPersist] 저장 완료 3건` 이 5~10초 간격 반복 | `persistDebounced()` 의 "변경 없으면 스킵" 가드(T-845)가 **처음부터 무효** | `persistedSignature()` 도입 |

원인은 비교 기준이었다. `lastSavedSnapshot != TorrentRepository.all()` 은
`TorrentJob`(data class) 전체를 비교하는데, 그중 `seeds`·`peers`·`downloadSpeed`·
`uploadSpeed`·`torrentFileBytes` 는 **`TorrentPersistence.save` 에 기록되지 않는
라이브 필드**이며 5초 폴링마다 갱신된다. 따라서 "내용이 같다"는 조건이 영영 참이 되지
못했고, **바이트가 완전히 동일한 파일을 계속 다시 썼다.**

`persistedSignature()` 는 저장기가 실제로 쓰는 필드만으로 서명을 만든다.
테스트 9건 추가 (204 → 213).

> 이 항목은 "타이머가 존재한다는 이유만으로 반복"되는 CPU 문제와 **같은 패턴**이었다.
> 비교/캐시 대상이 실제 저장 대상과 어긋난 경우다.

---

## 7. 미수정 (의도적 판단)

| 항목 | 이유 |
|---|---|
| 웹훅 UI 부재 | 기능 추가. API 는 완전 동작하나 UI 없음 |
| `torrentListenPort` vs 서버 포트 충돌 미검사 | 충돌 시 토렌트 세션이 조용히 기동 실패 — 로그 개선 필요 |
| `torrentSavePath` 무검증 | 웹에서 임의 경로 지정 가능 — 정규화 필요 |
| `guardEnabled` 등 4종의 부분 적용 | Phase 3 에서 지연 해소, 실시간은 유지 |
| `OkHttpClient` 요청당 신규 생성 (M17) | 스레드 누수 여지 있으나 동작 영향 확인 필요 |
| `firstBlocking()` 의 `runBlocking` (H1) | 25개 라우트 호출부 — suspend 전환은 대규모 변경이라 별도 과제 |
| `StreamDetector` chunked 응답 상한 (M6) | 크기 상한 부재 — 개선 권장 |

---

## 8. 실기기 검증 권장

자동화로는 잡히지 않는 항목:

- [ ] 대시보드 탭을 열어 둔 채 **화면 잠금** → 서버 로그에서 요청이 멈추는지
- [ ] 가드 임계치 lowered → **즉시** 반영되는지 (이전 5분 지연)
- [ ] 앱 설정에서 "최대 활성 torrent" 변경 → `활성 한도 적용` 로그
- [ ] 터널 ON → `터널 시작` 로그 또는 사유
- [ ] 웹에서 "다운로드 제한" ON → 업로드 제한이 **그대로 유지**되는지
- [ ] 웹 인증 암호 미설정 상태로 스위치 ON → 거부 메시지
- [ ] 대용량 업로드 → 스트리밍 로그 + 메모리 사용량 안정
- [ ] 토렌트 목록에서 pause 를 빠르게 연타 → UI 멈춤 없는지

### 2026-09-26 실기기 검증 결과 (설치 후 수행)

무선 adb (SM-S901N) + `adb forward tcp:3100 tcp:3000` 로 API 직접 호출.

| 항목 | 결과 |
|---|---|
| 앱 기동·크래시 | ✅ 없음. 터널/가드/토렌트 초기화 로그 정상 |
| 루트 rename 차단 (P1 #18) | ✅ `{"error":"이름 없음 또는 루트 경로 금지"}` + 루트 보존 확인 |
| MCP `file_list` 경로 탈출 (P1 #16) | ✅ `경로 탈출 차단` / 정상 경로는 정상 응답 |
| StatusPages 안전망 (P1 #19) | ✅ `500 {"error":"server_error","detail":"JSONException"}` |
| 속도 제한 단독 토글 (P3 #5) | ✅ dl=5MB/s 설정 시 ul=3MB/s **유지** (이전엔 0으로 덮어써짐) |
| 가드 임계치 즉시 반영 (P3 #4) | ✅ thermalLimit=52 즉시 반영 (이전 최대 5분 지연) |
| 값 상한 (P3 #7) | ✅ `Long.MAX_VALUE` → 10GiB/s 클램프 |
| `peers.json` 유휴 시 쓰기 (P2 #1) | ✅ 70초 관찰 중 쓰기 0회 (이전 분당 12회) |
| **토렌트 영속화 가드 (신규 발견)** | ✅ 저장 11회 중 10회가 실제 전이에 대응 |

---

## 9. 결론

- **아키텍처**: libtorrent 게이트 직렬화, 영속화 원자 쓰기, DataStore 단일 진실 — 설계 자체는 건전.
- **실패 지점**: 예외 처리의 부재와 "호출 지점이 없는 함수". `TunnelManager.start()`, `RelayApp.applySettings()`, `GuardDaemon.checkNow()`, `SpeedScheduleManager.checkNow()` — **정의만 있고 호출이 없는 함수가 4개** 있었고, 이것이 대부분의 "설정이 안 먹는다" 증상의 근본이었다.
- **CPU 문제의 공통점**: 상태 변경 여부를 무시한 채 **타이머가 존재한다는 이유만으로** 주기적 작업이 반복되고 있었다. 변경 기반 푸시(SSE 서명)와 푸시 기반 수신(NetworkCallback, SharedFlow)로 전환했다.
- **다음 세션 권장**: (1) `firstBlocking()` → `suspend` 전환, (2) ktlint 소스셋 활성화 + 기존 위반 정리, (3) 웹훅 UI 추가
