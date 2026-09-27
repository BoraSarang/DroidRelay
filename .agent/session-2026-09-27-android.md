# 세션 로그 — 2026-09-27 (android, 웹 대시보드 헤더 통합)

## 1. 목표
웹 대시보드의 상시 노출 서버 설명 바(`.info`)와 `📊 통계` 탭을 헤더 우측 **아이콘 1개 + 드롭다운**으로 통합

## 2. 변경
- 브랜치 `feat/web-info-stats-menu` (main 직접 push 금지)
- 파일: `WebDashboardHtml.kt` (단일 HTML asset) / 테스트 1개 신규 / 스크립트 2개 신규
- 탭 5 → 4, 닫기 4종(토글·바깥 클릭·Esc·탭 전환) + 단축키 `S`, 상태 배지(녹/적/숨김)
- 부하: 닫힘 상태 통계 API **0건** (이전엔 통계 탭을 열어두면 beat 마다 3건)
- 버전 0.39.0/31 → **0.41.0/32** (`bump-version.sh`)
- 문서: `PLAN_v0.41_info-stats-menu_android.md` · TODO `v0.42` 섹션 T-1064~T-1067

## 3. 설계 중 실제 버그 2건 (브라우저 실측으로만 발견)
| # | 원인 | 실측 | 대응 |
|---|---|---|---|
| 1 | `#infoMenu` 를 `.wrap` 하단에 배치 → `position:absolute` 기준이 **초기 포함 블록(문서)** | 메뉴 top = 뷰포트 높이 + 8px (화면 밖) | 앵커를 `.hd-acts` 로 이동 |
| 2 | 모바일 줄바꿈 시 `.hd-acts` 가 좌측 정렬로 떨어지고, 44px 버튼 래퍼가 앵커라 `right:0` 이 버튼에 걸림 | 390px 뷰포트에서 `dropdown left = -155px` | `.hd-acts{margin-left:auto}` + 액션 그룹 전체를 앵커로 |

**정적 grep 16건·단위 테스트 16건은 둘 다 GREEN 이었다.** 드롭다운 위치는 렌더 박스 계측이 없다면
검증 불가 — Node fake DOM 도 `getBoundingClientRect` 를 흉내내지 못해 같은 맹점을 가진다.

## 4. 검증
| 항목 | 결과 |
|---|---|
| 단위 테스트 | 222 → **240** (신규 18) 0 failures |
| `verify_dashboard_info_menu.js` (신규) | **35/35** — 실제 배포 블록을 vm 으로 실행 |
| `verify_dashboard_realtime.js` (기존) | **20/20** — v0.40 계약 유지 확인 |
| 브라우저 실측 1000px | 우측 정렬 · 차트 464px 무스크롤 · 카드 2열 · 6 하이라이트 · 8 기록 |
| 브라우저 실측 390px | 좌우 12px 대칭 · 카드 2열(154px) · 차트 가로스크롤(380/314) · 설명 세로 스택 |
| 닫힘 상태 요청 | `/api/stats/*` 0건, 일반 API 4건 |
| 배지 | 유휴 숨김 / 활성 노출 / 스로틀 적색 (title 요약 포함) |
| `assembleDebug` | 성공 |

## 5. 사용자 보고 결함 3건 (모두 실기기 브라우저 실측으로 재현 → 수정)
| # | 보고 | 근본 | 검증 |
|---|---|---|---|
| 1 | 사파리에서 통계 버튼 디자인 깨짐 | `min-height:44px` 를 base 에 넣음. 기존 컨트롤 터치 타깃 44px 은 모바일 쿼리 안에만 → 데스크톱에서 📊 44px vs ⟳ 34px | 실측 hDelta 0 / topDelta 0 (1100px·390px) |
| 2 | 보관함 상위 폴더 클릭 시 `SyntaxError: Unexpected token '}'` | `onclick="openDir(" + JSON.stringify(path) + ")"` → 큰따옴표가 속성을 닫아 핸들러 소스가 `openDir(` 로 잘림. **루트 외 브레드크럼 전체가 죽어 있었음** | 실기기 `M` 클릭 → curPath="M", 오류 0 |
| 3 | 순서변경 드롭 표시를 보관함처럼 | `.drop-before/.drop-after` 가 3px box-shadow 뿐 | synthetic DragEvent 4단계 + 3테마 |

- 2번의 동종 잠재 버그 2곳(`createVideo(<URL>)`, `retryVideo(<URL>)`) 은 `jsArg()` 로 함께 수정
- 검증 계층을 3단으로: 정적 계약 25건 · Node vm 실행 35/35 · 브라우저 실측(1100px/390px/드롭/테마)

## 6. 데이터 손실 버그 (T-1072) — 보관함 드래그 이동이 같은 이름 파일을 덮어씀
사용자 보고로 발견. 확인 요청이 아니라 **먼저 서버를 읽어야 했던** 사례.

**근본 2개**
1. `/api/storage/move` 에 충돌 검사가 아예 없었음. `File.renameTo()` 는 POSIX `rename(2)` 라
   대상이 있어도 조용히 대체. `copyTo(overwrite=true)` 폴백도 동일.
   → 같은 저장소의 rename·휴지통·휴지통복원 경로는 **모두 가드가 있었는데 이동만 유일하게 무방어**였다.
2. (덮어쓰기를 허용하게 만든 뒤 드러나는) 이동 검증 실패·예외 시 `dst.deleteRecursively()` 가
   무조건 호출 → 대상이 원래 있던 경우 그것은 사용자 파일. `hadExisting` 로 갈라 해결.

