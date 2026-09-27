# PLAN_v0.41_info-stats-menu_android.md
> 생성일: 2026-09-27 | 플랫폼: android (웹 대시보드) | 작성자: opencode
> 전제: v0.40.0 종합 안정성 4단계 완료 (대시보드 유휴 부하 0, SSE 상태 서명 tick)

## 1. 목표 (1줄)
웹 대시보드의 상시 노출 **서버 설명 바**(`.info`)와 **📊 통계 탭**을 헤더 오른쪽 **아이콘 1개(📊) + 드롭다운**으로 통합해 헤더 아래 1줄을 회수하고, 닫힘 상태에서는 통계 API 요청이 0건이 되게 한다.

## 2. 범위
- 플랫폼: android — 내장 웹 대시보드 `WebDashboardHtml.kt` 단일 파일 (2,845줄)
- 기술 스택: 순수 HTML/CSS/JS. **서버 코드·API 변경 없음**
  (`/api/info`, `/api/guard/status`, `/api/stats/summary|daily|extended` 전부 기존 재사용)
- design_profile: native (기존 대시보드 토큰 `--bg/--surface/--accent` 그대로)
- 버전: 0.41.0 (versionCode 32)
- 비범위: 앱 `StatsScreen.kt`(Compose) 미변경 · 서버 부하 모델 미변경 · 설정 탭 미변경

## 3. 문서 위치
- PLAN: 본 문서
- TODO: `docs/TODO.md` T-1064~T-1067

## 4. 변경 상세
### 4-1. 구조
| Before | After |
|---|---|
| 헤더 `.hd-acts` = `⟳` + `테마▾` | `.hd-acts` = `📊(배지)` + `⟳` + `테마▾` |
| L275 `.info` 상시 노출 바 | 삭제 → `#infoMenu` 내부 섹션 |
| 5탭 (`stats` 포함) | 4탭 (`stats` 제거) |
| L849 `#panel-stats` 전용 패널 | 삭제 → `#infoMenu` 내부 섹션 |

### 4-2. 드롭다운 사양
- 폭 `min(520px, 100vw-24px)` · 높이 `min(78vh, 720px)` 내부 세로 스크롤
- 우측 정렬(`right:0`), `z-index:60`, `overscroll-behavior:contain`(모바일 스크롤 연동 방지)
- 섹션 2개: `📡 서버 상태`(기존 `#info` DOM 그대로) / `📊 트래픽 통계`(기존 통계 DOM id 5종 그대로)
- 닫기: 버튼 토글 / 외부 클릭 / `Esc` / 탭 전환
- 상태 배지: 버튼 우상단 7px 도트 — 활성>0 녹색, 스로틀링 적색, 유휴 숨김
- 단축키: `S` 토글 (기존 `R` 새로고침과 동일 패턴, 입력 요소focus 시 무시)

### 4-3. 부하 계약 (v0.40 Phase 2 유예)
| 상태 | Before | After |
|---|---|---|
| 통계 탭 활성 | SSE beat 마다 통계 API **3건** | — (탭 없음) |
| 다른 탭 | 0건 | 0건 |
| 드롭다운 열림 | — | beat 마다 3건 (15초 스로틀 적용) |
| 드롭다운 닫힘 | — | **0건** |

`refresh()` 의 `curTab==='stats'` → `__infoMenuOpen && Date.now()-__statsAt>15000`

## 5. 성능 예산
- `updateInfoBar()` 문자열 캐시 가드(`__infoHtml`) 도입 — 동일 HTML이면 `innerHTML` 쓰기 스킵
  (드롭다운이 `hidden` 상태일 때의 유휴 DOM 쓰기 제거, Phase 2 계열)
- 새로 추가되는 폴링·타이머 없음. 통계 렌더 함수(`refreshStats`/`renderStats*`) 무수정.

## 6. 회귀 리스크
| 리스크 | 대응 |
|---|---|
| `.info` **클래스**가 비디오 진행 박스 6곳에서 재사용 | 클래스 CSS 정의 유지, `id="info"` 요소만 이동 |
| `switchTab` 탭 인덱스 매핑 | 5→4 배열 축소, 4탭 인덱스 불변 |
| 30일 차트 `svg{min-width:520px}` | 드롭다운 내 `420px` override + `overflow-x:auto` 유지 |
| T-1042 통계 3열 깨짐 재발 | 드롭다운 내 `auto-fit minmax(150px,1fr)` — 폭에 따라 2~3열 자동 |
| `DashboardRealtimeContractTest` 9건 | 대상 구간(`__pollTimer` ~ `pagehide` IIFE) 무수정 |

## 7. 빌드 & 검증 계획
- `./build_and_run.sh test android` → 신규 `DashboardInfoMenuContractTest` 10건 + 기존 222건 GREEN
- `node scripts/verify_dashboard_info_menu.js` → 드롭다운 블록 실제 실행 검증 (기존 `verify_dashboard_realtime.js` 기법)
- `./build_and_run.sh debug android` → 실기기(무선 adb) 설치
  - 4탭 / 드롭다운 열림 / 외부 클릭·Esc·탭 클릭 닫힘 / 배지 색상 / 390px 모바일 폭
  - **닫힘 상태 60초 관찰 → 서버 로그 `/api/stats/*` 0건** (부하 개선 실증)

## 8. DoD
- [ ] 탭 4개, 통계 탭·`#panel-stats` 완전 제거
- [ ] 드롭다운에 서버 상태 + 통계 전체 표시, 차트 가로스크롤 동작
- [ ] 외부 클릭 / `Esc` / `S` / 탭 클릭 4종 닫힘 동작
- [ ] 닫힘 상태에서 `/api/stats/*` 요청 0건
- [ ] 단위테스트 232건 GREEN (222 + 신규 10), `verify_dashboard_info_menu.js` 전 항목 통과
- [ ] `assembleDebug` 성공 + 실기기 설치
