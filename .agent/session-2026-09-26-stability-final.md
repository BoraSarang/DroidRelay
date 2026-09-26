# 세션 로그 — 2026-09-26 (android, 종합 안정성 4단계 + 실기기 검증)

## 1. 목표
CPU 사용 안정성 · 앱 안정성 · 기능 동작 상태 · 토렌트 안정성 · 설정 적용 상태 5축 점검 후 리팩토링

## 2. 점검 방법
- 3개 축 병렬 정적 분석 서브에이전트 (CPU/전력 · 크래시/안정성 · 설정 적용)
- **전 항목 원본 코드 1:1 대조 검증** 후에만 수정 (미검증 항목 반영 없음)
- Kotlin 79파일 / 19,546행

## 3. 수정 46건

### Phase 1 — 크래시 확정 12건 (`77f865c` → main `6106d09`)
프로세스 사망 경로. `SupervisorJob` 는 형제 상쇄만 막고 예외는 기본 핸들러로 전달.

- `WebhookManager` URL 파싱 / HMAC 부호확장
- `DownloadEngine.enqueue` 이중 `URLDecoder`
- `DebugOverlayService` `startForeground`·`stopForeground`
- `ScheduleJobService` `jobFinished` 크래시 루프
- `RssFeedManager` `StackOverflowError` 통과 / `NetworkMonitor.register`
- 가드 콜백 격리 / watchdog 재시작 방어 / `TorrentEngine.stop` 스코프 취소
- `DebugRoutes` limit 하한
- **데이터 손상**: `TorrentPersistence` @Synchronized, `TrafficLedger` delete 선행 제거,
  `VideoDownloadManager` 게시 실패 시 원본 삭제
- **보안**: `McpServer.file_list` 경로 탈출, `StorageGuard.storageChild`(루트 거부)
- **StatusPages 전역 안전망** (`ktor-server-status-pages` 신규)

### Phase 2 — CPU/idle 11건 (`f5b67ab`)
유휴 시 디스크 쓰기 분당 12회 → 0회, 대시보드 분당 264요청 → 0회, 메인 스레드 분당 52회 → 0회.

- `recordPeers` 데번스가 디스크 쓰기를 막지 못하던 문제
- SSE 무조건 1Hz tick → 상태 서명 기반 변경 감지 + 15초 beat
- `visibilitychange`/`pagehide` 가드, SSE 지수 백오프 + 고아 연결 제거
- `GuardSensorCache` 공유, `/api/info` lazy, watchdog 인프로세스
- `MainActivity` SharedFlow / `DownloadsScreen` NetworkCallback
- `TorrentRepository` 스로틀, `TrafficLedger` ThreadLocal

### Phase 3 — 설정 적용 8건 (`52fe598`)
**근본 원인: 정의만 있고 호출이 없는 함수가 4개** —
`TunnelManager.start()`, `RelayApp.applySettings()`, `GuardDaemon.checkNow()`, `SpeedScheduleManager.checkNow()`

- 터널 설정 완전 no-op → Flow 구독 추가
- 앱 화면 토렌트 설정 2종 미적용 → 핫 적용 경로 통일
- 가드 5분 지연 → Flow 푸시
- 웹 속도 제한 토글 방향 오염 / 웹 인증 스위치 무인증 / 값 상한(Long 오버플로)
- `SettingsResetter` 단일 구현

### Phase 4 — 토렌트 JNI·영속 14건 (`9b401a4`)
- 게이트 보유 중 `runBlocking` → Flow 스냅샷
- `withGateRead(2초)` + UI JNI 를 IO 로 이관 (ANR 방지)
- MCP suspend 전환, 업로드 OOM 스트리밍화
- `SecureRandom` 토큰, 스케줄러 취소 스코프, `BootReceiver` ANR, `CronParser` Regex 10,080회 → 1회

### 추가 발견 — 기기 검증 (`16273a1`)
`persistDebounced()` 의 "변경 없으면 스킵" 가드(T-845)가 **처음부터 무효**.
`lastSavedSnapshot != TorrentRepository.all()` 이 저장되지 않는 라이브 필드까지 비교 →
**바이트가 동일한 파일을 유휴 상태에서도 10초마다 다시 썼다.**
`persistedSignature()` 로 교체.

