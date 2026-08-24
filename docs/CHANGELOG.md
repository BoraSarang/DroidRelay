# Changelog

## [0.1.0] - 2026-08-25

### Added [android]
- DroidRelay 첫 출시: LTE 릴레이 다운로드 서버 앱
- 내장 HTTP 서버(Ktor, :8080): 웹 대시보드(한국어) + 관리 API + 파일 서빙
- Range(206 Partial Content) 지원 — 끊긴 지점부터 이어받기
- OkHttp 다운로드 엔진: 동시 2개 큐, 자동 재시도 3회, .part 이어받기
- 포그라운드 서비스(dataSync) + WakeLock — 화면 꺼짐에도 유지
- 완료/실패 시스템 알림, MediaStore 게시(Downloads/DroidRelay)
- Compose MD3 UI: 접속 주소·QR 카드, 작업 목록·진행률
- 적응형 아이콘 + Android 13 테마 아이콘(monochrome)
- 단위 테스트 18개(RangeParser/JobsRepository), ktlint 통합

### Fixed [android]
- 범용 URL 파일명(`__down` 등) → `file-{호스트}-{시각}` 폴백 개선

### Perf [android]
- 저대역폭 LTE(~400KB/s) 환경 검증: 10MB 실전 다운로드 + 중단 재개 확인
