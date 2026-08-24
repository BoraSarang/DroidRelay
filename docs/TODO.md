# TODO — DroidRelay v0.1

| 항목 | 내용 | 상태 |
|------|------|------|
| T-001 | Gradle 스캐폴드·매니페스트 권한·아이콘(적응형+테마) | ✅ 완료 |
| T-002 | JobsRepository + DownloadEngine (Range 이어받기, 동시 2개, 재시도 3회) | ✅ 완료 |
| T-003 | Ktor 서버: 웹 대시보드 + API + Range 파일 서빙(206) | ✅ 완료 |
| T-004 | RelayService 포그라운드(dataSync) + WakeLock | ✅ 완료 |
| T-005 | Compose MD3 UI (주소·QR 카드, 목록, URL 추가) | ✅ 완료 |
| T-006 | 완료/실패 시스템 알림 | ✅ 완료 |
| T-007 | 단위 테스트 18개(RangeParser 10 + JobsRepository 8) 전부 통과 + ktlint 통합·위반 0 | ✅ 완료 |
| T-008 | 실전 검증: 맥 curl → 폰 LTE 다운로드 → 206 이어받기 → 공용 Downloads 게시 | ✅ 완료 |

## 후속 후보 (v0.2 아이디어)
- [ ] Content-Disposition 헤더 기반 파일명 우선 적용
- [ ] 다운로드 재시작 시 앱 재시작해도 .part 자동 재개 목록 복원
- [ ] 서버 포트 변경 설정 / 다크·라이트 테마 대응
- [ ] iPad Safari 실기기 검증
