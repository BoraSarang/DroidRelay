# PLAN v0.30 — 모바일 390px 설정 탭 깨짐 수정 (android/web)

> 모바일 웹 390px에서 설정 탭이 많이 깨짐 (사용자 제보).

## 원인 (정적 마크업 감사)
1. `.fp{display:flex;gap:8px}`에 `flex-wrap` 없음 — 검색 URL+API키+저장, Debrid 키+저장, 저장경로+테스트 행이 390px에서 가로 넘침
2. 토렌트 고급 `.ck` 3체크박스 한 줄 (`gap:20px`, wrap 없음) → 넘침
3. `.sv input[type=range]{flex:1}`에 `min-width:0` 없음 → flex 최소폭(auto)으로 슬라이더 행 넘침
4. 토렌트 상세 `.modal-stat` 3열 그리드가 390px에서 빡빡함

## 목표
- 640px 이하 미디어쿼리에만 추가 (데스크톱 불변):
  - `.fp{flex-wrap:wrap}` + `.fp input[type=text]{flex:1 1 100%;min-width:0}` (행별 세로 적층)
  - `.ck{flex-wrap:wrap}`
  - `.sv input[type=range]{min-width:0}`
  - `.modal-stat{grid-template-columns:repeat(2,1fr)}`
- 가로 스크롤 0 (390px 실렌더 확인)

## 비범위
- 데스크톱 레이아웃 변경 없음
- HTML/JS 구조 변경 없음 (CSS만)
- 설정 기능 동작 변경 없음

## 검증 게이트
1. 정적 HTML 추출 → headless Chrome 390px 스크린샷 (설정 탭) 육안 확인
2. `ktlintCheck` 본문 GREEN + `assembleDebug` GREEN + 재설치
3. 사용자 실기기(사파리 390px) 확인
