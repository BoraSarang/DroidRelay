# PLAN v0.58 — 웹 대시보드 Range 루프 다운로더 (T-1128)

## 배경 (사용자 제보)

> "S22 웹 보관함에서 EXAONE-3.5 18GB gguf 파일을 다운로드하려는데 **32MB만 받고 완료**로 나온다"

### 원인 규명 (2026-10-18, 코드 실측)

1. `serveFile()`(RelayServer.kt)은 **응답당 32MB 상한**(`MAX_SERVE_SLICE = 32 * 1024 * 1024`)을 적용한다.
   `9eb870c`(2026-10-14, "A90 대용량 다운로드 OOM")에서 도입 — Ktor-Netty 청크 소비 경로가
   소켓 배출 대비 직접 버퍼를 과적재해 A90에서 3.7GB 전송 중 RSS 3.5GB → lmkd kill이 실측됐고,
   32MB 통짜(FullHttpResponse) 응답 + 클라이언트의 Range 자동 이어받기로 대체했다.
2. 이 가정이 **브라우저 일반 다운로드(`<a download>` 내비게이션)에는 틀렸다** —
   브라우저 다운로드 매니저는 서버가 자른 206을 완료 응답으로 저장하고, `Content-Range`의
   전체 크기는 무시하며 후속 Range 요청을 보내지 않는다.
   (자동 이어받기는 `<video>`/`<audio>`, WebDAV 클라이언트, Range 루프 구현 클라이언트에서만 성립)

→ 웹 보관함 📥(`/dl-file`)·📥 받기(`/file/{id}`)로 받으면 18GB 파일이 **정확히 32MB로 잘린 채 "완료"**.
   폴더 ZIP(`dl-folder`)·만료 공유(`/s/`)도 동일 경유라 같은 결함.

## 목표

- 웹 대시보드가 **직접 Range 루프**로 받아 32MB 슬라이스를 끝까지 이어붙인다.
- **서버 무변경** — OOM 회귀 위험(`serveFile` 32MB 상한은 유지)이 없다.
- 18GB 파일도 **브라우저 메모리에 올리지 않고** 디스크에 연속 기록한다.

## 설계

### 클라이언트 다운로더 `dlRange(url, name)` (WebDashboardScript.kt에 JS 추가)

1. 저장 위치 선택: `showSaveFilePicker({suggestedName})` → `createWritable()`.
2. 루프: `fetch(url, {headers:{'Range':'bytes='+offset+'-'}})`
   - 서버가 206으로 32MB 슬라이스를 주면 `response.body.getReader()`로 청크 단위 읽기 →
     `writable.write(chunk)` (메모리는 청크 수준만 — 18GB도 안전)
   - `Content-Range: bytes a-b/total` 에서 `total` 파악, `offset` 누적
   - `offset >= total` 이면 `writable.close()` → 완료
3. 중단/실패: 사용자 "취소" → `AbortController` + `writable.abort()` (부분파일 폐기).
   일시 오류 → 같은 offset으로 최대 3회 재시도 후 포기 (무한 루프 금지).
4. 진행 표시: 기존 토스트 대신 하단 고정 진행 바 (파이름 · 받은/전체 · % · 취소 버튼).

### 폴백 (File System Access 미지원 브라우저)

- `showSaveFilePicker` 없음(HTTP LAN 등 보안 컨텍스트 아님 — **사용자 주 사용 케이스**) →
  Range 루프는 그대로 돌되 메모리 Blob 누적으로만 받고, `DL_MEM_MAX`(256MB) 초과 시
  **명시적 실패 안내**(HTTPS 켜기 · 맥 앱 받기 · Finder DAV 이용) — 무음 절단 금지.
  브라우저가 보안 컨텍스트가 아니면 파일 쓰기 저장소를 못 열므로, 대용량은 근본적으로
  HTTPS 또는 비브라우저 경로가 필수다. (Blob 누적 폴백도 YAGNI로 별도 구현 없음)

### 배선 대상 (2곳 — 폴더 ZIP은 범위 밖으로 수정)

| 지점 | 현재 | 변경 |
|---|---|---|
| 보관함 파일 📥 (`WebDashboardScript.kt`) | `<a download>` | `onclick=dlRange(this.href,this.download)` + `preventDefault` |
| 잡 완료 📥 받기 (`WebDashboardScript.kt`) | `<a download>` | `onclick=dlRange(this.href,this.download)` |
| 보관함 폴더 📦 (`WebDashboardScript.kt`) | `<a download>` | **유지** — `/dl-folder` 는 요청마다 ZIP 재빌드라 슬라이스 루프가 재빌드 폭주를 일으킴 (T-1128b로 후속)

인라인 핸들러 지뢰 규칙(DashboardInlineHandlerContractTest) 준수 — 사용자 값(파일명)은
`jsArg()` 경유, 자유 텍스트 직접 삽입 금지.

## 범위 밖 (명시)

- 서버 `serveFile` 32MB 상한 **유지** — A90 OOM 회귀 방지가 우선 (근본 재설계는 별도 이슈)
- 만료 공유 링크 `/s/{token}` — 대시보드 밖 독립 페이지라 그대로 (32MB 한계 유지, 후보 기록)
- 맥 앱 "받기" — 자체 Range 이어받기 있음 (T-406), 미검증 범위 아님

## 검증 계획

- 계약 테스트: 대시보드 HTML에 `dlRange` 함수 + 3개 배선 지점 존재, 인라인 핸들러 규칙 통과
- 빌드: `./build_and_run.sh test android` 0 실패 + `assembleDebug`
- E2E: 실기(S22/A90) >32MB 파일 웹 다운로드 → 전체 크기 수신 확인 (기기 재기 후)

## 버전

- `0.57.0/39 → 0.58.0/40` (`scripts/bump-version.sh`)
