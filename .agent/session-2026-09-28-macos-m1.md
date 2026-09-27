# 세션 로그 — 2026-09-28 (macos, 메뉴바 클라이언트 M1)

## 1. 목표
목업(`docs/mockups/mac-menubar.html`) 리뷰를 건너뛰고 **M1(메뉴바 + 팝오버 + 진행률 + 서버 탐색)을 실제 코드로 구현**

## 2. 변경
- 브랜치 `feat/macos-menubar-m1` (main 직접 push 금지) → `787457b`, origin 푸시 완료. **PR 미생성**
- 신규 `apps/macos/` — Xcode 프로젝트 없이 SwiftPM (저장소가 Gradle 기반이라 일관성)
  - `DroidRelayCore` (테스트 가능): `ServerDiscovery` `RelayClient` `EventStream` `Subnet`/`IPv4` `NetworkInfo`
  - `DroidRelayMac` (AppKit): `AppModel` `PopoverView` `StatusItemController` `main.swift`
  - `Tests/` 25건 · `README.md`
- `docs/mockups/probe_discovery.py` 커밋 (탐색 실측 스크립트, 앱 코드 아님)

**Core/UI 를 분리한 이유 = 탐색 계산과 SSE 파서를 OS 없이 검증하기 위해서.** 앱 코드에 섞어두었다면
아래 버그 1·2·3은 테스트할 방법이 없었다.

## 3. 서버 탐색 (실측 기반 계층)
`cached 0ms → gateway 35ms → /24 스캔 0.11초 → 수동 입력`. 근거는 `probe_discovery.py`.
게이트웨이만으론 부족하다 — 폰이 공유기를 단 말단이면(phone=10.38.120.147, gateway=10.38.120.1)
게이트웨이에 폰이 없다. 그래서 3단계 계층이 필요했다.
`/16` 에 붙으면 로컬 IP 의 `/24` **만** 254호스트 스캔(65,534개면 안 된다).

## 4. 발견한 실제 버그 5건 (전부 수정)
| # | 원인 | 증상 | 잡은 주체 |
|---|---|---|---|
| 1 | `min(prefix,24)` — 캡 방향 반대. 접두사가 *작아질수록* 서브넷이 *커진다* | `/16` 이 남아 **65,534개** 스캔 | 단위 테스트 |
| 2 | `withPrefix` 가 새 마스크를 안 만들고 `masked(by:)` 로 AND | 접두사가 안 줄어 1번이 살아남음 | 단위 테스트 |
| 3 | Swift 는 `\r\n` 을 **Character 하나**로 취급 | CRLF 스트림에서 `firstIndex(of:"\n")` 이 `nil` | 단위 테스트 |
| 4 | `AsyncBytes.lines` 가 **빈 줄을 버림** | SSE 프레임 종료 신호 소실 → 이벤트 영영 미완성 | `--diagnose` |
| 5 | `withUnsafePointer(to: sa)` — 이미 포인터인 걸 한 번 더 감쌈 | 로컬 IP `119.0.0.0` (포인터 값 0x7700 을 읽음) | `--diagnose` |

**4번이 가장 위험하다.** 고치지 않으면 메뉴바 진행률이 **조용히 죽는데 화면엔 아무 이상 없어 보인다.**
`curl` 은 `data: tick` 이 오는데 Swift 쪽은 0건이었다 — 그래서 `--diagnose` 를 넣었다.
5번은 게이트웨이 전략이 먼저 성공해 가려져 있었다. 스캔 대상 254개가 전부 `119.0.0.x` 였다.

## 5. 커밋 전 점검에서 추가 수정 2건
- `NSWindow.didResignKey` 로 팝오버를 닫으면 **설정 시트 열 때 팝오버까지 사라진다**
  (`.transient` 가 바깥 클릭을 처리하므로 관찰자 제거)
- 배지가 `install()` 시점 값으로 고착 — `badgeChanged` 알림을 받도록 연결

## 6. 검증
| 항목 | 결과 |
|---|---|
| `swift test` | **25건 0 failures** |
| `swift build` | 통과 (Swift 6.4 / macOS 27.2 / Xcode 27.0) |
| 진단 — 인터페이스 | en0 · `10.38.120.193/255.255.255.0` · gw `10.38.120.211` · 스캔 254개 |
| 진단 — 탐색 | `10.38.120.211:3000` v0.42.0 · **게이트웨이 46ms** |
| 진단 — SSE | HTTP 200 · `text/event-stream` · **tick 2건/21초 ✓** |
| 앱 실행 | 접속 확인 (`[DroidRelay] 연결됨 … (게이트웨이)`) |

## 7. 교훈
- **메뉴바 앱은 `--diagnose` 없이는 못 고친다.** 화면이 없으므로 실패 원인을 볼 수단이 stdout 뿐이다.
  버그 2건이 여기서만 드러났다. 이런 앱엔 진단 모드가 기능이 아니라 **필수 장비**다.
- 검증 도구를 OS 밖으로 뺄수록 값이 나는 것도 같다 — 1·2·3번은 단위 테스트가, 4·5번은 진단이 잡았다.
  어느 쪽이든 **앱 안에 두면 둘 다 못 잡았을 것**이다.
- `nohup` 실행 시 stdout 은 완전 버퍼링이라 로그가 비어 보인다. pty(`script -q`)로 돌려야 보인다.
  "앱이 멈췄다"는 오진이었다 — `sample` 로 보니 이벤트 루프에서 정상이었다.

## 8. 남김
- **PR 미생성** — `feat/macos-menubar-m1` 이 origin 에 있음. 열어서 리뷰/병합 필요
- **메뉴바 아이콘 실제 렌더링 미검증** — 이 환경엔 스크린샷이 없다. 프로세스 생존·접속·SSE 까지만 계측.
  사용자가 직접 띄워 눈으로 확인해야 함
- **목업 리뷰 4건 미반영**: 팝오버 폭 352px · 통계 행 · 팔레트 3단계/1단계 · 독립 창 탭바
- **탐색 상태 표시 추가 여부 미결** (`🔌 10.38.120.211 자동 발견 ✓`)
- M2 팔레트 · M3 독립 창 · M4 설정 영속화/서명/공증 전부 미착수
- 목업(`docs/mockups/mac-menubar.html`)은 여전히 유효 — M2/M3 착수 전에 리뷰하면 좋음
