# PLAN_v0.42_api-mcp-hardening_android.md
> 생성일: 2026-09-27 | 플랫폼: android (+ 외부 클라이언트) | 작성자: opencode
> 전제: v0.41.0 대시보드 헤더 통합 + 브레드크럼 SyntaxError + 보관함 이동 데이터 손실 수정
> 상태: **Phase 0~1 진행 · Phase 2~3(맥 앱)은 보류** — "모든 것이 마무리된 후 꺾끗한 상태에서 시작"

---

## 1. 목표 (1줄)

MCP/REST API 를 외부 클라이언트(맥 메뉴바 앱)가 안전하게 쓸 수 있게 **전송 계층을 규격에 맞추고
교차 출처 공격을 막는다.** 맥 앱 자체(Phase 2~3)는 본 Phase 가 모두 끝나고 별도로 시작한다.

## 2. 조사 결과 (2026-09-27 실측)

### 2-1. MCP 규격 준수 상태 — 4/4 위반

현행 스펙(2026-07-28, Streamable HTTP) 대비 실측:

| 규격 요구 | 실측 | 판정 |
|---|---|---|
| `MCP-Protocol-Version` 헤더 필수 (2025-06-18~) | 헤더 없이 `tools/list` → **200** | 위반 |
| `Origin` 검증 (DNS rebinding 방어, MUST) | `Origin: http://evil.example.com` → **200** | 위반 |
| notification 은 `202` no body | `notifications/initialized` → **200 + `-32603 에러`** | 위반 |
| 단일 POST 엔드포인트 | `/mcp` + 비표준 `/mcp/call` 혼용 | 위반 |

**가장 치명적인 것은 3번이다.** MCP 클라이언트는 `initialize` 직후 `notifications/initialized` 를
반드시 보낸다. 지금 서버는 이를 "지원하지 않는 메서드"로 거부하므로, **도구를 아무리 늘려도
현대 클라이언트가 연결되지 않을 수 있다.**

### 2-2. 교차 출처(CSRF) 벡터 — REST API 전체 (MCP 도저)

```
POST /api/storage/mkdir  -H 'Content-Type: text/plain'  -d '{"path":"","name":"__probe__"}'
→ {"ok":true}     ← 실제 폴더 생성됨 (테스트 후 정리 완료)
```

`text/plain` 은 브라우저 cross-origin **simple request** 로 preflight 가 없다. CORS 는 응답을
가려줄 뿐 요청은 실행된다. 따라서 **같은 Wi-Fi 에서 사용자가 방문한 임의의 웹사이트** 가
`download_add` · `storage/delete` · `settings/*` 를 실행할 수 있다. 응답을 읽을 필요가 없다.

원인: 라우트가 `Content-Type` 을 확인하지 않고 `receiveText()` → `JSONObject(body)` 만 수행.

### 2-3. REST API 는 맥 클라이언트에 충분 (~95 엔드포인트)

| 기능 | 가능 | 근거 |
|---|---|---|
| 주소 붙여넣기 → 등록 | ✅ | `POST /api/jobs` (복수 URL 동시) |
| magnet → 토렌트 | ✅ | `POST /api/torrents/add` |
| 실시간 진행률 | ✅ | `GET /api/events` (SSE) |
| 일시정지/재개/취소 | ✅ | `POST /api/jobs/{id}/{action}` |
| 서버 상태·저장공간 | ✅ | `GET /api/info` |
| 영상 → 해상도 선택 | ✅ | `/api/video/analyze` → `/api/video/create` |
| 보관함 탐색 | ✅ | `GET /api/storage` |
| 통계 | ✅ | `/api/stats/*` |
| 파일을 Mac 으로 | ✅ | `GET /dl-folder/{name}` (ZIP) · `GET /dav/{path}` (**WebDAV → Finder 마운트**) |
| YouTube 등 사이트 추출 | ❌ | v0.12.2 에서 제거 (봇 가드) |

**위치 개입**: 폰이 실제로 다운로드하고 Mac 은 **제어만** 한다. 파일을 Mac 으로 옮기려면
ZIP 경로 또는 DAV 마운트를 쓴다.

