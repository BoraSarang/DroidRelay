# 세션 로그 — 2026-09-28 (macos, 쓰기 계열 실사용화 1차 · PR #31)

## 1. 목표
macOS 메뉴바 클라이언트의 쓰기 계열 기능(보관함 폴더 진입 · 동영상 재생 · 받기)을
실사용 가능하게 만들고, 6단계(최소 설정 창)까지 갔다. 목업 없이 착수한 부분은 없다.

## 2. 사고 — "http 는 브라우저만 연다" 는 틀렸다

IINA 로 `/stream` 을 못 연다고 **단정**했다. 근거는
`NSWorkspace.urlForApplication(toOpen:)` 였다.

**이건 Launch Services 등록 목록이지 앱의 실제 능력이 아니다.**
IINA 를 `open(_:withApplicationAt:)` 로 직접 지정하니 **실제로 재생된다**
(서버 로그 `206 부분` + 창 제목이 파일명으로 바뀜).

첫 시도를 실패로 판정한 실수: `pgrep -x iina` 로 찾았는데 **프로세스 이름이 대문자
`IINA`** 라 "안 됨"으로 읽었다. 이미 떠 있던 인스턴스를 못 보고 `iina-cli` 로 새
인스턴스를 만들려다 인계가 어긋나 아무 요청도 안 갔다. **404 는 내 URL 인코딩
실수였고, 그 다음 첫 시도가 이미 성공**이었다.

> **교훈** — **"앱이 못 한다" 는 판정은 Launch Services 한 번으로 내리지 않는다.**
> 실제 실행을 보는 게 조용한 부재 확인(check 0개)보다 값지다. 없는 것과 닫힌 것은 다르다.

## 3. 사고 — `.sheet` 는 붙은 뷰의 *윈도우*에서 뜬다

"설정이 창으로 안 뜨고 팝오버로 보인다" 는 사용자 지적이 맞았다.
원인은 설정이 `.sheet(isPresented:)` 로 `PopoverContainer` 에 붙어 있어서였다.
**`.sheet` 는 붙은 뷰의 윈도우에서 뜨는데, 그 윈도우는 팝오버(352×520, floating panel)라
자기 자신 안에서 자라났다.** 사용자가 본 건 "창"이 아니라 **팝오버가 설정 모양으로 바뀐 것**이었다.

→ 시트를 걷어내고 `NSWindow`(`SettingsWindowController`) 를 세웠다.

**`.accessory` 앱에서 `NSWindow` 를 세울 때 남는 함정 두 개**(둘 다 코드에서 막음):
1. `NSApp.activate` 를 **`present()` 안에서 먼저** 부르지 않으면 창이 key 가 되는
   시점에 앱이 아직 비활성이라 **입력창이 있어도 키보드가 안 들어간다.**
2. 컨트롤러를 **강한 참조로** 안 들면 풀려서 **창이 통째로 사라진다.**
   `isReleasedWhenClosed = false` 만으로는 컨트롤러 해제를 못 막는다.

## 4. 변경 — PR #31 머지 (`1b27d58`)

| 영역 | 내용 |
|---|---|
| 보관함 | 폴더의 이름·아이콘·크기를 **하나의 `Button`** 으로 묶어 행 어디든 눌러도 진입 |
| 재생 | `ActionRules.isBrowserPlayableVideo` + `RelayClient.streamURL/downloadURL` + `AppModel.playStream/downloadStorage` |
| IINA | `MediaOpener.resolve(bundleIDs:lookup:)` — **우선순위를 Core 의 순수 함수로.** `lookup` 주입이라 이 Mac 에 IINA 가 있어도 없어도 같은 판단을 검증 |
| 설정 | `SettingsWindowController`(별도 창) + `SettingsView` + **안전망 폴링 주기** |
| 테스트 | 신규 31건 — `StorageMediaTests` 11 · `BrowserPlayableRuleTests` 5 · `MediaOpenerTests` 5 · `BackupPollTests` 10. **전체 206건 0 실패** |

**폴링은 둘 중 하나만 설정에 노출했다.** 10초 안전망만 열고 1초 속도 샘플러는 고정.
1초를 노출하면 **그래프만 거칠어지고 서버 부하만 오른다** — 사용자가 얻는 게 없다.
안전망은 길어도 안전하다(평소 갱신은 SSE 가 한다). 선택지만 5/10/15/30/60초를 주는 이유:
"2초" 를 고를 수 있게 하면 안전망이 주 폴링이 되어 규칙이 깨진다.

