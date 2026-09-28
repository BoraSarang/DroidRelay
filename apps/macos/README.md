# DroidRelay macOS (메뉴바 클라이언트)

폰의 DroidRelay 서버에 붙어 **진행률과 제어를 메뉴바에서** 하는 네이티브 클라이언트.
Xcode 프로젝트 없이 SwiftPM 로 빌드한다 (이 저장저���는 Gradle 기반이라 일관성 유지).

## 빌드 · 실행

```bash
cd apps/macos
swift build
BIN=$(swift build --show-bin-path)/DroidRelayMac

"$BIN"                 # 메뉴바 앱 실행 (Dock 아이콘 없음)
"$BIN" --diagnose      # 진단 — 서버 찾기·연결·SSE 생존을 확인하고 종료
swift test             # 순수 로직 39건
```

### 빌드 스크립트 (권장)

```bash
./build_and_run.sh debug macos     # swift build + .app 번들 → /Applications 설치
./build_and_run.sh run macos       # 실행 (프로세스 생존 확인까지)
./build_and_run.sh diagnose macos  # 서버 탐색 · SSE 생존
./build_and_run.sh test macos      # swift test
./build_and_run.sh uninstall macos # 제거
```

`.app` **번들**으로 설치해야 한다. `Info.plist` 의 `LSUIElement` 가 없으면
Dock 아이콘이 뜨고(메뉴바 앱인데) macOS 가 경고를 띄운다.

`--diagnose` 가 중요하다. 메뉴바 앱은 화면이 없으므로 실패 원인을 볼 수단이 이것뿐이다.
M1 개발 중 **실제 버그 5건 중 3건이 이 경로에서만 드러났다.**

## 구조

| 타깃 | 내용 | 테스트 |
|---|---|---|
| `DroidRelayCore` | `ServerDiscovery` · `RelayClient` · `EventStream` · `Subnet`/`IPv4` | O (순수 로직) |
| `DroidRelayMac` | `AppModel` · `PopoverView` · `StatusItemController` | — (AppKit 위에서만 의미가 있음) |

Core/UI 를 뺀 이유는 **탐색 계산과 SSE 파서를 OS 없이 검증**하기 위해서다.
아래 버그 5건 중 4건이 그 결과로 잡혔다.

## 서버 탐색 (계층 전략)

순서대로 시도하고, 먼저 성공한 전략을 화면에 표시한다.

| 순위 | 전략 | 실측 | 성립 조건 |
|---|---|---|---|
| 1 | `cached` | 0ms | 이전에 찾은 주소 (`UserDefaults`) |
| 2 | `gateway` | 35ms | **폰이 핫스팟** — 게이트웨이가 곧 폰 |
| 3 | `subnetScan` | 0.11초 | 폰과 Mac 이 같은 공유기 아래 |
| 4 | `manual` | — | 위가 전부 실패 → 팝오버 설정에서 직접 입력 |

근거: `docs/mockups/probe_discovery.py`. 순서와 무관하게 0.15초면 끝나므로
mDNS 같은 무거운 탐색은 필요 없다.

`/16` 에 붙어 있는 경우 로컬 IP 가 속한 `/24` **만** 스캔한다 (254호스트).
`/16` 전체 65,534개를 두드리면 가정 대역 오탐과 시간 낭비다.

## M1 개발 중 발견한 실제 버그 5건

모두 "해보면 깨지는" 종류라 기록해 둔다.

1. **`/24` 캡이 반대로 동작** — `min(prefix, 24)` 는 접두사를 *작게* 만들어 서브넷이
   *커진다*. `min` 이면 `/16` 이 그대로 남아 65,534개를 스캔한다. `max` 여야 한다.
2. **`withPrefix` 가 새 마스크를 안 만들고 기존 값에 AND** — `masked(by:)` 로 구현해서
   `255.255.0.0 & 255.255.255.0 = 255.255.0.0`. 접두사가 줄지 않아 1번이 살아남았다.
3. **`String` 의 그래프eme 클러스터** — Swift 는 `\r\n` 을 **Character 하나로** 취급한다.
   그래서 `buffer.firstIndex(of: "\n")` 이 CRLF 스트림에서 `nil` 이었다. 바이트(0x0A)로 쪼개야 한다.
4. **`URLSession.AsyncBytes.lines` 는 빈 줄을 버린다** — SSE 프레임은 `data: 값` 다음에
   **빈 줄**로 끝난다. lines 로 읽으면 종료 신호를 잃어 이벤트가 영영 완성되지 않는다.
   `curl` 은 `data: tick` 가 오는데 Swift 쪽은 0건이었다. → 바이트를 직접 먹어야 한다.
   *이게 고치지 않으면 메뉴바 진행률이 조용히 죽는다 — 화면엔 아무 이상 없어 보인다.*
5. **`withUnsafePointer(to: sa)` 로 포인터를 한 번 더 감쌈** — `sa` 는 이미 포인터다.
   감싸면 **포인터 변수 자체의 주소**를 넘겨 `sin_addr` 가 포인터 값(0x7700…)을 읽고
   로컬 IP 가 `119.0.0.0` 처럼 깨진다. 게이트웨이 전략이 먼저 성공해서 가려져 있었고,
   스캔 대상 254개가 전부 `119.0.0.x` 였다.

`--diagnose` 가 잡은 것: 4번·5번. 단위 테스트가 잡은 것: 1·2·3번.

## 아직 하지 않은 것 (M2 이후)

- 팔레트(단축키로 작업 검색·제어) — M2
- 독립 창 + 탭바 — M3
- 설정 영속화 (주소 저장, 자동 시작, LaunchAgent) — M4
- 코드 서명 · 공증 — M4 (`notarytool` 필요)
- **메뉴바 아이콘의 실제 렌더링은 아직 눈으로 못 봤다** (이 환경엔 스크린샷이 없다).
  프로세스 생존·접속·SSE 수신까지만 계측했다.
