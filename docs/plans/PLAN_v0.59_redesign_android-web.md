# PLAN v0.59 — "Relay Console" 리디자인 (android + web)

> 크기: **L**(리디자인) · 사용자 요청(2026-10-21): *"안드로이드 디자인 스킬로 최신 방법으로 리디자인, 다크/라이트 지원 목업. 웹도 같이"*
> 목업(합의용): `docs/mockups/redesign-v059/android.html` · `web.html` · `README.md`

## 1. 배경

- 안드로이드 앱은 Material 3 를 쓰지만 **기본 시드·기본 컴포넌트 배치** 수준이다. 하단 내비가 6개(M3 권장 3–5)이고,
  상태/통계/설정 화면이 정보 위계 없이 카드 나열이다.
- 웹 대시보드는 **문자열 계약**(`WebAssets.dashboardHtml`) 위에 자체 테마 3종(Toxic/Midnight/Navy)을 얹어
  앱과 시각 언어가 다르다. 테마가 3개라 유지보수 비용도 3배다.
- 대용량 전송이 핵심 기능인데 **처리량·진행률·남은 시간의 시각 위계가 낮다**.

## 2. 목표 / 비목표

**목표**

1. 앱과 웹이 **같은 토큰(M3 역할)** 을 쓰는 단일 시각 언어 "Relay Console".
2. **라이트/다크 완전 지원** (안드로이드는 기존 SYSTEM/LIGHT/DARK 설정 유지, 웹은 신규).
3. 숫자(속도·진행률·용량)를 **tabular numeral + 큰 타입**으로 승격.
4. 안드로이드 내비 **6 → 5**, 통계·설정은 "더보기" 보조 화면. 웹은 상단 탭 → 좌측 레일.
5. 웹 테마 **3 → 2(+시스템)**, CSS 커스텀 프로퍼티로 통합.

**비목표**

- 기능 추가·API 변경 없음. **동작 불변**(라우팅/핸들러/ID 유지).
- iOS/맥 앱 리디자인은 범위 밖(토큰만 공유 가능하게 설계).
- 폴더 ZIP(T-1128b)·공유 링크(T-1128c)는 이 PLAN 에서 구현하지 않는다.

## 3. 디자인 확정안

| 항목 | 값 |
|---|---|
| 시드 | `#006B56` (딥 파인) — 다크 primary `#62DCBE` |
| surface | Light `#F6FBF7` / Dark `#0E1513` |
| 강조(업로드·경고성 정보) | tertiary `#7C5800` / `#F5BF48` |
| 형태 | 카드 18dp · 다이얼로그 28dp · 칩 9dp · 버튼 full |
| 간격 | 8dp 그리드 (8/12/16/24) |
| 타입 | Roboto(시스템) · 숫자 tabular + wdth 92 |
| 모션 | 강조 500ms `cubic-bezier(.2,0,0,1)`, 상태 점 2.2s pulse |

전체 토큰 표는 `docs/mockups/redesign-v059/README.md` §토큰.

## 4. 구현 단계

### Phase 0 — 합의 (이번 PR)

- 목업 2종 + 토큰 표 + 이 PLAN. **코드 변경 없음.**
- 사용자 확인 후 Phase 1 진행.

### Phase 1 — 토큰/테마 (android 먼저)

- `ui/theme/Theme.kt`: LightScheme/DarkScheme 을 위 표로 교체, `Typography` 신설(Roboto tabular),
  `Shapes` 18dp medium/large 조정.
- `dynamicColor` 기본값을 **false** 로 (브랜드 유지). 설정 스위치는 유지.
- 검증: `test android` 그린 + 스크린샷 6장(라이트/다크).

### Phase 2 — 안드로이드 화면

| 파일 | 변경 |
|---|---|
| `MainActivity.kt` | 내비 6→5, 통계·설정 보조 화면 전환(뒤로가기), 탭 인덱스 매핑 |
| `StatusScreen.kt` | 히어로 서버 카드 + 처리량 카드(스파크라인) + 저장공간 + 최근 완료 |
| `DownloadsScreen.kt` | 필터 칩, 진행률/속도/남은시간 위계, FAB |
| `TorrentScreen.kt` | magnet 입력 + 3분할 요약 + 상태 칩 |
| `FilesScreen.kt` | 저장공간 헤더, 경로 칩, 행 카드, 휴지통 진입 |
| `StatsScreen.kt` | 히어로 수치 + 30일 막대 + 하이라이트 |
| `SettingsScreen.kt` | 그룹 카드, 디자인 그룹(테마 세그먼트·동적 색상) |

- 기존 상태/이벤트 배선은 그대로. **Compose 시그니처를 바꾸지 않는 것**을 원칙으로 한다.

### Phase 3 — 웹 대시보드

- `WebDashboardHead`(CSS): `:root` 라이트 + `[data-theme=dark]`/`prefers-color-scheme` 다크 토큰,
  기존 3테마 변수(`--bg`,`--surface`,`--accent` 등)를 M3 역할 변수로 치환.
- `WebDashboardBody`: 상단 탭 → 레일 마크업. **기존 `id`·`onclick`·`panel-*` 구조 유지**.
- `WebDashboardScript`: `setTheme('light'|'dark'|'system')` + localStorage. 기존 `setTheme(toxic|midnight|navy)` 호출부 교체.
- 계약 테스트: 문자열 검사가 깨지면 **테스트를 먼저** 새 구조에 맞게 갱신(핸들러/ID 계약은 유지).
- 반응형: 600px 미만 레일→하단 내비, 우측 레일→접이식.

### Phase 4 — 검증/문서

- `./build_and_run.sh test android` (412건 기준선 → 신규 계약 테스트 포함), `lint android`.
- `test macos` — 토큰 공유 외 변경 없음, 회귀 확인.
- 실기: S22 라이트/다크 스크린샷, 웹 Chrome 라이트/다크 + 390px 폭.
- CHANGELOG v0.59, TODO 상태 갱신, 세션 로그.

## 5. 위험 / 대응

| 위험 | 대응 |
|---|---|
| 웹 계약 테스트 대량 실패 | Phase 3 착수 전 `grep` 으로 검사 문자열 전수 목록화 → 테스트 먼저 수정 |
| 6→5 내비로 기존 딥링크/위젯 탭 인덱스 깨짐 | `pendingOpenTab` 매핑 테이블 + 기존 인덱스 수용(테스트) |
| 동적 색상 ON 시 대비/브랜드 붕괴 | 기본 OFF, ON 일 때 tertiary·error 의미 고정 |
| 웹폰트/아이콘 CDN 의존 | 인라인 SVG + 시스템 폰트만. 오프라인 LAN 전제 |
| 목업과 구현 드리프트 | 토큰은 Theme.kt/CSS 양쪽에 같은 hex, README 표를 단일 출처로 |

## 6. 롤백

- 착수 전 태그 `pre-redesign-v059`.
- Phase 1~3 을 커밋 단위로 분리(토큰 / 앱 화면 / 웹) → 문제 시 `git revert` 로 부분 롤백.
- 웹은 `WebAssets.dashboardHtml` 이 계약이므로, 되돌릴 때 **Head/Body/Script 3파일을 함께** 되돌린다.
