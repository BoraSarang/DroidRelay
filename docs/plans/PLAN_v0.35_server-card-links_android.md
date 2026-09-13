# PLAN v0.35 — 홈 QR 카드 HTTP·HTTPS 바로가기 (android)

> 홈 `ServerCard` 접속 주소 바로가기가 HTTP 1개뿐이라 HTTPS도 나란히 표시. 복사/공유 텍스트도 두 주소 포함.

## 배경/원인
- v0.33에서 상태줄·설정은 두 포트 병기했으나, `ServerCard` 바로가기 링크는 HTTP 1개만 남음 (`DownloadsScreen.kt`).
- 설정 포트는 이미 `httpPort` 파라미터로 전달 중 — HTTPS도 같은 경로로 전달.

## 목표
1. `ServerCard`에 `🌐 http://…` + `🔒 https://…` 두 링크 (각각 탭하면 브라우저).
2. 복사 버튼은 두 줄 복사, 공유 텍스트에 두 주소 포함. QR은 HTTP 유지 (1개).
3. 390px 줄바꿈 확인 (두 줄 세로 적층이라 기존보다 안전).

## 비범위
- QR에 HTTPS 포함 없음. 웹 대시보드 변경 없음. 신규 에러코드 없음.

## 검증 게이트
1. `./build_and_run.sh test android` GREEN.
2. `./build_and_run.sh debug android` + 실기기 설치.
3. 실기기: 두 링크 탭 → 브라우저 이동 (HTTPS는 자체서명 경고 후 진행).
4. TODO + CHANGELOG + 세션 로그.

## PERF/CACHE 영향
- 표시만 → 영향 없음.
