# PLAN v0.28 — 앱 주소 클릭 시 브라우저 열기 (android)

> 다운로드 탭 서버 카드 + 설정 탭 서버 상태의 주소 텍스트를 탭하면 외부 브라우저로 열기.

## 목표
1. `DownloadsScreen.kt` ServerCard 접속 주소 텍스트 클릭 → `ACTION_VIEW` 브라우저 열기
2. `SettingsScreen.kt` 서버 상태 URL 텍스트 클릭 → 동일 동작
3. 실패(브라우저 없음 등)는 try-catch + DebugLogger + 스낵바 안내, 크래시 금지

## 범위
- 신규 `ui/OpenBrowser.kt` 공용 헬퍼 `openUrlInBrowser(ctx, url, tag)` (DRY, 2곳 재사용)
  - `[INFO] [FEATURE] 주소 브라우저 열기` 로그 1개 + 실패 시 `[ERROR]` 로그
  - 에러코드 신설 없음 (기존 `E-AND-DOWN-1003`은 다운로드 URL용이라 범위 밖)
- 클릭 affordance: 주소 텍스트 `primary` 색 + 밑줄
- 기존 복사/공유 버튼 동작 유지

## 비범위
- 웹 대시보드(`WebAssets.kt`) 변경 없음
- QR 클릭 동작 변경 없음
- `error_message_ko.json` 변경 없음

## 검증 게이트
1. `ktlintCheck` 본문 GREEN
2. `./build_and_run.sh debug` 빌드+설치 GREEN
3. 실기기: 주소 탭 → 브라우저 열림 + 로그 확인
