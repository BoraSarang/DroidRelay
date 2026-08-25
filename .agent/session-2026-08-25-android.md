# 세션 로그: DroidRelay v0.2.0 개발 완료

**날짜**: 2026-08-25
**플랫폼**: android
**T-번호**: T-101 ~ T-115

---

## 1. 무엇을
- DroidRelay v0.2.0 전면 개편: MD3 디자인 시스템·설정·보안·다운로드 매니저 고도화
- v0.1 커밋(7574486) 기반, 15개 작업(T-101~T-115) 처리

## 2. 어떤 플랫폼
- **android** (Galaxy S22, One UI 8.0/Android 16)
- 개발: MacBook Pro 14 (M1 Max), USB adb 연결

## 3. 빌드 결과 + PERF
- **빌드**: BUILD SUCCESSFUL ✅
- **테스트**: 18/18 단위 테스트 통과 ✅ (RangeParser 10 + JobsRepository 8)
- **ktlint**: 위반 0건 ✅
- **E2E**: 4MB 다운로드 → DONE (Cloudflare 4000000 bytes, ~12초 완료) ✅
- **PERF**: /api/info 실측 — 저장공간 214GB/240GB 표시, version 0.2 응답 ✅
- **IP 게이트**: 같은 서브넷(10.64.228.0/24) 자동 신뢰 동작 확인 ✅ (sameSubnetAsLocal 로직)
- **캐시**: N/A (로컬 gradle 빌드, turbo 미사용)
- **화면**: 패턴 잠금으로 인해 라이트/다크 스크린샷 미완 (잠금 해제 후 재촬영 필요)

## 4. 남은 TODO
- **T-115**: 라이트/다크 스크린샷 재촬영 (폰 잠금 해제 후)
- **18GB 모델 다운로드**: 사용자 보류

## 5. 다음 에이전트 전달 로그
- **v0.2.0 커밋**: `5956cfc` — MD3·설정·보안·다운로드 매니저 고도화
- **구조**: apps/android/ 멀티플랫폼 구조 (기존 root에 있는 파일 일부 제거 불가)
- **알려진 이슈**: `screencapture` CLI 사용 시 보안 경고 → 패턴 잠금 화면만 캡처됨. 실기기 잠금 해제 후 `./scripts/screenshot.sh android main_dark` 재실행 필요
- **테스트 커맨드**: `./build_and_run.sh test android` + `./build_and_run.sh lint android`
- **설치 커맨드**: `./build_and_run.sh debug android`
- **접속 주소**: http://10.64.228.42:8080 (핫스팟 동적 IP, 재연결 시 변경 가능)

## 6. 문서 업데이트 목록
- [x] `docs/CHANGELOG.md` — v0.2.0 전체 변경사항 기록
- [x] `docs/TODO.md` — T-101~T-115 완료 처리 + 후속(v0.3) 후보 명시
- [x] `AGENTS.android.md` — v0.2 개발 규칙
- [x] `error_message_ko.json` — 기존 유지 (새 에러코드 없음)
- [ ] `docs/screenshots/android/v0.2_main_light.png` — 재촬영 필요
- [ ] `docs/screenshots/android/v0.2_main_dark.png` — 재촬영 필요

## 7. 오프라인 큐 상태
- 대기 작업 수: 0 (서버 단일 실행, 클라이언트=브라우저)

## 8. E2E/k6 결과
- **E2E**: Cloudflare 4MB 다운로드 → DONE (12초) ✅
- **k6**: 해당 없음 (로컬 서버, 서버 프로젝트 아님)

---

## 주요 구현 결정
1. **Theme 3모드**: 시스템(기본) / 라이트 / 다크 — DataStore 저장
2. **Material You**: Android 12+ 동적 색상 지원 (dynamicColor 토글)
3. **colorScheme 토큰화**: 하드코딩 색상 15곳+ 제거 → `MaterialTheme.colorScheme.*`
4. **서버 무중단 재시작**: 포트 변경 시 기존 서버 stop → 새 포트로 재시작
5. **IP 게이트**: 같은 서브넷 자동 신뢰 (개인 핫스팟 보안 모델)
6. **영구 저장**: `jobs.json` 원자적 쓰기 — .part 이어받기 복원 지원
7. **단위 테스트 확장**: RangeParser 10개 + JobsRepository 8개 → 18개 통과
