# 세션 로그 2026-08-28 (Android + Web) — v0.12.2 유튜브 제거 + v0.13 비디오 다운로드 개선

## 세션 요약
- **무엇을/플랫폼**: [ANDROID+WEB] v0.12.2 YouTube/yt-dlp 전면 제거(커밋 `757155d`) 후 v0.13 — 비디오 다운로드 개선 3종: ① FAILED video 재요청(재시도), ② MP4 직접 다운로드, ③ 해상도 선택·파일명 변경 UI. PageKit(네이버 MP4·토렌트씨 스트림) 동작을 OkHttp 정규식 한계 내 구현
- **빌드**: BUILD SUCCESSFUL (ktlint + testDebugUnitTest StreamDetectorTest 7건 GREEN + assembleDebug). `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`, gradlew는 apps/android. WebAssets JS `node --check` OK
- **PERF/CACHE**: 해당 없음 (기능 개선). FFmpeg 재다운로드 경로는 부분 재개 불가 → 재시도는 원본 url 재분석
- **남은TODO**: T-890 실기기 E2E(네이버 MP4·토렌트씨 스트림·재시도) + 커밋
- **전달로그**: 토렌트씨(Cloudflare Turnstile)·네이버 쇼핑(JS 동적 로드)은 **OkHttp로 원천 불가** — fetch 브라우저 헤더 보강으로 네이버류 봇 차단 302→200 통과 확인(증명). Cloudflare JS 챌린지는 브라우저에서 m3u8/mpd 주소 직접 복사 입력 안내
- **문서갱신**: PLAN_v0.13_stream_download.md, TODO T-885~T-890, CHANGELOG v0.13.0, 본 세션 로그, error_message_ko.json(0205 추가, 사용 안 하는 0204 제거)
- **큐상태**: 없음
- **E2E**: StreamDetectorTest 7건 단위 테스트 GREEN(순수 파싱 함수 TDD). 실기기 E2E 미수행

## 구현 내역

### StreamDetector.kt — MP4 직접 + 해상도 variant 파싱 (T-886, TDD)
- `Quality(label, url, protocol)` + `Found(kind, qualities)` — kind = `stream`|`mp4`|`page`
- `analyze`: `.mp4` 직접 → kind=mp4 / 웹페이지 MP4_RE(`.mp4|.webm|.mov`) 스니핑 / 매니페스트 스니핑 + `parseManifestVariants`
- `parseHlsMaster`: `#EXT-X-STREAM-INF:RESOLUTION=` → 라벨(예: 720p) + variant URI 절대화(resolve)
- `parseDashManifest`: `Representation` width/height + `BaseURL`
- `kindOf(url)` public, `parseManifestVariants`/`scan(html,pattern)` private, `decodeEntities`/`resolve` 기존 유지
- **fetch 헤더 보강**: Referer·Sec-Fetch-*·Accept-Language·Upgrade-Insecure-Requests + Accept에 mpd/m3u8 mime — 네이버 쇼핑 봇 차단 302→200 통과, 직접 CDN 매니페스트 조회 최적화. 403 안내 문구를 Cloudflare/봇 차단 명확화

### StreamDetectorTest.kt (신규, 7건 GREEN)
- HLS 마스터 해상도 라벨/URI 절대화, 단일 variant, 마스터 아님(미디어 플레이리스트) 빈 목록
- DASH Representation 해상도 + BaseURL, Representation 없음 빈 목록
- kindOf 판별 (mp4/stream/stream/page)

### VideoApi.kt — analyze 결과에 kind·qualities 반영
- `analyze` JSON에 `kind`, `qualities`(label/url/protocol 배열) 추가

### API/웹 UI (T-888)
- `/api/video/create`에 `variantUrl`(streamUrl)·`filename` 전달·검증 (기존 create 시그니처 유지)
- WebAssets JS: analyzeVideo kind별 라벨(MP4 직접/직접 스트림/페이지) + 해상도 라디오(name=vq) + 파일명 input(#vname) + currentVideoUrl() 선택 variant + createVideo body 확장 + retryVideo(FAILED video 재시도, 재분석/재다운로드) + render FAILED video "재시도" 버튼

### 앱 Compose UI (T-889)
- VideoAddRow: 분석 결과 해상도 라디오(qualities>1일 때)·파일명 OutlinedTextField·kind별 라벨, start()가 선택 variant+파일명 전달
- JobCard: video FAILED 잡이 engine.resume()(DownloadEngine, video 미지원) 대신 VideoApi.create 재시도 분기, 실패 시 E-AND-VID-0205 설정

## 검증 요지
- TDD: 파싱 순수 함수 테스트 먼저 작성(GREEN) 후 구현 확정
- curl 진단: 네이버는 UA+전체 브라우저 헤더+302 follow → 200 (기존 302 차단 우회), 토렌트씨는 Mobile/전체 헤더로도 403 고정(Cloudflare)
- JS 문법: WebAssets JS 추출(604~1921행) `node --check` OK (단일 raw string, 빠진 추출 시 `</script>` 포함으로 SyntaxError 확인)
- 0204 에러코드: 실제 throw 없음(parseManifestVariants는 원본 폴백) → 죽은 코드 방지 위해 error_message_ko.json·PLAN에서 제거

## 문서
- CHANGELOG.md: v0.12.2 유튜브 제거 + v0.13.0(MP4 직접·해상도·파일명·재시도·0205·헤더 강화) 기록
- TODO.md: v0.13 섹션 T-885~T-890 등록 (885~889 ✅, 890 진행중)
- PLAN_v0.13_stream_download.md: 요구/기술 제약/결정 사항/마일스톤/에러코드(0205만)

## 주요 변경 파일
- **변경**: StreamDetector.kt (kind/qualities/MP4 직접/해상도 파싱/브라우저 헤더), VideoApi.kt (kind·qualities 반영), WebAssets.kt (해상도·파일명·재시도), DownloadsScreen.kt (VideoAddRow/JobCard), error_message_ko.json (0205), CHANGELOG.md, TODO.md
- **신규**: StreamDetectorTest.kt (TDD 7건), PLAN_v0.13_stream_download.md

## 남은 TODO
- T-890: 실기기 E2E(네이버 MP4·토렌트씨 스트림 해상도·재시도) — 실기기 필요로 미수행, 커밋은 별도 요청으로 진행

## 큐 상태
- 없음

## E2E
- StreamDetectorTest 단위 테스트 7건 GREEN (HLS/DASH 해상도·MP4 kind 판정)
- 실기기 E2E 미수행 (T-890 잔여)
