# 세션 2026-09-12 (web, T-1003 접이식)

## 무엇을
- 비디오 분석 + 토렌트 검색 접이식 (기본 접힘, localStorage 기억)
- 검색 OFF 시 토렌트 검색 섹션 숨김 (로드/탭진입/저장후)
- 모바일 행 넘침 수정 (인라인 flex 정리, 버튼 균등 분할)

## 검증
- node --check OK, theme-tokens --dry (write 금지 — navy 정의부 순환참조 오염 1건 발생→원복)
- ktlint 본문 GREEN (kts 파서 기존 이슈 제외)
- headless 390 실폭 렌더 (내용폭 366 고정법): 기본접힘·펼침·행 넘침 0 확인
- assembleDebug + 재설치 GREEN (23:41:29, dex 식별자 8건)

## 문서갱신
- PLAN_v0.31, TODO T-1003 ✅, CHANGELOG 0.29.0(미배포)
- error_message_ko.json 변경 없음

## 남은 일
- 사용자 실기기 확인 (접힘 기본값·상태 기억·검색OFF 숨김)
- theme-tokens.py write 모드는 navy/toxic/midnight 정의부 오염 → --dry만 사용 (스크립트 개선은 별도 T-번호 필요)

## 핫픽스 (23:59, 동일 T-1003)
- 증상: `toggleColl is not defined` + 검색OFF인데 검색 섹션 표시
- 원인: 접이식 블록을 `applyTheme();` 단독 문자열 기준으로 삽입했더니 `setTheme()` 내부 호출(1680행)에 들어가서 함수들이 지역 스코프에 갇힘. 로드 시 `refreshSearchVisibility()`도 미실행
- 수정: `setTheme()` 닫기 복원 + stray `}` 제거 → 전역 정의 확인(node 스텁 11assert ALL PASS + headless typeof function)
- 교훈: edit oldString은 함수 시그니처 포함 충분한 문맥으로 (단독 호출문 앵커 금지)
- 재설치 GREEN (23:59:09)
