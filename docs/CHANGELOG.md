# Changelog

## [0.2.0] - 2026-08-25

### Changed [android]
- **디자인 시스템 전면 개편 (material-3 감사 기반)**: 하드코딩 색상 15곳+ 제거 → `MaterialTheme.colorScheme` 토큰화
- **테마 3모드 지원**: 시스템 / 라이트 / 다크 (설정에서 선택, DataStore 저장)
- **Material You 동적 색상**: Android 12+ 배경화면 기반 색상 옵션 추가
- 라이트/다크 명암비 준수 — 서버 주소 등 안 보이던 텍스트 문제 해결
- 하단 NavigationBar 3탭 구조: 다운로드 / 파일 / 설정

### Added [android]
- 설정 화면: 포트 변경(유효성 검사+서버 무중단 재시작), 동시 다운로드 수(1~4), 속도 제한(스로틀), 웹 접속 암호(HTTP Basic), 알림 토글
- 다운로드 중 알림 진행바 + 실시간 속도(KB/s) 표시
- 작업 일시정지/재개 (.part 유지)
- 저장공간 여유/전체 표시 (앱 카드 + 웹 대시보드 `GET /api/info`)
- 작업 목록 영구 저장(jobs.json) — 앱 재시작 시 .part 이어받기 복원
- 완료 파일 탭: MediaStore 조회 + 공유 인텐트 + 삭제
- 클립보드 URL 감지 → 스낵바 추가 제안
- 신규 기기 접속 승인 게이트: 같은 핫스팟 서브넷 자동 신뢰, 타 서브넷은 알림 허용/거부

### Fixed [android]
- 단위 테스트에서 android.util.Log 미목 문제 (`isReturnDefaultValues`)

### Security [android]
- HTTP Basic 인증 옵션, IP 화이트리스트, 세션 차단 목록

## [0.1.0] - 2026-08-25
- 초판 (v0.1 상세는 PLAN_v0.1 및 커밋 7574486 참조)
