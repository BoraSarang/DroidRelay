# PLAN v0.26 — Phase E 트래커 프로빙 (android)

> Motrix 트래커 프로빙 차용 (도달성 측정). T-987~T-991.

## 목표
- 동기된 트래커의 실제 도달성 측정 → 주입 우선순위(도달 우선, 미확인 중간, 불가 최후)
- 24h 프로브 캐시 + 백그라운드 측정 (배터리/데이터 최소)

## 범위
- `TrackerProbe.kt` 신규 (UDP BEP15 핸드셰이크 + TCP connect, 병렬 8, 타임아웃 4초)
- 프로브 캐시 `trackers_probe.txt` (수동 직렬화, TTL 24h, never throw)
- `applyExtraTrackers` 도달 우선 정렬 (프로브 없으면 기존 순서)
- API: GET trackers에 `probed/probing/probeAgeMs` 추가 + POST trackers/probe (백그라운드 시작)
- refresh 완료 후 자동 프로브 (start 시 + 수동 refresh 시, 엔진 scope)
- 웹 토렌트 설정 도달 표시+버튼 + 앱 설정 행
- 테스트 10건 (UDP 빌드/파싱·엔드포인트·정렬·캐시)
- 문서: TODO T-987~T-991, CHANGELOG 0.26.0(미배포), 에러코드 추가 없음

## 비범위
- HTTP announce 정식 요청 (트래커 통계 오염 방지 — TCP/UDP 핸드셰이크만)
- 트래커별 속도 측정·자동 제외 (도달 불가도 주입 유지, 순서만 후순위)
- IPv6 별도 처리 (시스템 리졸버 그대로 사용)

## 설계 상세
```kotlin
data class ProbeResult(val url: String, val reachable: Boolean, val rttMs: Long)
fun parseEndpoint(url): Endpoint(scheme, host, port)? // pure, 기본포트 http80/https443
fun buildUdpConnectRequest(txId: Int): ByteArray // magic 0x41727101980 + action0 + txId
fun parseUdpConnectResponse(bytes: ByteArray, txId: Int): Boolean // action0 + txId 일치
suspend fun probeAll(urls, timeoutMs=4000, parallelism=8): List<ProbeResult> // url별 try-catch
```
- UDP: DatagramSocket SoTimeout, 16바이트 connect 응답 검증. TCP(http/https): Socket connect만 (HTTP 요청 없음).
- 캐시 행: `url|1|123|timestamp` (`|` 불가 URL은 parseList에서 이미 공백/제어문자 제외, `|` 포함 시 drop)
- 정렬: 도달(rtt 오름) → 미확인(캐시 없음) → 불가. take(20) 유지.
- 동시 실행 가드 AtomicBoolean, 측정 중 GET은 `probing:true` + 기존 캐시 반환.

## 검증 게이트
1. `testDebugUnitTest` (신규 10건 포함 GREEN)
2. `ktlintCheck -x runKtlintCheckOverKotlinScripts` + JS `node --check`
3. `assembleDebug` + 실기기 설치
4. E2E: POST probe → GET probing→완료, 도달 N/전체 확인, 주입 로그 순서, 캐시 파일 존재

## 리스크
- 모바일 데이터/배터리: 40개 핸드셰이크 수백 바이트, refresh 연쇄 1일 1회 수준 — 무시 가능
- UDP 차단망: 전부 불가로 나와도 주입은 유지되므로 기능 저하 없음 (순서만 무의미)
- DNS 지연: 타임아웃 4초, 병렬 8로 상한 ~20초