## 5. 사용자 판단 (이번 세션)

| 질문 | 답 |
|---|---|
| ▶ 를 어느 앱으로 | **IINA 우선, 없으면 브라우저** |
| OrbStack 잡 유실 추적 | **신경 끄기** (파일은 보관함에 안전) |
| 6단계 | **진행** — 폴링 주기 위치는 **서버 주소 바로 아래** (ni가 정하라 해서) |
| 설정 창 | **별도 창으로** (팝오버로 보인다고 지적) |
| 검증 | **본인이 직접 확인** — "잘 드고 잘 되" |

## 6. 검증 (사용자 직접 확인 포함)

- 폴더 이름 클릭 → 실제 진입, 서버 `M` 폴더 내용과 일치
- mp4 행 ▶ → 재생 정상. 동영상 IINA 재생 (사용자 + 서버 로그 206 + 창 제목)
- 테스트 더미 `Zed-dummy-test.mkv`(300000B) 받기 → `~/Downloads` 에 **300000 바이트 도착**
- mkv 행에 `▶` 없고 `받기` 만 있는 것 — AX 덤프로 확인
- **설정 창이 별도 창으로 열림 + 동작 (사용자 확인 "잘 드고 잘 되")**
- macOS **206건 0 실패**

**테스트 데이터 정리 완료** — 서버 `AI/Zed-dummy-test.mkv` 삭제 + 휴지통 영구 삭제(0개).
보관함 원본(폴더 5개 + `OrbStack…dmg`/`v2.45.0.tar.gz`/`v2.47.0.tar.gz`) 그대로,
`~/Downloads` 테스트 파일 삭제.

## 7. 다음 세션에 남긴 것

### 미결 (사용자 결정 대기)
- **`.mkv` 재생 버튼** — IINA 가 `.mkv` 를 기본 처리(번들 첫 Document Type 이 Matroska)라
  재생 가능하지만, "IINA 있을 때만" 조건부 규칙이라 **IINA 없는 Mac 에서 눌려도 안 되는
  버튼**이 될 위험이 있다. 현재 안전하게 `받기` 만. 규칙을 넓힐지 결정 필요.
- **사용자 토렌트 `MOOC-016` 복구 불가** — magnet 없음. `T38-072` 완성 파일(4.09 GB)은
  `M/4k688.com@T38-072.mp4` 에 살아있어 재생 가능. `MOOC-016` 은 재다운로드 필요.

### 대기 항목
| ID | 내용 | 상태 |
|---|---|---|
| M-10 | 목업 리뷰 4건 (팝오버 폭 · 통계 행 · 팔레트 단계 · **탭바**) | **탭바만** 반영. 3건 대기 |
| M-11 | 탐색 상태 표시 (`🔌 10.38.120.211 자동 발견 ✓`) | 미착수 |
| M-12 | 팔레트 (Carbon 핫키 `⌘⇧D` · `.nonactivatingPanel` · IME 입력 소스 캡처) | **시스템 권한 필요** |

### 편집 함정 (다시 걸리면 시간 잡는다)
- heredoc/python 편집 시 `\\(` 이중 삽입 · `rg -rn` 은 `-r` 이 replace 플래그로 오작동
  → `edit` 도구와 `rg -n` 사용. **편집 직후 `rg -n '[一-龥]'` 로 CJK 혼입 검사.**
- IINA 관련: 프로세스 이름은 **대문자 `IINA`** (`pgrep -x iina` 로 안 잡힌다).
  이미 떠 있으면 `iina-cli` 인계가 어긋나 요청이 안 갈 수 있다 → `pkill -x IINA` 후 테스트.
- URL 수작업 인코딩은 404 를 만든다. 서버가 준 정확한 파일명을
  `urllib.parse.quote(name, safe='')` 로 인코딩할 것.
- **사용자 화면 자동 조작 금지** (23:0x 통보). 빌드·설치까지만 하고 클릭은 사용자 몫.
- 앱 프로세스 이름은 `DroidRelayMac` 이 아니라 **`DroidRelay`**.
- 이미지 첨부는 볼 수 없다(모델이 이미지 입력 미지원). 목업/스크린샷은 **말로** 받을 것.

## 8. 다음 세션 시작 시

```
git checkout main && git pull
bd list + docs/TODO.md 의 M-10~M-12
swift test --package-path apps/macos   # 206건 기준선
./build_and_run.sh debug macos
```
