# 세션 로그 — 2026-09-28 (macos, M1 PR 병합 · M3 탭 · 빌드 스크립트)

## 1. 목표
맥 메뉴바 클라이언트 재개. 브랜치를 main 에 합치고, 남은 공백(탭)을 채우고,
빌드 스크립트로 설치·실행 가능한 상태를 만든다.

## 2. 사고 — 또 파일을 날렸다 (2번째)

`git reset --hard origin/main` 으로 로컬 main 을 동기화했는데, **macOS 코드가
`feat/macos-menubar-m1` 에만 있었다.** main 에 없는 파일이 작업 트리에서 삭제됐다.

복구: `git checkout origin/feat/macos-menubar-m1 -- apps/macos .agent docs/mockups`
→ 12개 swift + 세션 로그 + 목업 2건 전부 복구. 빌드·테스트 정상 확인.

> **교훈** — 브랜치에 있는 작업은 main 에 합쳐놓기 전까지 `reset --hard` 의 blast radius 에
> 포함된다. "브랜치에 있다"는 게 안전망이 아니다. **PR 을 먼저 열어야** 안전하다.
> 이걸 M-09 를 1순위로 앞당긴 이유였다.

## 3. 변경

| 영역 | 내용 |
|---|---|
| M-09 | `feat/macos-menubar-m1` → main 리베이스(충돌 0) → **PR #17 머지** (`e05d0d4`) |
| M-13 | 팝오버 **3탭** — `Torrent`/`StorageEntry` 모델 · `torrentControl`/`torrentDelete` · 탭별 통계·배지 · `--diagnose` 확장. 테스트 25 → **39건**. **PR #18 머지** (`0ce200b`) |
| M-08 | **사용자 확인 완료** — 3탭 동작, 폰 연결 3개, SSE `data: tick` |
| 스크립트 | `build_and_run.sh` 에 **macOS 분기 신설** (usage 에는 `[android|macos]` 가 적혀 있었으나 구현이 없었다) |

## 4. 빌드 스크립트 — macOS 분기

| 명령 | 동작 |
|---|---|
| `debug macos` | swift build(release) → `.app` 번들 → ad-hoc 서명 → /Applications 설치 |
| `run macos` | 실행 + **프로세스 생존 확인** (창이 없어 `open` 이 "아무 일도 없다"로 보임) |
| `diagnose macos` | 서버 탐색 · SSE 생존 |
| `test macos` / `clean macos` / `uninstall macos` | 각 플랫폼 대응 |

`.app` **번들**이 필수다. `Info.plist` 의 `LSUIElement` 없으면 Dock 아이콘이 뜨고
macOS 가 경고를 띄운다. 코드의 `NSApp.setActivationPolicy(.accessory)` 는 **실행 뒤**라
첫 프레임부터 Dock 에 안 뜨려면 번들 플래그가 있어야 한다.

### 스크립트에서 잡은 버그 2건

**1. `$2` 충돌 — 기존 Android 명령이 깨졌다**
`$2` 를 플랫폼 인자로 재정의하니 `run`·`log` 의 `sleep "${2:-5}"` 가 `"android"` 를 받아
`sleep: invalid time interval` 이 났다. → 숫자면 예전 방식(`run 8` = 8초)으로 해석하는
폴백을 넣고 두 경로 모두 재검증.

**2. 번들 바이너리명 오인**
SwiftPM 산출물은 `DroidRelayMac`, 번들 내부는 `DroidRelay`. 처음엔 같은 이름으로 찾아
`바이너리 생성 실패`. → 타깃명(`MACOS_TARGET`)과 앱 이름(`APP_NAME`)을 분리했다.
번들 실행 파일명 ≠ 빌드 산출물명이고, `CFBundleExecutable` 과 실제 파일명이
일치하기만 하면 된다.

## 5. M-13 설계 판단 3가지

1. **탭별 선택적 갱신** — SSE tick 이 빠르면 1초다. 매 tick 4요청은 부하.
   선택된 탭만 갱신하고 전환 시 즉시 당긴다(`selectTab`).
2. **`torrentDelete` 는 `POST /{action}` 이 아니다** — 서버는
   `/api/torrents/{id}/{action}` 에 pause·resume·files·limit 만 분기하고 삭제는
   `DELETE /api/torrents/{id}`. 처음에 action 으로 묶었다가 분리.
3. **알 수 없는 상태값은 원문 유지** — 서버가 새 상태를 줘도 "UNKNOWN" 으로 뭉개지지 않는다.

## 6. 함정

**`ByteCountFormatter` 하드코딩** — 출력을 `"2.0 MB"` 로 고정했다가 **실제로는 `2MB`** 라
테스트가 실패했다. 이 포매터는 OS 버전마다 "2 MB"/"2MB"/"2.0 MB" 를 오가므로 하드코딩하면
업그레이드마다 깨진다. 문자열 하드코딩을 빼고 **"같은 바이트면 같은 표시"** 라는
실제 계약으로 바꿨다.

**진단 중 오판 — "앱이 안 붙었다"** — `lsof -a -p PID -iTCP` 가 안 잡혀 연결이 없다고
결론냈는데, 폰 쪽 `netstat` 에 established 3개가 있었다. `lsof` 필터를 믿지 말고
**서버 쪽에서 본다.**

## 7. 검증

- Android 테스트 **320건 0 실패** · macOS **39건 0 실패** (회귀 없음)
- 실폰 `--diagnose`: 탐색 게이트웨이 69ms · T38-072 진행 중 · 보관함 4폴더 파싱
- 설치본: `/Applications/DroidRelay.app` · `LSUIElement=true` · ad-hoc 서명
- 프로세스 pid 72534 · 폰 established 3개 · SSE `data: tick` 스트리밍
- **사용자 확인: 3탭 동작 정상**

## 8. 남은 것

| 항목 | 내용 |
|---|---|
| M-10 | 목업 리뷰 4건 (팝오버 폭 · 통계 행 · 팔레트 단계 · **탭바** — 탭바는 M3 에서 반영) |
| M-12 (M2) | 팔레트 — Carbon 핫키 `⌘⇧D` · `.nonactivatingPanel` · IME 입력 소스 캡처 |
| M-14 (M4) | 설정 영속화 + **DMG 배포 경로** (배포 방식은 DMG 로 확정) |

> **Gatekeeper 사실 하나** — `spctl rejected` 는 ad-hoc 서명의 현재 판정이고 지금은
> 로컬 빌드(격리 속성 없음)라 실행에 문제없다. 다만 **다운로드받은 파일엔
> `com.apple.quarantine` 가 붙으므로 DMG 는 Gatekeeper 를 가장 통과하기 어려운 경로다.**
> ad-hoc + DMG 면 사용자가 우클릭 → 열기(1회) 또는 `xattr -dr` 가 필요하고,
> 이것을 없애려면 Developer ID + 공증(Apple Developer Program 연 $99)이 필요하다.
> 불만을 감수하면 ad-hoc + DMG 로 배포는 된다.
