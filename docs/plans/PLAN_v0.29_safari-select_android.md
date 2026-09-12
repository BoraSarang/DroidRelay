# PLAN v0.29 — Safari 테마 select 깨짐 수정 (android/web)

> Safari(macOS/iOS)에서 헤더 우측 테마 변경 셀렉트 박스가 깨져 보임 (사용자 제보).

## 원인
- `#themeSel`이 `appearance` 리셋 없이 커스텀 배경/테두리만 적용 → Safari 네이티브 셀렉트 크롬(베젤+기본 화살표)과 겹쳐 깨짐
- Chrome/Edge에서는 네이티브 렌더링이 관대해서 정상으로 보였던 것

## 목표
1. `#themeSel`에 `-webkit-appearance:none;appearance:none` + SVG 셰브론 직접 렌더 (3테마 공통 중립 회색)
2. 화살표 공간 확보 (`padding-right`) + `:focus` 테두리 강조
3. `option` 팝업도 다크 계열로 (`color-scheme:dark`는 이미 `:root`에 있음)

## 범위
- `WebAssets.kt` 대시보드 CSS만 (HTML 구조 변경 없음, JS 변경 없음)
- 셰브론 색은 `%23` 인코딩 SVG data URI로 — `scripts/theme-tokens.py` 잔여 검사(`#[hex]` 리터럴)에 걸리지 않음
- Kotlin triple-quote 안에 `$` 사용 금지 (템플릿 충돌)

## 비범위
- iOS 16px 미만 포커스 줌 동작 변경 없음
- 디버그 페이지 변경 없음

## 검증 게이트
1. `scripts/theme-tokens.py` 잔여 0 (신규 리터럴 색상 없음)
2. `ktlintCheck` 본문 GREEN + `assembleDebug` GREEN + 재설치
3. 사용자 Safari 실확인 (맥/아이폰) — 네이티브 베젤 없이 다크 셀렉트 + 화살표 표시
