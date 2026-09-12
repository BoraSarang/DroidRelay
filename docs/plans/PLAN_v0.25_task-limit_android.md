# PLAN v0.25 — Phase D 작업별 속도 제한 (android)

> Motrix `Global and per-task limits` 차용. T-982~T-986.

## 목표
- HTTP 작업별 다운로드 상한 (전역 제한과 병행, 둘 중 작은 값 적용)
- 토렌트는 기존 per-torrent 제한 유지 (이번 단계 변경 없음)

## 범위
- `Job.maxDownBps: Long = 0` (B/s, 0=무제한) + `JobsPersistence` save/load (`optLong` 기본 0 — 구파일 호환)
- `ThrottleInterceptor`: `JobTag` 요청 태그 + `taskLimit:(String)->Long` 리졸버 + 작업별 버킷 맵 + `forget(id)` + `effectiveLimit()` 순수함수
- `DownloadEngine`: 요청 태그, `setTaskLimit(id, bps)` (Repo 갱신 + 로그), DONE/cancel 시 forget
- API: `GET /api/jobs`에 `maxDownBps` 포함 + `POST /api/jobs/{id}/limit {maxDownBps}` (0~1GB/s, else 400)
- 웹 카드별 제한 select (무제한/256K/512K/1M/5M, video·DONE 제외) + 앱 JobCard 제한 다이얼로그
- 테스트: effectiveLimit 5건 + Repo 작업별 제한 흐름 2건 = 7건
- 문서: TODO T-982~T-986, CHANGELOG 0.25.0(미배포), 에러코드 추가 없음

## 비범위
- 비디오(FFmpeg) 작업별 제한 — OkHttp 경로가 아니라 미적용, UI에서 숨김 (재시도 제외와 동일 선례)
- 작업별 업로드 제한 — HTTP 잡은 다운로드만 수행하므로 YAGNI
- 토렌트 UI 변경 없음 (이미 per-torrent 제한 존재)

## 설계 상세
```kotlin
// 유효 상한: 둘 다 0이면 무제한(0), 아니면 양수 중 최소
fun effectiveLimit(globalBps: Long, taskBps: Long): Long
```
- 인터셉터: GET 요청의 `tag(JobTag::class.java)` → task 상한 조회 → 전역·작업 버킷 각각 take (둘 다 양수면 둘 다 통과해야 전송)
- 리졸버는 `JobsRepository.get(id)?.maxDownBps`를 live 조회 — 엔진 재시작/동기화 불필요
- 버킷 누수 방지: DONE/cancel 시 `forget(id)`, FAILED/PAUSED는 유지(재개 시 재사용)
- API 검증: 존재하지 않는 id → 404, 범위 밖 → 400 "잘못된 요청"

## 검증 게이트
1. `testDebugUnitTest` (신규 7건 포함 GREEN)
2. `ktlintCheck -x runKtlintCheckOverKotlinScripts` + JS `node --check`
3. `assembleDebug` + 실기기 설치
4. E2E: 100MB 파일 2건 동시 다운로드, 1건만 512KB/s 제한 → 제한건 ~512KB/s·비제한 full 속도 + 해제 후 full 복귀 + API roundtrip

## 리스크
- 전역 0(무제한)+작업 제한 조합에서 전역 버킷 스킵 확인 (무제한 분기 순서 주의 — T-851 이중래핑 교훈, 단일 래핑 유지)
- 동시 2건이 전역 버킷 공유 시 합산 상한 — 기존 동작 유지 (작업별은 개별 추가 상한)
