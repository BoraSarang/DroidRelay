# PLAN_v0.36_server-control_android.md
> 생성일: 2026-09-21 | 플랫폼: android | 작성자: opencode

## 1. 목표 (1줄)
재부팅/앱실행 자동시작 분리 + HTTPS 개별 ON/OFF + 홈 ServerCard 시작/정지 버튼으로 서버 제어를 완성한다.

## 2. 범위
- 플랫폼: android (Kotlin + Compose + Ktor Netty)
- 기술 스택: DataStore Preferences + Ktor 이중 커넥터 + Compose + 기존 ServerToggle 재사용. 선정 이유: 기존 구조 최소 변경, 오프라인(핫스팟) 무의존.
- design_profile: native
- 버전: 0.36.0 (versionCode 28)

## 3. 문서 위치
- PLAN: 본 문서
- TODO: docs/TODO.md T-1020~T-1025 등록
- DESIGN: 해당 없음 (네이티브 MD3 유지)
- API: /api/info + /api/settings/server 확장 (httpsEnabled, boot/launch 상태 노출은 미포함 — 설정값만)

## 4. 성능 예산
- budgets.json 참조, override 없음. 설정 배선+버튼만이라 cold_start·메모리·캐시 영향 없음.
- 테스트 예산: smoke+unit만 (full/E2E full은 사용자 허락 후).

## 5. 에러 코드
- 신규 없음. 기존 E-AND-SRV-0111(포트 충돌) 재사용. HTTPS OFF 시 충돌 검사는 HTTP 단일 기준으로 완화.

## 6. 빌드 & 검증 계획
- ./build_and_run.sh test android (unit) → ktlint 본문 → assembleDebug → 실기기 설치
- 단위테스트 신규 10건: 마이그레이션(구 auto_start→신규 2종, https 기본 true) 4 + validPorts 완화 2 + effectiveHttpsPort 1 + ServerState 표시 문구 헬퍼 3
- DebugPanel 검증: ERROR 0, [FEATURE] 부팅/앱실행/HTTPS/시작·정지 로그 확인
- 실기기 E2E (사용자 직접): 토글 OFF→재부팅 미기동, ServerCard 정지/시작, HTTPS OFF→8443 거부+8080 200, HTTP QR/복사 1줄

## 7. 예외 규칙 (있으면)
- 없음. [HARD] 준수: main 직접 push 금지 → feat 브랜치, 시크릿 없음, 포커스 스틸 금지.
