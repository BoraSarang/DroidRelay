# 세션 2026-09-13 (android, T-1004 파일명 우선)

## 무엇을
- 서명 URL 파일명 오저장 수정 (Godot tpz → file-ts... 폴백 버그)
- 쿼리 response-content-disposition + 일반 filename 쿼리 우선, 응답 Content-Disposition 헤더 1회 교정

## 플랫폼
- android (Kotlin, UI 변경 없음)

## 빌드+PERF+CACHE
- testDebugUnitTest 전체 GREEN (JobsRepository 12건 + ContentDisposition 7건 포함)
- ktlint 본문 GREEN (kts 파서 기존 이슈 제외)
- assembleDebug + 실기기 설치 성공 (Success)
- 전송 경로 무변경 → PERF/CACHE 예산 영향 없음

## 남은TODO
- 사용자 실기기 확인 (문제 URL 등록 → tpz 이름 + 완료 검증)
- full/E2E full은 사용자 허락 후 (현재 미실행)

## 전달로그
- [FEATURE] Content-Disposition 교정 id=... 'old' → 'new' (폴백일 때만)
- 파일명 결정(쿼리 우선/URL 그대로/범용 폴백) DebugLogger.d

## 문서갱신
- PLAN_v0.32 신규, TODO T-1004 ✅, CHANGELOG 0.32.0(미배포)
- error_message_ko.json 변경 없음 (신규 에러코드 없음)

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리

## E2E
- 실기기 E2E는 사용자 직접 진행 예정
