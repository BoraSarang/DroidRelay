# PLAN v0.13 — 비디오 다운로드 개선 (재요청 · MP4 직접 · 해상도/파일명) (2026-08-28)

> 유튜브 제거(v0.12.2) 후 남은 스트림 다운로드 기능을 사용자 요구 3종으로 개선한다.
> 참고: PageKit(`/Users/lee/Documents/Apps/PageKit`)이 네이버 상품(MP4 직접)·토렌트씨(스트림 파싱)에서
> 하는 동작을 DroidRelay(OkHttp 서버) 한계 내에서 정규식 기반으로 구현한다.

## 개요 (사용자 요구)
1. **실패 시 재요청 버튼** — video 잡 FAILED 상태에서 "재시도" 버튼 → 재분석 후 재다운로드.
2. **MP4 직접 다운로드** — `.mp4` 직접 URL 또는 페이지 내 임베디드 mp4 추출 (`brand.naver.com/jbn/products/...`).
3. **웹페이지 동영상 파싱 + 해상도 선택 · 파일명 변경** — 스트림 페이지 파싱, m3u8/mpd 마스터
   매니페스트 variant(해상도) 선택, 파일명 입력 (`torrentsee.../topic/...`).

## 기술 제약 (PageKit과의 차이 — 중요)
- PageKit은 **브라우저 확장**: DOM/JS 실행, `performance` entries, iframe 협업, webRequest 캡처가 가능.
- DroidRelay는 **OkHttp 서버**: HTML 문자열만 GET → **DOM 파싱/JS 실행 불가**, 정규식 기반 HTML 스니핑만 가능.
- 403/Cloudflare 차단 사이트는 기존처럼 "직접 m3u8/mpd URL 입력" 안내로 폴백.
- 해상도 목록은 **매니페스트 자체를 GET**해서 파싱(HTML이 아닌 스트림 컨텐츠에서 variant 추출) — 순수 함수로 분리해 TDD 가능.

## 결정 사항 (사용자 확정)
| 항목 | 결정 |
|------|------|
| 해상도 UI | **스트림(m3u8/mpd) 인식 시에만** 해상도 선택 제공. 직접 MP4는 해상도 없음(파일명만). 웹+앱 모두 |
| 재요청 방식 | FAILED video 잡 "재시도" → **Job.url로 StreamDetector 재분석** 후 재다운로드. (Job에 streamUrl 별도 저장 없음 — 원본 url만 저장됨. 토큰 만료 가능성에 재분석이 안전) |
| 버전/커밋 | **v0.13 새 PLAN**, `fix/bd-0203` 브랜치에서 유튜브 제거(v0.12.2)는 이미 커밋(`757155d`) 완료. v0.13도 같은 브랜치에서 진행 후 커밋 |

## 아키텍처
```
[입력] 웹페이지 / .m3u8 / .mpd / .mp4
        └─► StreamDetector.analyze
              ├─ .mp4 직접: kind=mp4, url=mp4, qualities=[]
              ├─ 웹페이지: HTML에서 .mp4·video src·m3u8/mpd 스니핑
              └─ m3u8/mpd 마스터: 매니페스트 GET → variant 목록(해상도) 파싱
                    └─► qualities=[{label, url, protocol}]
        ► VideoApi.create(url, streamUrl or variantUrl, filename)
              └─► VideoDownloadManager.createAndStart (FFmpeg -c copy)
        ► 실패 시 FAILED → UI "재시도" → VideoApi.create 재실행(재분석)
```

### StreamDetector 확장 (T-886)
- `Found`에 `kind`(`stream`/`mp4`)와 `qualities: List<Quality>` 추가.
  - `Quality(label, url, protocol)`, protocol=`hls`|`dash`|`direct`.
- **MP4 직접**: 입력 URL이 `.mp4`면 그대로(`direct`), 웹페이지 HTML에서 `.mp4`/`source[src*=.mp4]`/`og:video`/인라인 JS `file|source|src:"...mp4..."` 스니핑.
- **마스터 매니페스트 variant 파싱** (순수 함수 → TDD):
  - HLS master: `#EXT-X-STREAM-INF:(...RESOLUTION=WxH...)` → 다음 variant URI + resolution 라벨 + URI 절대화.
  - DASH: `Representation`의 `width/height` + baseURL/`AdaptationSet` Resolution.
  - variant가 1개뿐(단일)이면 `qualities=[]`(선택 UI 생략).
