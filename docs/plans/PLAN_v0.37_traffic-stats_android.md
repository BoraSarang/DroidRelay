# PLAN_v0.37_traffic-stats_android.md
> 생성일: 2026-09-21 | 플랫폼: android | 작성자: opencode

## 1. 목표 (1줄)
일별·이번달·누적 업/다운로드 통계를 집계해 웹(📊 탭+SVG 그래프)과 앱(통계 탭)에 보여준다.

## 2. 범위
- 플랫폼: android (Kotlin + Ktor + Compose) + web (대시보드, 외부 CDN 없음)
- 기술 스택: TrafficLedger.json 일별 원장 + diff 기반 계측 + SVG/canvas 자체 렌더. 선정 이유: 핫스팟 오프라인 동작, 재시작·이어받기 중복집계 방지.
- design_profile: native
- 버전: 0.37.0 (versionCode 29)

## 3. 문서 위치
- PLAN: 본 문서
- TODO: docs/TODO.md T-1030~T-1037 등록

## 4. 성능 예산
- budgets.json 참조, override 없음. 원장 쓰기는 완료·서빙 시 1회 + 10초 디바운스 영속, 폴링 추가 없음(기존 refresh 주기 재사용).

## 5. 에러 코드
- 신규 없음.

## 6. 빌드 & 검증 계획
- ./build_and_run.sh test android → ktlint 본문 → assembleDebug → 실기기 설치
- 단위테스트 신규 12건: 일자경계·월합산·prune·마이그레이션 불필요(신규 파일)·diff 베이스라인·API 직렬화
- 실기기 E2E (사용자 직접): 1MB 파일 up/down 후 오늘 집계 반영 + 재시작 후 누적 유지

## 7. 집계 정의
- 다운: downHttp(DownloadEngine 완료 실수신 바이트) + downVideo(VideoDownloadManager 완료 크기) + downTorrent(토렌트 totalDone diff)
- 업: upServe(serveFile/dl-file/dl-folder 실전송 바이트, 썸네일 제외) + upTorrent(토렌트 totalUpload diff)
- 합산 + 개별 breakdown 둘 다 저장·노출. 일자 TZ는 기기 기본, 400일 보관.
