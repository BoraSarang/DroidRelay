# PLAN v0.2 — DroidRelay 디자인 개편·기능 고도화

> 작성일: 2026-08-25 · 플랫폼: Android · 전제: v0.1 완료(커밋 7574486)
> 스킬: material-3 (AGENTS.md 13.3) 감사 기반 재설계

## 1. 목표

1. MD3 토큰 기반 디자인 시스템으로 개편 — 하드코딩 색상 제거, 시스템/라이트/다크 + Material You 대응
2. 다운로드 매니저 완성도: 설정·알림 진행바·저장공간·영구저장·일시정지·속도·파일탭
3. 보안 고도화: 웹 접속 암호(Basic)·IP 허가 팝업·속도 제한 (Transfer 654★ 벤치마크)

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 범위 | Tier A+B+C 풀패키지 (사용자 확정) |
| 동적 색상 | 포함 — 설정에서 ON/OFF, Android 12+만 노출 |
| 설정 저장 | DataStore Preferences (신규 의존성 1개) |
| 작업 이력 저장 | jobs.json (org.json 직렬화, 파일 기반) |
| 보안 방식 | HTTP Basic(웹) + 첫 접속 IP 허가 다이얼로그 + OkHttp 스로틀 인터셉터 |
| 내비게이션 | 하단 NavigationBar 3탭: 다운로드 / 파일 / 설정 |

## 3. 아키텍처 변화

```
ui/
├─ theme/Theme.kt          # ThemeMode(SYSTEM/LIGHT/DARK) + dynamicColor + MD3 스킴
├─ MainActivity.kt          # NavigationBar 3탭 + 클립보드 감지
├─ DownloadsScreen.kt       # 기존 메인(주소카드+목록) 이동
├─ FilesScreen.kt           # 완료 파일 목록 + 공유/삭제
├─ SettingsScreen.kt        # 포트/테마/동적색/동시수/자동시작/알림/보안/스로틀
relay/
├─ SettingsRepository.kt    # DataStore 래퍼 (포트·테마·동시수·보안·스로틀…)
├─ JobsPersistence.kt       # jobs.json 저장/복원 (.part 연계)
├─ DebugLogger.kt           # 유지
├─ DownloadEngine.kt        # +PAUSED 상태, 속도(EMA), 스로틀, 영구연동
├─ RelayServer.kt           # +Basic Auth 인터셉터, /api/info, IP 허가 검문
├─ RelayService.kt          # +알림 진행바 갱신, 설정 반영 재시작
```

## 4. 구현 단계

- T-101 본 문서 + DataStore 캐시 확인·의존성 추가
- T-102 Theme.kt(3모드+동적색) + 전면 토큰화 리팩터 + themes.xml DayNight
- T-103 SettingsRepository(DataStore) + SettingsScreen UI
- T-104 포트 변경 → 서버 재시작 연동 + 유효성 검사
- T-105 알림 진행바(진행률·속도 실시간 갱신)
- T-106 저장공간 표시(StatFs): 앱 카드 + GET /api/info
- T-107 jobs.json 영구저장 + 시작 시 .part 복원
- T-108 일시정지(PAUSED)/재개 + 속도 계산(EMA) 표시
- T-109 NavigationBar 3탭 + FilesScreen(공유 인텐트)
- T-110 클립보드 URL 감지 SnackBar
- T-111 웹 Basic Auth (설정 on/off)
- T-112 신규 기기 IP 허가 다이얼로그 + 화이트리스트
- T-113 속도 제한 스로틀 (KB/s, 0=무제한)
- T-114 단위 테스트 추가(설정·persist·throttle) + ktlint
- T-115 실전검증 + 스크린샷(라이트/다크) + CHANGELOG + 커밋

## 5. 테스트 계획

- TC-U-100 RangeParser 회귀(기존 10건 유지)
- TC-U-101 SettingsRepository 기본값·저장·로드
- TC-U-102 JobsPersistence 직렬화 왕복
- TC-SRV-010 Basic Auth ON 시 미인증 401 / OFF 시 통과
- TC-E2E-020 라이트 모드 스크린샷 명암비 확인

## 6. 롤백

git revert 단일 커밋 단위 · DataStore 파일 삭제 시 기본값 복원 · jobs.json 삭제 안전.

## 7. 에러코드 추가

E-AND-DOWN-2001 포트 변경 실패(점유/권한) · E-AND-DOWN-2002 설정값 무효 · E-AND-DOWN-2003 이력 복원 실패(무시 가능)

## 8. 성능 예산

Cold Start ≤2s 유지 · 알림 갱신 1Hz · 진행 Flow 버퍼링 · 웹 폴링 1Hz 유지.
