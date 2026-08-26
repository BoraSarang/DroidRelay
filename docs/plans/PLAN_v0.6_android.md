# PLAN v0.6 — 후속 UX 개선 (2026-08-26)

## 개요
v0.5 이후 TODO "후속 후보" 4건을 모두 구현한다. 웹 대시보드 중심이며 Android 서버 API 확장 포함.

## T-번호 작업 목록

| T-번호 | 내용 | 플랫폼 |
|--------|------|--------|
| T-701 | 웹 SSE 실시간 푸시 (`/api/events` text/event-stream, tick 수신 시 refresh, 실패 시 폴링 폴백) | android(server) + web |
| T-702 | 다중 URL 일괄 붙여넣기 (공백/줄바꿈 분할 → 순차 POST, 결과 집계 토스트) | web (+맥 호환 유지) |
| T-703 | 보관함 휴지통 (`.trash/` 이동 삭제 + 복구 + 영구삭제 + 비우기 API/웹 UI) | android(server) + web |
| T-704 | 체크섬 검증 (SHA-256 선택 입력, 완료 시 스트리밍 digest 비교, 불일치 시 FAILED E-AND-DOWN-1004) | android |

## 결정 사항
- **SSE**: Ktor `respondBytesWriter` + `ContentType.Text.EventStream`, 1초 tick. 웹은 EventSource onmessage → refresh(), onerror 시 기존 1초 폴링 유지(폴백). SSE 연결 성공 시 폴링을 10초로 완화.
- **휴지통**: `/sdcard/Download/DroidRelay/.trash/`. 원본 경로 메타데이터 저장 생략 — 복원 시 보관함 루트로 이동(단순화). 이름 충돌 시 `-2` 접미사. 웹 보관함 목록에서 `.trash` 항목 숨김.
- **체크섬**: Job에 `expectedSha256`(nullable), `verified` 필드 추가. jobs.json 하위호환(opt). 검증은 rename 후 done 파일로 수행, 불일치 시 파일 삭제 + FAILED. 맥 앱은 sha256 미전송(API 호환).
- **다중 URL**: 서버 단일 add API 재사용, 웹 JS에서 분할·순차 호출.

## 구현 순서
1. T-704 Job 모델 + persistence + 엔진 검증 + API 파싱
2. T-702 웹 add() 다중 URL
3. T-703 서버 휴지통 API 3종 + delete 변경 + 웹 UI
4. T-701 서버 /api/events + 웹 EventSource
5. 빌드(android/macos) → 세션 로그

## 테스트 계획
- TC-701: curl -N /api/events → 1초 간격 tick 확인
- TC-702: 줄바꿈 3개 URL 붙여넣기 → 3건 추가 토스트
- TC-703: 삭제 → .trash 이동 확인, 복구 → 루트 복원, 비우기
- TC-704: 정확한 SHA-256 → ✓검증 배지, 틀린 해시 → FAILED 체크섬 불일치

## 롤백 계획
git revert 단위 커밋. jobs.json 신규 필드는 opt라 구버전 무시 가능.

## 에러코드
- E-AND-DOWN-1005: 체크섬 불일치 (error_message_ko.json 추가, 1004는 포트 에러로 기존 사용 중)