**검증 계층**
- 순수 판정 `StorageMove` 분리 (Ktor route 테스트 하네스 없음) → 단위 13건
- 실기기 프로브 파일로 엔드투엔드: 19B vs 25B 동명 파일
  → 승인 전: conflict 응답 + 목적지 25B 보존 + 원본 잔존
  → 취소: 아무 변화 없음
  → 덮어쓰기 승인: 목적지 19B 로 교체 + 원본 비워짐
- 프로브 완전 정리 (휴지통 포함). 사용자 데이터(`MKMP-752` `JUR-866` 등)는 보존 확인

**UX 판단 2건**
- 확인 버튼 라벨을 `확인` → `덮어쓰기` 로 (파괴적 동작은 행동을 말해야 함)
- 1KB 미만은 `fmt()` 이 `0 KB` 로 뭉개므로 바이트 표기

## 7. 남김
- 브라우저 손 드래그는 미검증 — 합성 DragEvent 로 핸들러·CSS 만 확인. 실포인터는 사람 손이 필요
- 통계 30일 차트는 좁은 화면에서 가로스크롤 유지(기존 동작, 520px → 420px/380px로 축소만)

## 8. API/MCP 하드닝 (T-1073~T-1078) — 맥 클라이언트 전제 조건

사용자 요청: "MCP 도구를 늘려야 하지 않을까" + "맥 메뉴바 앱을 만들 수 있나" → 조사 후 착수.
계획 문서 `docs/plans/PLAN_v0.42_api-mcp-hardening_android.md` 에 보관, Phase 2~3(맥 앱)은 보류.

### 조사에서 발견한 것 2가지 (모두 실측)

**1. MCP 규격 4항목 전부 위반** — 도구를 늘려도 현대 클라이언트가 연결되지 않는 구조였다.
가장 치명적인 것: 클라이언트는 `initialize` 직후 `notifications/initialized` 를 **반드시** 보내는데
서버가 "지원하지 않는 메서드"로 거부했다. `protocolVersion` 은 `2024-11-05`(폐기된 HTTP+SSE 세대)를 반환.

**2. 교차 출처 벡터 — REST API 전체가 열려 있었음** (MCP 도저)
`text/plain` POST 는 브라우저 simple request 라 preflight 가 없다. `POST /api/storage/mkdir` 로
**실제로 폴더가 만들어졌다.** 같은 Wi-Fi 에서 사용자가 방문한 어떤 웹사이트든
`download_add`·`storage/delete`·`settings/*` 를 보낼 수 있었다. CORS 는 응답만 가려줄 뿐
요청은 이미 실행된다.

### 구현
- `CrossOriginGuard` (신규) — Origin/Host 검증 · `Sec-Fetch-Site` · Content-Type 화이트리스트.
  **네이티브 클라이언트를 막지 않는 것이 설계 원칙** (Origin 없는 curl·MCP·WebDAV·향후 맥 앱은 통과)
- `McpServer` 응답 계층 재작성 — 202 알림 / 404 `-32601` / 버전 협상 / `isError` 결과
- 도구 5 → 12개. `storage_move` 는 라우트와 같은 `StorageMove.decide()` 를 쓴다
- `testImplementation("org.json:json")` — android.jar 의 org.json 은 단위 테스트에서 스텁이라
  JSON 조립 로직을 검증할 수 없었다 (이 제약 때문에 판정 로직을 Map 으로 돌려쓴이기도 했다)

### 실기기 검증 13종
| 항목 | 결과 |
|---|---|
| 동일 출처 GET/POST (브라우저 대시보드) | 200 · 폴더 생성 성공 |
| `Origin: evil.example.com` | 403 (E-AND-SRV-0120) |
| `Origin: null` (샌드박스 iframe) | 403 |
| `Sec-Fetch-Site: cross-site` POST | 403 (E-AND-SRV-0121) |
| `text/plain` / `x-www-form-urlencoded` / **Content-Type 생략** | 415 (E-AND-SRV-0122) |
| **실제 data: URL iframe 공격 2건** | 서버 로그로 **요청이 도달해 Origin 규칙에 거부**됨을 확인 |
| WebDAV `/dav/` | Content-Type 검사 제외 · Origin 검사 유지 |
| MCP initialize 협상 | 2025-06-18→동일 / 2025-11-25→동일 / 그 외→2025-11-25 폴백 |
| `notifications/*` | 202 + body 0 bytes |
| 미지원 메서드 | 404 + `-32601` |
| 미지원 버전 | 400 + `UnsupportedProtocolVersionError` + 지원 목록 |
| `tools/call` 실패 | `isError:true` (도구 없음 / 경로 탈출 / 인자 누락) |
| `storage_move` 충돌 | isError + 목적지 25B 보존 / overwrite 시 11B 교체 |
| 대시보드 4탭 + 설정 6라우트 + 드롭다운 | 무결 |

테스트 264 → **300건 0 failures**. 프로브 파일은 휴지통까지 완전 정리.

### 교훈 2가지
- **`text/plain` 이라는 한 글자가 preflight 를 건너뛴다.** "JSON API" 라는 것만으로는 CSRF 방어가 아니다.
- 규격 준수 여부는 **실측 표로** 봐야 한다. 4항목이 모두 위장 없이 위반 상태였고,
  정적 코드 리뷰로는 드러나지 않았다.

## 9. 남김
- **MCP 클라이언트 실연결 미검증** (Claude Desktop·Cursor). curl 스펙 검증까지만 했다.
- Phase 2~3 맥 메뉴바 앱 — 전 Phase 완료 후 착수 (PLAN_v0.42 6장에 함정 6종 기록)
- adb 기기가 USB·무선 2경로로 잡혀 `build_and_run.sh` 가 `more than one device` 로 실패한다.
  `adb -s R5CT215F4QK` 로 대상을 고정했다. (스크립트 수정은 별건)
