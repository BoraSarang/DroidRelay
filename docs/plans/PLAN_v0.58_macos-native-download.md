# PLAN v0.58 — macOS 맥 앱 네이티브 Range 받기 (T-1129)

> 배경: 웹 대시보드 Range 루프(T-1128)를 고치던 중 **맥 앱도 같은 고장**임을 발견했다.
> 맥 앱 `받기` 는 직접 받지 않고 `NSWorkspace.open(/dl-file)` 로 **브라우저에 넘겼다.**
> 브라우저 다운로드 매니저는 206 슬라이스를 "완료" 로 저장하고 이어받지 않으므로,
> **맥 앱에서도 32MB 넘는 파일은 32MB 로 잘렸다**(웹 수정으로는 고쳐지지 않는다).
> 상세 근거: `docs/plans/PLAN_v0.58_range-downloader_android.md`

## 1. 전제와 깨지는 지점

| 전제 | 실측/근거 |
|---|---|
| 서버 `serveFile` 은 응답당 32MB 로 자른다 | `MAX_SERVE_SLICE = 32*1024*1024`, 18GB 파일에서 `Content-Range: bytes 0-33554431/19343747520` 확인 |
| 잘린 응답은 `Content-Range` 로 **전체 크기**를 준다 | 실측 헤더 확인. 이 값을 읽지 않는 클라이언트가 잘린다 |
| 맥 앱은 브라우저에 위임했다 | `AppModel.downloadStorage` → `NSWorkspace.shared.open(u)`, `openJobFile` 동일 |
| 브라우저는 저장(attachment)에서 이어받지 않는다 | 사용자 제보(32MB 완료) + 웹 수정의 근거와 동일 |

**깨지는 지점**: 32MB 초과 파일의 `받기` 는 (a) 잘린 파일을 (b) "받는 중" 이라는 안내와 함께
남긴다 — 사용자는 나중에야 안다. **무음 절단**이 가장 나쁜 실패다.

## 2. 설계

`DroidRelayCore/RangeDownload.swift` — 순수 로직 + 얇은 네트워크 계층.

- `ContentRange.parse` — `bytes a-b/TOTAL` 파싱. 형식이 어긋나면 **nil**(추측 금지 → 잘못된 이어붙이기 방지).
- `RangeDownloader.run(fetcher:partURL:)` — 루프:
  - 항상 `Range: bytes=<받은바이트>-` (서버가 첫 요청에도 206 슬라이스를 준다 — 실측).
  - `Content-Range.start != 받은바이트` 면 **거절**(조용한 어긋남 방지).
  - `206` → 이어붙임 / `200` → 처음부터(두 번 연속이면 `noRangeSupport` **실패**) / `416` → 이미 전체면 완료, 아니면 실패.
  - 전체 크기를 모르는 서버(`*`)는 **짧아진 조각**을 끝으로 본다.
  - **같은 offset 재시도 3회**(실측: 폰 Doze 로 전송이 멈췄다가 화면을 켜자 이어졌다).
  - 본문은 `URLSession.download(for:)` 의 **임시 파일**로 받아 4MB 씩 붙인다 → 메모리 상한 32MB(예산 300MB 내).
  - 임시 파일은 **조각마다 삭제**(18GB 를 조각으로 받으면 임시 파일이 쌓여 디스크가 두 배 든다).
- `RangeDownload.download(...)` — `.part` 에 받고 **전체 크기 확인 후에만** 완성 이름으로 `move`. 같은 이름이 있으면 `이름 (1).ext`(덮어쓰지 않는다). 경로 문자가 이름에 있어도 저장 폴더를 벗어나지 않는다.
- 취소는 `Task.checkCancellation` → **`.part` 보존**(다음에 그 지점부터).

앱 배선(`AppModel`): `startDownload(url:key:name:)` 하나로 보관함·잡이 합쳐진다.
진행률은 `downloads[key]`, 취소는 `downloadTasks[key]`. 저장 위치는 표준 `~/Downloads`.
완료/실패/취소를 각각 다른 문구로 안내하고, **Finder 를 강제로 띄우지 않는다**(포커스 스틸 금지).

UI(`PopoverView`): `DownloadBadge` — 받는 중이면 `받기` 대신 **진행률% + 취소**.

## 3. 범위

- **포함**: 보관함 파일 `받기`, 완료 잡 `📥 받기`, 진행률·취소, `.part` 이어받기.
- **제외**: 재생(`▶`, `/stream` — 브라우저/플레이어가 알아서 Range 한다), 폴더 일괄 받기,
  Finder DAV, 웹 대시보드(별도 PLAN), 서버 변경(없음).

## 4. 검증

- `RangeDownloadTests` 22건 — 바이트 일치(100B/8B 조각), 이어받기 시작점, 지점 불일치 거절,
  `Content-Range` 누락, `Range` 무시 서버에서 **자르지 않고 실패**, 416(완료/미완료), 재시도 성공·한도,
  취소 후 `.part` 보존, 이름 정리·중복 회피·폴더 탈출 방지, 진행률 문구.
- `./build_and_run.sh test macos` 387건 0 실패 · `swift build -c release` 성공.
- 실기 E2E 는 T-1129b 로 남긴다(자동 검증은 가짜 서버 기준 — 실 네트워크 경로는 미확인).
