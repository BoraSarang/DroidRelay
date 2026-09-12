# PLAN v0.23 — Phase B 성공률·복원력 (쿠키전달 + 파일명 + 트래커)

> Motrix-Next 차용 P0-3/P1-4/P1-6. 권장안 확정: 쿠키는 메모리만(영속 금지), 미디어는 해상도+컨테이너로 제한 유지(자막 제외).

## 목표
1. 0206 차단 사이트 구제율 상승 — 브라우저 세션의 Referer/Cookie를 분석·다운로드 fetch에만 전달
2. 파일명 처리 매트릭스 완성 — 제어문자·예약어·후행점/공백·traversal
3. 시더 없는 토렌트 구제 — 커뮤니티 트래커 자동 동기 + 핸들 주입

## 범위
- `StreamDetector.ExtraHeaders` + `sanitizeExtra()` 순수함수 + `analyze/parseManifest/fetch` extra 인자(기본 null, 기존 호출 호환)
- `VideoApi.analyze/create` extra 전달 + `JobRoutes` 2종 파싱(초과 시 `E-AND-VALID-0002` 400)
- 웹 비디오 섹션 Referer/Cookie 입력(성공 시 초기화) + 앱 `VideoAddRow` 2필드(쿠키 마스킹, 시작 후 초기화)
- `safeFilename` 강화(제어문자·예약어·후행점/공백) + `DispositionHeader` 빈 이름 폴백
- `TrackerListProvider`(parse/cached/refresh, 24h 캐시, 실패 시 번들 폴백, 절대 throw 금지)
- 설정 `trackerSyncEnabled`(기본 true) + Settings/SettingsRoutes/reset 배선 + maskSecrets(비밀 아님, 평문)
- `TorrentEngine.applyExtraTrackers` — registerMapping 후킹 + start 시 백그라운드 refresh
- `TorrentRoutes`: `GET /api/torrents/trackers` + `POST /api/torrents/trackers/refresh`
- 웹 토렌트 설정 토글+새로고침 + 앱 Torrent 설정 스위치
- 테스트: ExtraHeaders 6 + SafeFilename 6 + TrackerList 5 = 17건
- 문서: TODO T-967~T-973, CHANGELOG 0.23.0(미배포), error_message `E-AND-VALID-0002` 1건 추가

## 비범위
- WebView 스니핑(배터리/권한 비용), 쿠키 영속 저장, 자막 트랙 선택, GeoIP/PEX 그래프
- 트래커 프로빙(도달성 측정) — 목록 주입만, 품질 측정은 다음 단계

## 설계 상세

### P0-3 쿠키 전달 (메모리만)
```kotlin
data class ExtraHeaders(val referer: String?, val cookie: String?)
fun sanitizeExtra(referer: String?, cookie: String?): ExtraHeaders?
// trim → blank=null → referer>2048 or cookie>4096 이면 null 반환 + 호출자가 400 (무음 절단 금지)
// 둘 다 null이면 null (헤더 미적용 경로)
```
- `fetch(url, extra)`: extra?.referer → `Referer` 헤더(기본 google 대신), extra?.cookie → `Cookie` 헤더
- 로그는 `extraReferer=true/false, extraCookie=true/false`만, 값 절대 기록 금지 (DebugLogger·apiBuf 모두)
- `VideoApi.create`의 FFmpeg `-i stream`에는 쿠키를 전달하지 않음 — 분석/fetch 단계만 (FFmpeg headers 옵션은 범위 외, 실패 시 기존 0206 안내 유지)
- 웹: `<input id="vref" placeholder="Referer (선택)">` + `<input id="vcookie" type="password" placeholder="Cookie (선택, 저장 안 됨)">`, analyze/create/retry body에 포함, `clearVideoUrlInput`에서 함께 초기화
- 앱: `var referer/cookie` 상태 + Cookie는 `PasswordVisualTransformation`, 시작 성공 시 초기화

### P1-4 파일명
```kotlin
fun safeFilename(raw, ext): String // 기존 정규식에 추가:
 // 1. \p{Cntrl} 제거  2. 예약어(CON/PRN/AUX/NUL/COM1-9/LPT1-9, 대소문자무시) → "_"+base
 // 3. 후행 '.'/' ' 제거 (Windows)  4. 기존 80자 cap·한글 유지
fun DispositionHeader.make(name): String // blank → "download" 폴백
```

### P1-6 트래커
```kotlin
object TrackerListProvider {
  const val SOURCE_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
  const val MAX_TRACKERS = 40; const val CACHE_TTL_MS = 24*3600*1000
  fun parseList(text: String): List<String> // http/https/udp만, trim, dedupe, cap — pure
  fun getCached(context): List<String> // 캐시 유효 or 번들 DEFAULT_TRACKERS(5개 udp)
  suspend fun refresh(context): List<String> // 네트워크, 실패 시 getCached, never throw
}
```
- `TorrentEngine.applyExtraTrackers(id)`: enabled && cached.isNotEmpty → withGate { existing=url set → missing take(20) → `th.addTracker(AnnounceEntry(u))` } + try-catch 전체 감싸기 (핸들 UAF 재발 방지, T-931 교훈)
- 호출점: `registerMapping()` 내 `applyPersistedSelection` 다음 (ADD_TORRENT·수동매핑·재시작복원 전 경로 커버)
- `start()`: `scope.launch { if (firstBlocking().trackerSyncEnabled) refresh() }` (DHT 분기 옆)

## 검증 게이트
1. `testDebugUnitTest` (신규 17건 포함 GREEN)
2. `ktlintCheck -x runKtlintCheckOverKotlinScripts` (kts 파서 기존 이슈 제외)
3. `assembleDebug` GREEN + 실기기 설치
4. E2E: 정상 m3u8 회귀(헤더 없음) + 0206 사이트에 쿠키 입력 시 분석 시도(성공 보장은 사이트 종속) + 번들에 쿠키 값 없음 확인 + 트래커 목록 조회/refresh + 손상 트래커 파일 시 번들 폴백 + 파일명 6종 단위

## 리스크
- 쿠키 로그 누출: 리뷰 시 `DebugLogger.*extra` 값 전달 여부 grep 필수, 테스트로 `sanitizeExtra` 길이 cap 단언
- 트래커 주입이 libtorrent 세션 불안정 유발: withGate 내 + 전체 try-catch + 20개 cap, 이상 시 설정 OFF로 즉시 차단 가능
- 설정 54번째 필드: v1 마이그레이션이 기본값 자동 구체화 — Phase A 설계 검증 케이스 겸함
