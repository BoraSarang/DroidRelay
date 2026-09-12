# 세션 2026-09-12 (commit, Phase A~E + v0.27 6커밋 분리)

## 무엇을
- 5상+리뉴얼 작업분을 1커밋 1관심사 6커밋으로 분리해 `chore/android-motrix-borrowing`에 적재 후 main fast-forward + push
- 커밋: 15a3581(A) / e40ebe5(B) / f2ac515(C) / b4ceb84(D) / 2f80a0a(E) / W(v0.27, 후속)
- T-999(모바일 1줄 입력)는 W에 포함

## 방법·삽질
- hunk 자동분류 스크립트(/tmp/genpatch.py, /tmp/genpatch2.py)로 초안 분리 후 mixed는 수동 분리
- 교훈 4종 (재발 방지):
  1. `git apply --recount`는 정상 패치도 파괴 — 사용 금지, plain apply만
  2. 패치 파일 끝 개행 필수 (없으면 corrupt)
  3. hunk body의 빈 줄은 빈 컨텍스트로 해석 — 의도치 않은 빈 줄 금지, 카운트 정확히
  4. `git add -A`는 stray 변경을 쓸어담음 — 커밋마다 foreign 키워드 스캔 + 빌드로 검증
  5. auto 패치는 재생성 시점에 주의 (빈 diff로 덮어쓰기事故 — 백업 필수)
- 중간 검증: 커밋마다 unit 전체 + ktlint + foreign 스캔, E2E는 세션 시 완료분으로 갈음
- B 커밋 오염 2줄(Keys) 발견 → amend 제거, 이후 매 커밋 foreign 스캔 정착

## 결과
- main fast-forward, GitHub push 완료 (후속 명령 참조)
- 브랜치·stash 정리 (후속 명령 참조)
