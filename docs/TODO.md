# TODO — DroidRelay

## v0.2 (2026-08-25)

| 항목 | 내용 | 상태 |
|------|------|------|
| T-101 | PLAN_v0.2 문서 + DataStore 1.1.7 의존성 | ✅ |
| T-102 | Theme.kt(시스템/라이트/다크+Material You) + 전면 토큰화 + themes.xml 정리 | ✅ |
| T-103 | SettingsRepository(DataStore) + 설정 화면 UI | ✅ |
| T-104 | 포트 변경 → 서버 무중단 재시작 + 1024~65535 검증 | ✅ |
| T-105 | 알림 진행바 + 실시간 속도(KB/s, EMA) | ✅ |
| T-106 | 저장공간 표시(StatFs): 앱 카드 + GET /api/info | ✅ |
| T-107 | jobs.json 영구저장 + 시작 시 .part 복원 | ✅ |
| T-108 | 일시정지(PAUSED)/재개 + 속도 계산 | ✅ |
| T-109 | NavigationBar 3탭(다운로드/파일/설정) + FilesScreen(공유·삭제) | ✅ |
| T-110 | 클립보드 URL 감지 SnackBar 제안 | ✅ |
| T-111 | 웹 Basic Auth (설정 on/off) | ✅ |
| T-112 | 신규 기기 IP 허가 게이트(같은 서브넷 자동 신뢰) | ✅ |
| T-113 | 속도 제한 스로틀(128KB/s 스텝, 0=무제한) | ✅ |
| T-114 | 단위 테스트 18개 회귀 통과 + ktlint 위반 0 | ✅ |
| T-115 | 라이트/다크 스크린샷 캡처 | ⏳ 폰 잠금 해제 후 재캡처 필요 |

## v0.1 아카이브
T-001~T-008 전부 완료 (커밋 7574486).

## 후속 후보 (v0.3)
- [ ] autoStart 토글 실동작 연결(현재 MVP는 항상 기동)
- [ ] Content-Disposition 파일명 우선
- [ ] 웹 대시보드 SSE 실시간 푸시
- [ ] 다중 URL 일괄 붙여넣기
- [ ] iPad Safari 실기기 검증
