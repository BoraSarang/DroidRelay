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
swift test             # 순수 로직 335건
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
| `DroidRelayCore` | `ServerDiscovery` · `RelayClient` · `EventStream` · `Subnet`/`IPv4` · `AppMenu` · `Notice` | O (순수 로직) |
| `DroidRelayMac` | `AppModel` · `PopoverView` · `StatusItemController` · `MainMenu` · `Diagnostics` | — (AppKit 위에서만 의미가 있음) |

Core/UI 를 뺀 이유는 **탐색 계산과 SSE 파서를 OS 없이 검증**하기 위해서다.
아래 버그 5건 중 4건이 그 결과로 잡혔다.

같은 이유로 **메뉴 명세(`AppMenu`)** 와 **안내 규칙(`Notice`)** 도 Core 에 있다 —
AppKit 런타임 없이 "⌘V 가 사는가"·"실패가 남는가" 를 테스트로 고정한다.

## 메인 메뉴 (`⌘V` 붙여넣기 · `⌘,` 설정)

macOS 의 단축키는 **뷰가 아니라 `NSApp.mainMenu`** 가 처리한다. `⌘C/⌘V/⌘X/⌘A/⌘Z` 는
`편집` 메뉴 key equivalent 가 first responder(`paste:`) 로 보내고, `⌘,` 는 앱 메뉴 항목이
연다. 이 앱은 씬 없이 `NSApplication` 을 직접 부팅하므로 SwiftUI 가 기본 메뉴를 만들지
않는다 — **직접 설치하지 않으면 두 단축키가 코드 오류 없이 죽는다.**

- `AppMenu`(Core): 메뉴 **명세**. `Item.receiver` 가 **누가 action 을 받는가** 를 정한다.
  - `.responderChain` — `paste:` · `terminate:` 처럼 AppKit/필드 편집기가 구현
  - `.appController` — **앱이 직접 구현**(`drShowSettings:` 등). `target = nil` 이면
    responder chain 에 구현이 없어 AppKit 이 **항목을 비활성(회색)으로 만든다**
- `MainMenu`(Mac): 명세를 `NSMenu` 로 옮겨 설치한다(`Boot.start()` 런루프 전).
  **우클릭 메뉴도 같은 함수(`makeMenu`)를 쓴다** — 규칙이 한 벌이어야 반쪽 수정이 안 생긴다.
- `--menu-check`: 메뉴·붙여넣기·설정 항목의 `target`(responder chain/고정)을 수치로 확인한다.

붙여넣기는 **두 번** 죽었다 — v0.7.1 에서 고쳤다가 macOS 앱을 삭제(`ebfaa27`)하고 메뉴바
앱을 재작성할 때 재발. 그래서 `AppMenuTests` 로 회귀를 **빌드 시점에** 잡는다.

## 메뉴바 우클릭 메뉴

메뉴바 아이콘 **우클릭**으로 뜨는 짧은 메뉴(`AppMenu.statusMenu`):
대시보드 열기 / 설정… ⌘, / 종료. 좌클릭은 팝오버를 연다
(`MenuBarSpeedView.mouseDown` vs `rightMouseDown`).

**팝업 메뉴에는 responder chain 이 없다** — 항목이 전부 앱이 구현하는 selector 라
**명시적 target**(`StatusItemController`)을 건다. 이 배선이 **스텁에 걸려** `설정…` 이
M1(`e05d0d4`) 부터 동작하지 않았다 — `openSettings()` 가 앱만 활성화하고 창을 열지 않았다.
(팝오버 안 `설정…` 행은 다른 경로라 정상이어서 "설정은 되는데 우클릭만 안 됨" 으로 보였다)

- `--settings-menu-check`: 메뉴를 **실제로 만들어 눌러 보고** 설정 창이 떴는지 본다.
  코드를 읽는 것도, 창을 직접 띄워 보는 것(`--settings-check`)도 이 부류를 못 잡는다 —
  그 진단은 창 컨트롤러를 직접 호출하므로 **메뉴 배선을 건너뛴다.**

## 결과 안내 배너

쓰기 동작의 결과는 팝오버 하단 배너로 뜬다. **성공·정보는 4초 뒤 스스로 사라지고,
실패는 남는다** — 실패가 조용히 사라지면 사용자는 성공으로 믿게 된다(무음 실패 금지).
아이콘·색은 종류에서 파생된다: 성공=체크 초록 · 정보=ⓘ 회색 · 실패=경고 주황.
규칙은 `Notice`(Core)에 있고 `NoticeTests` 로 고정된다.

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
