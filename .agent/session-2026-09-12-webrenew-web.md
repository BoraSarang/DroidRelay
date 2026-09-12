# 세션 2026-09-12 (web, v0.27 리뉴얼)

## 무엇을
- 웹 대시보드 리뉴얼: 새로고침(⟳+R) + CSS 토큰 19종 + 다크 3테마 + 네온 + 모바일
- T-998 추가: 틱 리렌더 중 입력 보호 (사용자 제보 버그 — 실행 중 속도 변경 불가)
- 수정: WebAssets.kt만 + scripts/theme-tokens.py 신규 (+ docs)

## 검증
- 치환 249건 잔여 0, JS node --check 2블록 OK
- test/ktlint/assemble GREEN (Kotlin 변경 없음, WebAssets JS만)
- 실기기 스크린샷: toxic/midnight/navy PC + toxic 모바일(390px)
- T-998 E2E: 실행 중 작업 select 포커스 4초 유지 → 512KB/s 변경 적용 → 원복 256KB/s

## 문서갱신
- docs/plans/PLAN_v0.27_web-renew_android.md, TODO T-992~T-998, CHANGELOG 0.27.0
- error_message_ko.json 변경 없음

## 남은 일
- 5상 커밋 분리 작업 중단 상태 (브랜치 chore/android-motrix-borrowing, /tmp/patches) — v0.27 포함 여부 결정 필요
- 사용자 육안 확인 대기 (테마 취향)
