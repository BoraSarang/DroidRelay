# PLAN_v0.38_stats-highlights_android.md
> 생성일: 2026-09-22 | 플랫폼: android | 작성자: opencode
> 전제: v0.37.1 P0 레이아웃 수정 완료 (웹 3열 grid, 앱 줄바꿈 대응)

## 1. 목표 (1줄)
traffic.json 스키마 변경 없이 `TrafficLedger.daily(400)` 연산만으로 최고 기록·평균·예측·비율·streak 하이라이트를 웹+앱 통계 탭에 추가한다.

## 2. 범위
- 플랫폼: android (Kotlin + Ktor + Compose) + web (대시보드, 외부 CDN 없음)
- 기술 스택: 기존 TrafficLedger/daily/summary 재사용, 클라이언트 연산 (서버 highlights API 신설 없음 — 폴링 증가 방지)
- design_profile: native
- 버전: 0.38.0
- 비범위 (P2로 이관): 일별 최고속도, 일별 건수, 피크 피어, 누적 가동시간, 저장공간 추이, 단절/스로틀 추이 → PLAN_v0.39_stats-ledger-v2_android.md 예정

## 3. 문서 위치
- PLAN: 본 문서
- TODO: docs/TODO.md T-1040~T-1045 등록 예정

## 4. 성능 예산
- budgets.json 참조, override 없음. highlights 연산은 stats 탭 진입 + 5초 갱신 시점에 `daily(400)` 1회 스캔 (400건 정렬 없음, O(n)). 폴링 추가 없음.

## 5. 에러 코드
- 신규 없음.

## 6. 하이라이트 6종 정의
| # | 지표 | 계산식 | 빈값 처리 |
|---|---|---|---|
| H1 | 최다 다운로드일 | `daily(400).maxBy{downTotal}` → 날짜+값 | 기록 없으면 `—` |
| H2 | Top3 다운로드일 | downTotal 상위 3일 | 3일 미만 시 있는 것만 |
| H3 | 주간평균·월예측 | 7일 평균, 월누적/경과일×당월일수 | 월 1일은 예측=월누적 |
| H4 | 업/다운 비율 | `upTotal/downTotal` (전체·월), 토렌트 한정 `upTorrent/downTorrent` | down=0이면 `—` |
| H5 | 연속 streak·활성일수 | `downTotal>0` 현재 연속일, 최장 연속일, 30일 활성일수 | streak 0 허용 |
| H6 | 타입별 비중 | `downHttp:downVideo:downTorrent` % | 합계 0이면 `—` |

## 7. UI 배치
- 웹: `#panel-stats`에 `.sg` 하이라이트 섹션 추가 (그리드 2열, 모바일 2열 유지). 기존 3열 트래픽 카드 아래, 30일 그래프 위.
- 앱: `StatsScreen`에 `HighlightCard` LazyColumn item 1개 추가 (6행). 기존 StatCard 3개 아래, WeekBars 위.
- 문구: `최다 다운로드일 09-20 · 11.31 GB`, `이번달 예측 약 12.4 GB`, `공유비율 3.6%` 등 1줄 요약형.

## 8. 빌드 & 검증 계획
- `./build_and_run.sh test android` → TrafficLedger 파생 로직 단위테스트 6건 (best일, Top3, 월예측, 비율 0분모, streak 단절, 비중 합계0)
- `./build_and_run.sh debug android` → 실기기 설치 후 통계 탭 진입, 하이라이트 6종 노출 + 5초 갱신 확인
- E2E (사용자 직접): 기록 없는 신규 설치 시 `—` 표시, 1MB 파일 up/down 후 H1/H3 반영 확인

## 9. DoD
- [ ] 웹 데스크탑 3열 + 모바일 3열 깨짐 없음 (v0.37.1 유지)
- [ ] 웹+앱 하이라이트 6종 표시, 빈값 `—` 처리
- [ ] 단위테스트 6건 통과, ktlint 본문 통과, assembleDebug 성공