## 4. 실기기 검증 (SM-S901N, 무선 adb) — 13/13 통과

| # | 항목 | 결과 |
|---|---|---|
| 1 | 대시보드 화면잠금 → 요청 0 | SSE 40초 `tick 1 + beat 2` (이전 tick 40) |
| 2 | visibilitychange 정리 | 배포 JS 실행 20/20 |
| 3 | SSE 지수 백오프 | 1.2→2.1→4.2→8.2→16.0s |
| 4 | 루트 rename 차단 | 오류 + 루트 보존 |
| 5 | MCP 경로 탈출 차단 | 차단 / 정상 정상 |
| 6 | StatusPages 안전망 | 500 JSON 응답 |
| 7 | 속도 제한 단독 토글 | dl 설정 시 ul 유지 |
| 8 | 가드 즉시 반영 | thermalLimit=52 즉시 |
| 9 | 값 상한 | Long.MAX_VALUE→10GiB/s, 999→10 |
| 10 | peers.json 유휴 시 | 70초 0회 (이전 분당 12회) |
| 11 | 활성 한도 즉시 적용 | maxActive 2→5 로그 |
| 12 | 업로드 메모리 | 40MB 시 PSS +11MB |
| 13 | 영속화 가드 | 저장 11회 중 10회가 실제 전이 대응 |

**추가 회귀 테스트**: `DashboardRealtimeContractTest` 9건 (배포 HTML 이 가시성 계약 포함하는지 정적 검증)
+ `scripts/verify_dashboard_realtime.js` (행동 검증 20/20)

## 5. 테스트

| | 이전 | 이후 |
|---|---|---|
| 단위 테스트 | 184 | **222** (신규 38) |

## 6. 발견 — 기존 검증 절차의 결함

**`ktlintCheck` 가 `.kts` 스크립트만 검사**하고 Kotlin 소스셋은 검사하지 않는다
(실행 태스크가 `runKtlintCheckOverKotlinScripts` 뿐).
이전 세션 로그의 "ktlintCheck GREEN" 은 19,546행 Kotlin 코드를 검증한 적이 없었다.
활성화 시 기존 위반이 대량 surfaced 하므로 **임의로 켜지 않고 Known Issues 에 기록**했다.

## 7. PR

| PR | 내용 |
|---|---|
| #10 | 토렌트 결함 #1~#9 + 보관함 정렬 |
| #11 | 종합 안정성 4단계 (46건) |
| #12 | 실기기 검증 13/13 + 회귀 테스트 |

모두 squash merge. `main` = `5b1aaa9`.

## 8. 미검증 (사용자 데이터 간섭 회피)

| 항목 | 사유 |
|---|---|
| 웹 인증 스위치 거부 | API 엔드포인트 없음(**앱 UI 전용**). 위험 상태 미존재는 `auth=false` 로그로 확인 |
| 토렌트 pause 연타 → UI 멈춤 없음 | **사용자 토렌트 3건이 실제 다운로드 중**(prog=0.64, 2.8GB)이라 일시정지하지 않음 |

## 9. 신규 발견 (Known Issues)

`ktor-server-core` 기본 **multipart 파트 제한 50MB** 로 600MB 업로드 실패.
기존에도 동일 실패했으나 오류가 그대로 노출된다. 대용량은 `raw-upload`(스트리밍) 경로 사용.

## 10. 다음 세션

- [ ] `firstBlocking()` → `suspend` 전환 (Ktor 라우트 25곳, 대규모)
- [ ] ktlint 소스셋 활성화 + 기존 위반 정리
- [ ] multipart 50MB 제한 상향 또는 대용량 `raw-upload` 유도
- [ ] 웹훅 UI 추가 (API 는 동작하나 UI 없음)
- [ ] `torrentListenPort` vs 서버 포트 충돌 검사
- [ ] `torrentSavePath` 경로 검증
- [ ] `OkHttpClient` 요청당 신규 생성 정리
- [ ] 미검증 2건 (웹 인증 스위치 / 토렌트 pause 연타) — 사용자 승인 시