**CORS**: 네이티브 앱(URLSession)은 CORS 적용 대상이 아니다. → **네이티브가 정답**이며,
브라우저로 만들면 CORS 에 막히고 위 CSRF 벡터가 더 넓어진다.

## 3. 범위

- 플랫폼: android (Ktor 서버) — **클라이언트 코드 없음**
- 비범위: 맥 앱 구현 자체(Phase 2~3, 보류) · WebDAV 경로의 콘텐츠 타입 제한(추후 별도)
- 설계 원칙: **네이티브 클라이언트를 막지 않는다.** Origin 이 없는 요청(curl·MCP·WebDAV)은 통과

## 4. Phase 0 — 교차 출처 차단 (보안, 최우선)

`RelayServer` 보안 파프라인에 2중 방어 추가.

### 4-1. Origin / Host 검증 (모든 요청)
- `Origin` 헤더가 **있으면** 그 scheme+host+port 가 요청 자신의 host 와 같아야 한다
- 다르면 **403** (DNS rebinding + CSRF)
- `Origin` 이 **없으면 통과** — 네이티브 클라이언트·curl·WebDAV(Finder)·MCP 호환

> 웹 대시보드는 같은 서버에서 서빙되므로 same-origin 이라 항상 통과한다.
> `localhost:3000`(adb reverse)·`https://ip:8443` 모두 host 가 같아 통과한다.

### 4-2. `Sec-Fetch-Site` 보조 판정 (변경 메서드)
- 비-GET 에서 `cross-site` 면 403. 이 헤더는 **JS 로 위조가 불가능**하다.

### 4-3. Content-Type 화이트리스트 (`/api/*` · `/mcp` 의 POST)
허용: `application/json` · `application/octet-stream`(raw-upload) · `multipart/form-data`(upload)
거부: `text/plain` · `application/x-www-form-urlencoded` · 그 외

**경로 예외**: `/dav/*` 는 WebDAV 클라이언트(Finder·davfs2)가 자유롭게 Content-Type 을 쓰므로
**제외**. 4-1 의 Origin 검사가 DAV 를 계속 보호한다.

## 5. Phase 1 — MCP Streamable HTTP 규격 준수

### 5-1. 버전 협상
- 지원 버전: `2025-06-18`, `2025-11-25`
- `2026-07-28` 은 **구현하지 않고** `UnsupportedProtocolVersionError` 로 명시적 거부
  (SEP-2243 `Mcp-Method`/`Mcp-Name` 헤더 + `subscriptions/listen` 이 요구되므로.
  무작정 지원한다고 advertise 하면 규격 위반이 된다)
- `initialize` → `protocolVersion` 협상 결과 반환
- 이후 요청: `MCP-Protocol-Version` 헤더가 지원 버전이 아니면 400
- 헤더와 body 의 `_meta.io.modelcontextprotocol/protocolVersion` 불일치 → 400 `HeaderMismatch`

### 5-2. 메시지 처리
| 메서드 | 처리 |
|---|---|
| `initialize` | `protocolVersion` · `capabilities.tools` · `serverInfo` 반환 |
| `notifications/*` | **202 Accepted + body 없음** (현재는 오류 응답) |
| `tools/list` | `inputSchema` 는 유효한 JSON Schema 객체 |
| `tools/call` | 결과를 **`content:[{type:"text",text:...}]` + `isError`** 형태로 (현재는 raw JSON) |
| 그 외 | **404 + `-32601`** (현재는 200 + `-32603`) |

### 5-3. 엔드포인트
- `POST /mcp` 를 정식 엔드포인트로 유지
- `/mcp/call` 은 **하위 호환 별칭으로 유지하되** deprecation 표시 (기존 호출부 보존)

### 5-4. 도구 확장 (전송 계층 green 후)
| 순위 | 도구 | 사유 |
|---|---|---|
| 1 | `download_add_batch` | 복수 URL — 웹 UI 동작과 동일 |
| 2 | `storage_move` / `storage_mkdir` | T-1072 충돌 가드가 그대로 적용됨 |
| 3 | `torrent_add` | magnet |
| 4 | `video_analyze` / `video_create` | 스트림·영상 |
| 5 | `stats_summary` | 오늘/이번달/누적 |

