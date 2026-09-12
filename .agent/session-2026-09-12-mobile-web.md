# 세션 2026-09-12 (android+web, T-1000~T-1002)

## 무엇을
- T-1000: 앱 주소 클릭 → 브라우저 열기 (서버 카드+설정 URL, `ui/OpenBrowser.kt` 신규)
- T-1001: Safari 테마 select 깨짐 수정 (appearance 리셋+SVG 셰브론)
- T-1002: 모바일 390px 설정 서브페이지 깨짐 수정 (다운로드/토렌트/가드/스케줄 등 세로 적층)

## 검증
- headless Chrome 390px 실렌더: 설정 전 섹션(전역~복원) 깨짐 없음 확인
- ktlint 본문 GREEN (build.gradle.kts 파서 기존 이슈 제외)
- assembleDebug + adb 재설치 GREEN (22:55:45, dex 내 수정 문자열 확인)

## 문서갱신
- PLAN_v0.28/v0.29/v0.30, TODO T-1000~T-1002 ✅, CHANGELOG 0.28.0(미배포)
- error_message_ko.json 변경 없음 (신설 에러코드 없음)

## 남은 일
- 사용자 실기기 확인: 사파리 테마 select + 390px 설정 페이지 + 주소 클릭
