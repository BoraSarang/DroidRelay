# 세션 2026-09-21 (android, v0.36 서버 제어 분리 + HTTPS 개별)

## 무엇을
- 자동시작 분리 (bootAutoStart/launchAutoStart, 레거시 auto_start 폴백) + MainActivity 항상기동 버그 수정
- ServerCard 시작/정지 버튼 + HTTPS 끔 표시, 복사/공유 켜진 쪽만
- httpsEnabled (기본 true): OFF면 HTTP 단일, 리다이렉트 스킵, 충돌검사 완화
- API `/api/settings/server` GET/POST + `/api/info` httpsEnabled, 웹 가드 섹션 서버 제어 블록

## 플랫폼
- android (Kotlin+Compose+Ktor) + web (대시보드 가드 섹션)

## 빌드+PERF+CACHE
- testDebugUnitTest 139건 GREEN (ServerControlTest 10건 신규, failures 0)
- ktlint 본문 GREEN (kts 파서 기존 이슈만 — build.gradle.kts 미수정)
- assembleDebug + 실기기 설치 성공 (0.36.0/28 확인)
- 설정 배선+버튼만 → PERF/CACHE 예산 영향 없음

## 남은TODO
- 사용자 실기기 확인: 토글 OFF→재부팅 미기동, ServerCard 정지/시작, HTTPS OFF→8443 거부+8080 200, 웹 서버 설정 저장
- full/E2E full은 사용자 허락 후 (미실행)
- v0.37 트래픽 통계 (PLAN 미작성, 다음 작업)

## 전달로그
- [INFO] [FEATURE] 서버 시작 (ServerCard) / UI 서버 정지 (ServerCard)
- [INFO] [FEATURE] HTTPS 사용 설정 / HTTPS 끔 설정 — HTTP 단일 동작
- [INFO] [FEATURE] HTTPS 설정 변경 감지 {http}/{https}/{on} → … — 서버 재시작
- [INFO] [FEATURE] HTTPS 포트 기동 완료 http://… (+ https://… 또는 (HTTPS 끔))

## 문서갱신
- PLAN_v0.36 신규, TODO T-1020~T-1025 ✅, CHANGELOG 0.36.0(미배포)
- error_message_ko.json 변경 없음 (신규 에러코드 없음, E-AND-SRV-0111 재사용)

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리

## E2E
- 실기기 E2E는 사용자 직접 진행 예정