**비추천**: `file_read` — LLM 에 임의 파일 내용을 넣는 경로. `mcpPrivacyMode`(현재 `false`) 와
도구별 `mcpToolsDisabled`(현재 `[]`) 가 이미 있으므로 기본은 목록만 제공한다.

## 6. Phase 2~3 — 맥 메뉴바 앱 (보류, 별도 문서로 이관)

착수 조건: Phase 0~1 및 남은 안정화 작업 **전부 완료**.

### 6-1. 팔레트 흐름
```
⌘⇧D  ← Carbon RegisterEventHotKey      (NSEvent global monitor 는 Accessibility 권한 요구 → 금지)
  ↓   NSPasteboard.general.string()      (핫키 직후 동기 읽기. 폴링 금지)
  ↓   NSPanel(.nonactivatingPanel, level .statusBar)
      collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
      canBecomeKey = true / canBecomeMain = false
  ↓   Return → POST /api/jobs {url} → 토스트
```

### 6-2. 리서치로 확인된 함정 (반드시 준수)
| 함정 | 대응 |
|---|---|
| `NSEvent.addGlobalMonitorForEvents` 는 Accessibility 권한 요구, 사용자 거부율 높음 | **Carbon `RegisterEventHotKey`** |
| 일반 `NSWindow` 는 원래 앱의 포커스를 빼앗음 → 붙여넣기가 팔레트에 들어감 | **`.nonactivatingPanel`** |
| `MenuBarExtra` 는 macOS 26+ 에서 사용자가 메뉴바 허용을 끄면 **크래시 로그 없이 프로세스 죽음** | **수동 `NSStatusItem` 을 AppDelegate 에서 관리** |
| `NSStatusItem` 해제 시 아이콘이 조용히 사라짐 | **강한 참조 유지** |
| 노치가 있는 화면에서 새 항목이 노치 밑에 생성되어 보이지 않음 | `NSStatusItem Preferred Position` 을 **미설정 시에만** 시드 |
| ad-hoc 서명은 빌드마다 designated requirement 가 바뀌어 권한 부여가 조용히 취소 | **자체 서명 인증서** (유료 계정 아님) |
| IME 로 타이핑하는 사용자의 입력 소스가 바뀜 | summon 시 입력 소스 캡처 → hide 시 복원 |

### 6-3. 갈 수 있는 범위
2-3 표 그대로. **추가 제약**: 폰이 다운로드하고 Mac 은 제어만 한다.

## 7. 대안 (맥 앱을 만들지 않는 경우)

순서상 가성비 좋은 순:

1. **URL 스킴** `droidrelay://add?url=...` — 서버 30분, 클라이언트 거의 0. **최우선 대안**
2. **Hammerspoon** (Lua) — 전역 핫키 + 팔레트 + `curl`. 몇 시간
3. **Raycast extension** — Node 중심

## 8. 빌드 & 검증 계획

- `./build_and_run.sh test android` — Phase 0/1 계약 테스트 추가 후 0 failures
- 실기기(0.41.x, 무선 adb) curl 로 4종 검증
  - 동일 출처 웹 대시보드 **정상 동작** (기존 동작 파괴 없음이 최우선)
  - `Origin: http://evil.example.com` → 403
  - `Content-Type: text/plain` → 405/403
  - `Origin` 없는 curl / MCP → 통과
  - `notifications/*` → 202 no body
  - `MCP-Protocol-Version` 미지원 값 → 400
  - 미지원 메서드 → 404 + `-32601`
  - `tools/call` 결과가 `content` 배열 형태
- WebDAV(Finder) 경로가 Phase 0 때문에 막히지 않는지 확인

## 9. DoD
- [ ] 교차 출처 차단 + Content-Type 화이트리스트 동작, 웹 대시보드 무결성 유지
- [ ] `/dav/*` 예외 확인
- [ ] MCP 알림 202 / 미지원 메서드 404 / 버전 협상 / `content` 응답 형태
- [ ] 도구 확장(5-4) 반영
- [ ] 단위 테스트 GREEN + 실기기 8종 검증 통과
- [ ] 맥 앱 착수 전 남은 안정화 작업 목록이 비어 있을 것
