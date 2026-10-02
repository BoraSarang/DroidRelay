# PLAN v0.51 — 크래시 수정 · 맥 설정 창 반응 · UDP 발견 신호 (T-1088 ~ T-1090)

> 작성: 2026-10-02 · 플랫폼: android + macos
> 착수 근거: 사용자 보고 3건 (기기 크래시 확인 / 맥 설정 "다시 찾기" 무반응 / 안드로이드 "나 여기있소" 부재)

---

## 0. 실측으로 먼저 확정한 것 (추측 금지)

| 항목 | 측정 방법 | 결과 |
|---|---|---|
| 기기 앱 상태 | `adb shell ps -A` | **실행 중** (PID 23824) · `RelayService` 정상 |
| 서버 응답 | `curl /api/info` | `version 0.50.0` · 정상 |
| 크래시 이력 | `adb logcat -b crash` | **1건** — 2026-09-30 00:11:33 |
| 크래시 원인 | 스택 + 소스 대조 | `BootReceiver` **이중 `finish()`** |
| 맥 탐색 동작 | `--diagnose` 실행 | 게이트웨이 **40ms** · v0.50.0 · **탐색은 정상** |
| 맥 설정 창 표시 | `grep connectionLine\|lastResult\|phase` | **0건** — 결과를 보여줄 코드가 없다 |
| 안드로이드 announce | `grep -il "UDP\|Datagram\|multicast\|Bonjour"` | **없음** |

**중요**: 맥 탐색은 40ms 에 성공한다. 버튼이 실패한 게 아니라 **결과를 말하지 않은 것**이다.

---

## T-1088 — `BootReceiver` 이중 `finish()` 크래시

### 증상
```
FATAL EXCEPTION: queued-work-looper
java.lang.IllegalStateException: Broadcast already finished
    at BroadcastReceiver$PendingResult.sendFinished(BroadcastReceiver.java:313)
```

### 원인 — `BootReceiver.kt` 37행 + 41행

```kotlin
finally { runCatching { pendingResult.finish() } }              // 37행
job.invokeOnCompletion { runCatching { pendingResult.finish() } } // 41행
```

`goAsync()` 의 `PendingResult` 는 **한 번만** `finish()` 가능하다.
41행은 "백업"으로 붙었으나 **`invokeOnCompletion` 은 `finally` 이후에 발화**하므로
**정상 경로에서 반드시 이중 호출**된다. 백업이 아니라 중복 호출이다.

주석의 "finish() 는 반드시 호출" 의도는 37행 `finally` **하나로 이미 충족**된다.

### 수정
41행 `invokeOnCompletion` **삭제**. `finally` 만 남긴다.

### 왜 `runCatching` 으로 안 잡히나 (다음에 같은 실수를 안 하게)
`sendFinished` 는 **예외를 던지는 게 아니라** 이미 종료된 상태에서 다시 종료시키려 한다.
`runCatching` 은 이 호출을 감싸지만 크래시 버퍼에는 `queued-work-looper` 스레드에서
날아온 기록이 남는다. → **이중 호출 자체를 없애야 한다.**

### 검증
- [ ] `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.borasarang.droidrelay` → 크래시 로그 0건 증가
- [ ] 앱 프로세스 유지 확인 (`ps -A`)
- [ ] `bootAutoStart` ON 상태에서 서버 기동 확인

---

## T-1089 — 맥 설정 창 "다시 찾기" 무반응

### 증상 (사용자 그대로)
> "설정 -> 다시 찾기 했을때 찾았을때의 반응이 없네 설정에서 주소 채워줘야 하고 찾았습니다 또는 실패 했습니다. 등이 있어야 하는데"

### 원인 2가지 (둘 다 코드 부재)

**① 상태 표시가 아예 없다**
`SettingsWindow.swift` 의 `grep -c "connectionLine\|lastResult\|phase"` = **0**.
탐색 상태 표시(`DiscoveryBadge`, M-22)는 **팝오버에만** 있다.
설정 창은 별도 `NSWindow`(M-18)이므로 그 줄을 공유하지 않는다.
→ 찾았어도, 실패해도 **설정 창은 변화가 없다.**

**② 주소 자동 채움이 없다**
`SettingsWindow.swift:135` `address` 는 `@State` 이고
154~158행 `onAppear` 에서만 채워진다.
`connect()` 는 `model.stored` 를 바꾸지만 `@State` 를 갱신하지 않는다.
→ 재탐색에 성공해도 **칸은 옛 값(또는 빈 값)** 이 남는다.

### 수정
1. **상태 한 줄** 추가 — `model.connectionLine` **재사용** (복사 금지).
   `DiscoveryBadge` 가 전략 4개 × 버전 유무 × 수동 입력을 이미 다 처리한다.
   규칙을 두 벌 쓰면 한쪽만 고쳐지고 **화면과 진단이 다른 말을 한다** (M-27 교훈).
2. **주소 자동 채움** — `phase` 변경 시 `model.storedAddress` 를 `address` 에 반영.
   성공했을 때만 덮어쓴다(실패 시 사용자가 직접 넣은 값을 보존).
3. 탐색 중에는 버튼 비활성 + "탐색 중" 표시 (중복 실행 방지).

