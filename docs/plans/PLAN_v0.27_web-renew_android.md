# PLAN v0.27 — 웹 대시보드 리뉴얼 (android)

> 다크 3종 + 네온 사이버펑크 + 새로고침. 기본 toxic, 선택 헤더 우측, .sub 문구 삭제, 모바일 필수.

## 목표
1. 첫 화면 헤더에 새로고침(버튼+R키) — 탭 상태 유지 전체 재조회
2. CSS 변수 토큰화 + 다크 3테마 (toxic 기본 / midnight / navy)
3. 네온 리뉴얼 + 모바일 대응

## 범위
- `WebAssets.kt` 대시보드만 (디버그 페이지는 제외 — navy 고정, 도구 화면)
- 색상 19종 → var 19종 치환 (JS 인라인 스타일 포함, `var()` 유효)
- `body[data-theme]` + `localStorage(dr_theme)` (서버 저장 없음)
- `.sub` 삭제, `.hd` 헤더 신설 (h1 + ⟳ + 테마 select)
- `.fill` 그라디언트 플로우 애니메이션, midnight/toxic 글로우 (모바일은 off)
- 44px 터치 타겟, 640px 브레이크포인트 확장
- 테스트: `node --check` + 실기기 스크린샷 3테마 × PC/모바일 + 게이트 3종
- 문서: TODO T-992~T-997, CHANGELOG 0.27.0(미배포), 에러코드 없음

## 비범위
- 라이트 테마, 서버 저장 테마, 앱(Compose) 테마 변경
- SSE/폴링 주기 변경 (성능 영향 0)
- 디버그 페이지 디자인

## 토큰표 (navy 값 = 현행 유지)
`--bg #0A1428 --surface #101E3A/#0D1830/#181F2E --surface2 #12203D/#1A2540 --track #1B2B4D --line #22345A/#22335A --line2 #2A3B5C --text #E6EEF8/#E8F0FF/#E0E6F0/#C7D4F0/#E3E8EF --muted #8FA3BF/#9FB4D4 --dim #55688C/#66788C/#A0AABB --accent #2F80ED --on-accent(신규) --accent2 #8FD8FF --accentbg #123A63(신규) --ok #69E29B --okbg #12402F --err #FF8A93 --danger #40191C --warn #FFD59E/#B36B00 --sel #122A4D(신규)`
유지(테마 중립): `#22335433 #1A5C3A #3A3312 #333 #0A3A2F #6FE3C4 #6a8 #f86 #000 rgba()`

## 테마값
- toxic(기본, :root): bg#070B07 surface#0C130C surface2#131C13 track#1C2A1C line#223A22 line2#2E5230 text#E9FFE9 muted#93B795 dim#4E6B50 accent#39FF14 on-accent#061206 accent2#00E5FF accentbg#0F3311 ok#39FF14 okbg#0E2F12 err#FF5470 danger#3A0F18 warn#FFE14D sel#122A12
- midnight: bg#050508 surface#0D0D14 surface2#15151F track#1E1E2A line#26263A line2#34345A text#F2EDFF muted#9D94C0 dim#5E5578 accent#B537F2 on-accent#FFFFFF accent2#E879F9 accentbg#2A1245 ok#4ADE80 okbg#0B2E1D err#FB7185 danger#3A0F16 warn#FBBF24 sel#1D1030
- navy: 현행값 그대로 + on-accent#FFFFFF accentbg#123A63 sel#122A4D

## 검증 게이트
1. 치환 잔여 검사 (`#[0-9A-Fa-f]{6}` 중 매핑 외만 남는지)
2. `node --check` 2블록 + ktlint + test + assembleDebug
3. 실기기 agent-browser 스크린샷 3테마 × 390px/1280px + 새로고침/R키 동작
