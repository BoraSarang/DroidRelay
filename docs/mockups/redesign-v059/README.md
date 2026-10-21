# DroidRelay 리디자인 목업 — v0.59 "Relay Console"

디자인 합의용 목업입니다. 구현 코드가 아니라 **토큰·구조·화면**을 확정하기 위한 문서입니다.

- `android.html` — 안드로이드 6화면 × 라이트/다크 (390×844)
- `web.html` — 웹 대시보드 데스크톱 라이트/다크 + 좁은 화면(390) + 토큰/타입 표

## 보는 방법

```bash
# 그냥 열어도 되고(폰트는 CDN), 로컬 서버로 열면 더 안정적
open docs/mockups/redesign-v059/android.html
open docs/mockups/redesign-v059/web.html
```

> 목업은 Google Fonts(Roboto Flex)를 불러옵니다. **실제 앱/대시보드는 외부 CDN 을 쓰지 않습니다** —
> 안드로이드는 시스템 Roboto, 웹은 시스템 폰트 스택으로 대체합니다(핫스팟 오프라인 전제).

## 방향

**"Relay Console"** — 다운로드 릴레이를 계측기(instrument)처럼 보여준다.

- **시드**: 딥 파인 `#006B56` (신호·중계). 다크에서는 민트 `#62DCBE` 로 밝아진다.
- **숫자 우선**: 처리량·진행률·남은 시간은 `tabular-nums` + `wdth 92` 로 고정폭. 값이 바뀌어도 폭이 흔들리지 않는다.
- **톤으로 깊이**: 그림자 대신 surface-container 계층. 외곽선은 `outline-variant`, 카드 안쪽 구분선은 hairline.
- **8dp 그리드**: 간격 8 / 12 / 16 / 24, 카드 18dp, 다이얼로그 28dp, 버튼 full.
- **모션**: 강조 500ms `cubic-bezier(.2,0,0,1)`, 상태 점은 2.2s pulse (살아 있음을 보여주는 유일한 상시 애니메이션).

## 토큰 (Light / Dark)

Material 3 역할 이름 그대로 사용합니다. Compose 는 `colorScheme`, 웹은 `--md-sys-color-*` 에 매핑.

| 역할 | Light | Dark |
|---|---|---|
| primary | `#006B56` | `#62DCBE` |
| on-primary | `#FFFFFF` | `#00382D` |
| primary-container | `#7FF8D8` | `#005141` |
| on-primary-container | `#00201A` | `#80F8D9` |
| secondary | `#4A635C` | `#B1CCC3` |
| secondary-container | `#CCE8DF` | `#334B44` |
| on-secondary-container | `#06201A` | `#CCE8DF` |
| tertiary | `#7C5800` | `#F5BF48` |
| tertiary-container | `#FFDF9E` | `#5D4200` |
| error | `#BA1A1A` | `#FFB4AB` |
| error-container | `#FFDAD6` | `#93000A` |
| surface | `#F6FBF7` | `#0E1513` |
| on-surface | `#171D1A` | `#DDE4DF` |
| surface-container-lowest | `#FFFFFF` | `#090F0D` |
| surface-container-low | `#F0F5F0` | `#171D1B` |
| surface-container | `#EAEFEA` | `#1B211F` |
| surface-container-high | `#E4EAE4` | `#252B29` |
| surface-container-highest | `#DEE4DE` | `#303634` |
| on-surface-variant | `#3F4945` | `#BFC9C3` |
| outline | `#6F7974` | `#89938E` |
| outline-variant | `#BFC9C3` | `#3F4945` |
| inverse-surface | `#2B322E` | `#DDE4DF` |
| inverse-primary | `#62DCBE` | `#006B56` |

- **동적 색상(Material You)** 은 설정에서 켤 수 있게 유지하되 **기본은 끔** — 브랜드 시드가 화면마다 바뀌면 계측기 느낌이 사라진다.
- 대비는 라이트 `primary` on `surface` 5.9:1, 다크 `primary` on `surface` 11.2:1 (본문 4.5:1 충족).

## 타이포

| 역할 | 크기/무게 | 용도 |
|---|---|---|
| display | 34–42 / 700 (tabular) | 처리량 큰 숫자 |
| headline-small | 26 / 700 | 화면 제목("릴레이") |
| title-medium | 15–16 / 650 | 카드 제목, 파일명 |
| body-medium | 13 / 400 | 보조 설명 |
| label-small | 11 / 650 + tracking .08em | 섹션 라벨(대문자) |

## 구조 변경

### 안드로이드

- 하단 내비 **6 → 5**: `홈 · 받기 · 토렌트 · 보관함 · 더보기`
  - M3 권장 최대 5개. **통계·설정은 "더보기"에서 진입**(상단 뒤로가기 있는 보조 화면).
- 홈(상태): 서버 카드(주소·QR·복사/공유) + 실시간 처리량 + 저장공간 + 최근 완료 1건.
- 받기: 필터 칩(전체/받는 중/대기/완료) · 진행률·속도·남은 시간 · FAB `URL 추가`.
- 토렌트: magnet 입력 + 3분할 요약(↓/↑/시드·피어) + 상태 칩(배포 중/시더 찾는 중).
- 보관함: 저장공간 → 경로 칩 → 폴더/파일 행. 숨김 디렉터리(`.trash` 등)는 계속 비노출.
- 통계: 이번 달 전송량 히어로 + 30일 막대 + 하이라이트 4개 + 월 예상.
- 설정: 기기 카드 + 서버/다운로드/디자인/고급 그룹. **디자인 그룹에 시스템·라이트·다크 세그먼트와 동적 색상 스위치**.

### 웹 대시보드

- 상단 탭 4개 → **좌측 레일**(다운로드/토렌트/보관함/통계/설정) + **우측 상태 레일**(서버·저장공간·가드).
- 600px 미만: 레일 → 하단 내비, 우측 레일 → 접이식 카드.
- 현재 3테마(Toxic/Midnight/Navy) → **라이트/다크 + 시스템 추종**으로 통합. 선택은 localStorage 보존.
- 목록은 표가 아니라 **행 카드**: hover 시 surface-container, 폴더는 tertiary-container.

## 구현 시 지켜야 할 제약

1. **문자열 계약 테스트**: `WebAssets.dashboardHtml` 은 공개 계약이고 `DashboardRangeDownloaderContractTest`·
   `DashboardInlineHandlerContractTest` 가 마크업/JS 문자열을 검사한다. **ID·핸들러 이름·인라인 패턴은 유지**하고
   CSS/구조만 바꾼다. 바꿔야 하면 테스트를 먼저 갱신한다.
2. **오프라인**: 대시보드는 폰 LTE/핫스팟에서 열린다. 웹폰트 CDN·아이콘 폰트 금지. 아이콘은 인라인 SVG.
3. **파일 크기**: `WebDashboardHead`(CSS) / `Body` / `Script` 분리 구조를 유지하고, CSS 는 Head 쪽에만 둔다.
4. **접근성**: 터치 타깃 48dp, 텍스트 4.5:1, 아이콘 단독 버튼에 `contentDescription`/`aria-label`.
5. **동적 색상**: 켰을 때도 primary 계열만 바뀌고 tertiary(업로드)·error 의미는 유지.

## 아직 정하지 않은 것

- 아이콘 세트: 현재 Material Icons Filled 유지 vs 커스텀 라인 아이콘(목업은 라인). → **Material Symbols Rounded** 권장(번들 가능).
- 통계 화면의 기간 필터(7일/30일/전체) 위치.
- 웹 "폴더 ZIP"·"공유 링크"는 기존 T-1128b/c 범위와 함께 결정.