### 검증
- [ ] 새 Core 순수 함수 테스트 (성공 시 채움 / 실패 시 보존 / 탐색 중 무효)
- [ ] **계산값만 이 아니라 실제 화면 높이·텍스트를 재는 진단** (`--settings-check` 확장 — M-21 교훈)
- [ ] 실기기: 설정 → 다시 찾기 → 상태 줄과 주소 칸 동시 갱신 확인
- [ ] 폰 서버 중지 후 다시 찾기 → "연결 실패" 문구 확인

---

## T-1090 — 안드로이드 "나 여기있소" UDP 발견 신호

### 현재 (사용자 요구가 성립하는 이유)
발견은 전적으로 수동 스캔이다.

| 전략 | 방식 | 실측 |
|---|---|---|
| `cached` | 저장된 주소 프로브 | 즉시 |
| `gateway` | 게이트웨이 포트 두드림 | **40ms** |
| `subnetScan` | `/24` 254호스트 전수 두드림 | 0.11초 |

핫스팟이면 폰이 곧 게이트웨이라 `gateway` 하나로 끝난다(40ms).
**같은 공유기(Wi-Fi)에서는 스캔에 걸린다.** announce 는 그 구간을 즉시 만든다.

### 설계 — UDP 브로드캐스트 주기 발신 + 수동 "알리기"

**왜 Bonumber/NSNetService 가 아닌가** — 표준 Bonjour 는 Android 에 `NsdManager` +
맥에 `NSNetService` 양쪽 구현이 필요하다. 이 앱은 **자신의 서버를 이미 알고 있고**
상대도 자기 앱 하나뿐이다. 두 기가 같은 브로드캐스트 도메인에 있다는 사실만 알면 된다.
→ **UDP 브로드캐스트 1회로 충분**하고 실패해도 기존 스캔으로 넘어간다.

**포맷** — 사람이 읽히고 파싱이 안 깨지게 줄 단위 JSON.
```
{"app":"DroidRelay","v":"0.50.0","port":3000,"httpsPort":8443,"https":false,"ip":"10.38.120.211"}
```

**주기 발신** — 서버가 뜬 동안 `N` 초마다. 핫스팟 IP 는 바뀔 수 있으므로 매번 `lanAddress()` 조회.

**수동 "알리기"** — 그래프 정적일 때 즉시 1회 방송. (사용자 확정)

**안전 설계 (이게 중요)**
- **주기 발신이 절대 안 되더라도** 기존 3단계 탐색은 그대로 동작한다. **회귀 위험 0.**
- `DatagramSocket` 은 `close()` 해야 리소스를 놓는다. 발신만 하므로 **바인딩 없이** 쓴다.
- 핫스�트가 꺼지면 발신이 의미없어진다 → `lanAddress()` 가 null 이면 **건너뛴다**(문제가 아니라 상태).
- 백그라운드 제한: 이미 `RelayService`(FGS) 안에서 도므로 별도 워커가 필요 없다.

**수신(맥)** — `ServerDiscovery` 의 **전략 0** 으로 추가. 가장 빠르고 확실하므로 먼저 쓴다.
리스너는 앱 수명 동안 1개만(중복 등록 금지), 패킷 오면 캐시 주소 갱신.

### 검증
- [ ] 패킷 캡처로 포맷·주기 실측 (`tcpdump` 또는 맥 리스너 로그)
- [ ] **、服务 있는 Mac 이 40ms → 즉시** 붙는지 계측 (스캔 미경유 확인)
- [ ] **핑승을 서버 내린 상태에서 주기 발신이 크래시/ANR 를 만들지 않는지** 확인
- [ ] 기존 스캔 전략 **회귀 없음** (announce 안 들릴 때 게이트웨이 경로로 붙는 것)
- [ ] 새 순수 함수 테스트 (파싱·버전 불일치·포맷 오류)

---

## 작업 순서 (의존성)

```
T-1088 (S, 독립·즉시)      ← 크래시. 다른 작업에 영향 없음
   ↓
T-1089 (M, 독립)          ← 맥 설정 창
   ↓
T-1090 (L, 신규 기능)     ← UDP — 가장 큼. 마지막
```

각각 **별도 커밋**. announce 실패가 T-1088·T-1089 를 되돌리면 안 된다.

---

## 위험과 대응

| 위험 | 대응 |
|---|---|
| announce 이 권한/제한에 막힘 | 주기 발신 `runCatching` 격리 · 실패해도 기존 탐색 유지 |
| 핫스팟 IP 변경 후 오래된 주소广播 | 매 주기 `lanAddress()` 재조회 · 포맷에 `ip` 실어 보냄 |
| 이중 `finish()` 수정 후 부팅 자동시작 실패 | `finally` 만으로 8초 `withTimeout` 경로 유지됨을 테스트로 고정 |
| Mac 설정 창이 좁아 새 줄이 잘림 | `--settings-check` 로 실제 뷰 높이 실측 (M-21 교훈) |

---

## 하지 않는 것 (범위 밖)

- mDNS/Bonjour 정식 등록 — 두 기 전용이라 과하다
- 웹 대시보드에도 announce 노출 — 요청 범위 밖
- announce 로 **주소 입력** 자동화 — 붙는 것까지만. 자동 저장·자동 전환은 별도 결정