- 기존 `analyze`의 동작(웹페이지에서 m3u8/mpd 매니페스트 URL 1차 발견)은 유지하고, 그 결과 매니페스트를 추가 GET해 variant를 파싱.

### VideoDownloadManager (T-887)
- 기존 `start(jobId, argv)` 재사용. FAILED 잡의 "재시도"는 VideoApi.create를 **재실행**하는 형태(새 argv → 새 FFmpeg 세션)로 처리 — 부분 재개 불가(원본 copy).
- `createAndStart`가 FAILED에서도 새 잡을 열 수 있도록 별도 처리 불필요(Job은 새 id로 생성). 단, "재시도" 시 **같은 파일명 우선**을 위해 filename 재사용 시 흥미 — 안전하게 사용자/이전 파일명 전달.

### API (T-888)
- `POST /api/video/analyze`: 응답에 `kind`, `qualities: [{label,url,protocol}]` 추가.
- `POST /api/video/create`: body에 `streamUrl`(선택 해상도 variant URL) — 기존 그대로. (streamUrl로 variant URL을 넘기면 그 해상도 다운로드)
- 실패 시 `E-AND-VID-0205`: 재요청 재분석/재다운로드 실패(2회 이상) — 신규.

### UI (T-888 웹, T-889 앱)
- **웹 `WebAssets.kt`**:
  - `analyzeVideo` 결과: `qualities.length>0`이면 해상도 `<select>`/라디오 목록 렌더, `kind=mp4`면 "MP4 직접" 표시.
  - 파일명 `<input>` 추가 → create body에 `filename` 전달.
  - FAILED video 잡 카드에 "재시도" 버튼 → `createVideo` 재호출(재분석).
- **앱 `DownloadsScreen.kt`**:
  - `VideoAddRow`: 분석 결과 `qualities` 있으면 해상도 선택(라디오), 파일명 `OutlinedTextField`, `kind=mp4` 구분.
  - `JobCard`: video 잡 FAILED 시 재개 버튼이 `engine.resume()`(DownloadEngine)을 호출하지 않도록 **video 전용 재시도 분기** → `VideoApi.create` 재실행.

### 에러코드 (추가)
| 코드 | 의미 |
|------|------|
| `E-AND-VID-0205` | 비디오 재요청(재분석/재다운로드) 실패 |

## 마일스톤 (T-885 ~ T-890)
| ID | 내용 | 상태 |
|----|------|------|
| T-885 | PLAN v0.13 작성 + TODO 등록 | ✅ |
| T-886 | StreamDetector: MP4 직접 + 매니페스트 variant(해상도) 파싱 (TDD — 파싱 순수 함수) | ✅ |
| T-887 | video 재요청(ref): 재시도는 UI(웹 `retryVideo`·앱 `JobCard`)에서 `VideoApi.create(url)` 재호출(재분석 포함, 부분 재개 불가). 실패 시 `E-AND-VID-0205` | ✅ |
| T-888 | API(analyze qualities/create variantUrl·filename) + 웹 UI(해상도·파일명·재시도) | ✅ |
| T-889 | 앱 Compose UI(해상도 선택·파일명·JobCard 재시도 분기) | ✅ |
| T-890 | 검증(ktlint·assembleDebug·node --check) + 실기기 E2E + CHANGELOG/error_message_ko.json/TODO/세션 로그 + 커밋 | |

## 검증
- `JAVA_HOME=".../Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:ktlintCheck :app:assembleDebug :app:testDebugUnitTest` (apps/android)
- `node --check`(WebAssets JS 추출), 실기기 E2E:
  - 네이버 상품 MP4 직접 다운로드.
  - 토렌트씨 스트림 페이지 파싱 + 해상도 선택 + 파일명 변경.
  - 실패 유도 → FAILED → "재시도" 버튼 동작.

## 위험/제한
| 항목 | 대응 |
|------|------|
| 서버가 아닌 브라우저 전용 사이트(JS 렌더링) | 정규식 스니핑으로 못 찾으면 기존 안내(직접 URL 입력) |
| 403/Cloudflare | 기존 `E-AND-VID-0200` 안내 + 직접 입력 경로 |
| m3u8 토큰 만료 | 재요청 시 재분석으로 갱신 |
| DRM | 기존 `E-AND-VID-0300` 유지 |
| 부분 재개 불가 | 재요청은 처음부터 재다운로드(원본 copy 특성) — 안내 명시 |

## 라이선스
- 변경 범위에 새 라이브러리 없음(기존 OkHttp/FFmpegKit 유지).
