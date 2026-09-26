# 세션 로그 — 2026-09-26 (android, 종합 안정성 점검 + 4단계 리팩토링)

## 1. 목표
CPU 사용 안정성 · 앱 안정성 · 기능 동작 상태 · 토렌트 안정성 · 설정 적용 상태 5축 점검 후 리팩토링

## 2. 수행

### 점검 방법
- 3개 축 병렬 정적 분석 서브에이전트 (CPU/전력 · 크래시/안정성 · 설정 적용)
- **전 항목 원본 코드 1:1 대조 검증** 후에만 수정 (미검증 항목 반영 없음)
- Kotlin 79파일 / 19,546행 대상

### 브랜치
- `fix/torrent-audit-2026-09-26` — 이전 세션 미커밋 작업(토렌트 결함 #1~#9) 커밋
- `refactor/stability-cpu-settings` — 본 리팩토링 4개 커밋

### Phase 1 — 크래시 확정 12건 (`6106d09`)
- 프로세스 사망 12건: WebhookManager URL 파싱/HMAC 부호확장, DownloadEngine 이중 URLDecoder,
  DebugOverlayService startForeground·stopForeground, ScheduleJobService jobFinished 루프,
  RssFeedManager Error 통과, NetworkMonitor.register, 가드 콜백 격리, watchdog 재시작 방어,
  TorrentEngine.stop scope 취소, DebugRoutes limit 하한
- 데이터 손상 4건: TorrentPersistence @Synchronized, TrafficLedger delete 선행 제거,
  VideoDownloadManager 게시 실패 시 원본 삭제, McpServer 경로 탈출, StorageGuard.storageChild(루트 거부)
- **StatusPages 전역 안전망** 추가 (ktor-server-status-pages 의존성 신규) — 라우트 미처리 예외 9곳을
  개별 패치 대신 한 번에 해결

### Phase 2 — CPU/idle 11건 (`63fb1b8`)
- recordPeers 무조건 디스크 쓰기(분당 12회) 제거, snapshot 5종 원자 쓰기 통일
- SSE 무조건 1Hz tick → 상태 서명 기반 변경 감지 + 15초 beat
- 클라이언트 `visibilitychange`/`pagehide` 가드, SSE 지수 백오프 + 고아 EventSource 제거
- GuardSensorCache 공유(데몬 dead cache), /api/info lazy 캐시, watchdog 인프로세스 판정
- MainActivity MutableSharedFlow, DownloadsScreen networkTypeFlow(NetworkCallback)
- TorrentRepository 진행 틱 스로틀(JobsRepository 이식), TrafficLedger ThreadLocal 포매터

### Phase 3 — 설정 적용 8건 (`d652286`)
- **터널 설정 완전 no-op** → RelayService 에 Flow 구독 추가
- 앱 화면 토렌트 설정 2종 미적용 → init Flow 구독을 유일한 핫 적용 경로로 정리
- 가드 5분 지연 → Flow 푸시 구독
- 웹 속도 제한 토글 방향 오염, 웹 인증 스위치 무인증 상태 허용, 값 상한 부재(Long 오버플로)
- **SettingsResetter** 신규 — 앱/웹 키 집합 불일치 해소 + 단일 팬아웃

### Phase 4 — 토렌트 JNI·영속 14건 (`4a5630c`)
- 게이트 보유 중 runBlocking(alert 스레드 교차 대기) → Flow 스냅샷
- withGateRead(2초) 도입 + TorrentScreen UI JNI 를 IO 로 이관 (ANR 방지)
- MCP suspend 전환(40초 이벤트루프 블로킹), 업로드 OOM 스트리밍화
- RSS/공유 저장소 @Synchronized + 원자 쓰기, 공유 토큰 SecureRandom
- 스케줄러 stop→start 취소 스코프, BootReceiver ANR 방지, CronParser Regex 10,080회 → 1회

## 3. 검증
- ktlintCheck GREEN · **testDebugUnitTest 204건 0 failures** (신규 20건: SettingsClamp 6, SseSignature 7, CronParserPerf 7)
- assembleDebug 성공 (58.9MB APK)
- 대시보드/디버그 JS `node --check` 통과
- 소스 내 중국어/깨진 문자 전수 검사 후 정리

## 4. 발견 — 기존 검증 절차의 문제
- **`ktlintCheck` 가 `.kts` 스크립트만 검사**하고 Kotlin 소스셋은 검사하지 않는다
  (실행 태스크가 `runKtlintCheckOverKotlinScripts` 뿐)
- 즉 이전 세션 로그의 "ktlintCheck GREEN" 은 19,546행 Kotlin 코드를 검증한 적이 없었음
- 활성화 시 기존 위반 대량 surfaced → 별도 과제로 분리 (이번 세션에서 임의 활성화하지 않음)

## 5. 산출물
- `docs/STABILITY_AUDIT_2026-09-26.md` — 5축 점검 리포트 (45건 수정 상세 + 미수정 사유 + 실기기 검증 체크리스트)

## 6. 다음 세션
- [ ] 실기기 검증 8항목 (리포트 §8) — 대시보드 화면잠금 시 요청 정지, 가드 즉시 반영, 터널 기동, 업로드 메모리
- [ ] `firstBlocking()` → `suspend` 전환 (Ktor 라우트 25곳 — 대규모 변경)
- [ ] ktlint 소스셋 활성화 + 기존 위반 정리
- [ ] 웹훅 UI 추가 (API 는 동작하나 UI 없음)
- [ ] `torrentListenPort` vs 서버 포트 충돌 검사 추가 (현재 충돌 시 토렌트 세션이 조용히 기동 실패)
- [ ] `torrentSavePath` 경로 검증 (웹에서 임의 경로 지정 가능)
- [ ] `OkHttpClient` 요청당 신규 생성 정리 (스레드 누수 여지)
