# PLAN v0.33 — 웹 파비콘/북마크 아이콘 (android+web)

> 내장 웹 대시보드(`/`)+디버그(`/debug`)+GitHub Pages 랜딩(`docs/index.html`)에 북마크 아이콘 적용. 앱 런처 벡터를 SVG로 이식해 브랜드 통일.

## 배경/원인
- `WebAssets.dashboardHtml` (`WebAssets.kt:5-9`), `debugHtml` (`WebAssets.kt:2532-2536`) `<head>`에 아이콘 `<link>` 없음 → 탭/북마크에 기본 빈 아이콘.
- `RelayServer.kt:419-428` 라우팅에 `/favicon.*`·`/apple-touch-icon.png`·`/site.webmanifest` 없음.
- 보안 파이프라인 (`RelayServer.kt:315-397`)이 전 경로에 Basic Auth+기기승인을 적용 → 파비콘 라우트 추가 시 exempt 누락하면 인증 팝업 루프 또는 아이콘 미표시.
- `docs/index.html:3-8`에도 아이콘 없음.

## 목표
1. SVG(64 viewBox, 남색 그라디언트 `#1B2C55→#0A1428` + 하늘색 궤도 `#8FD8FF` + 흰색 다운로드 화살표) 1종 + PNG 16/32/180 + `site.webmanifest`.
2. 대시보드·디버그 `<head>`에 `icon(svg/png)` + `apple-touch-icon` + `manifest` + `theme-color` 삽입.
3. Ktor에 정적 6개 경로 서빙 (`Cache-Control: public, max-age=86400`, HTML `no-store` 유지).
4. 파비콘 경로는 Basic Auth·게스트·기기승인 exempt (민감 정보 없음).
5. 랜딩 `docs/`에 동일 세트 배치 + 상대경로 링크.

## 비범위
- 앱 런처 아이콘 변경 없음. 신규 에러코드 없음 (`error_message_ko.json` 갱신 불필요).
- `/favicon.ico` 별도 인코딩 없음 — 32px PNG 바이트를 `image/x-icon`으로 응답 (모던 브라우저 호환).

## 설계
### 에셋
- `app/src/main/assets/web/favicon.svg` (단일 진실) → `rsvg-convert`로 `favicon-16.png`/`favicon-32.png`/`apple-touch-icon.png`(180) 렌더.
- `app/src/main/assets/web/site.webmanifest` (`name`/`short_name: DroidRelay`, `theme_color`/`background_color: #0A1428`, icons 16/32/180).
- `docs/`에 동일 5개 파일 복사 (Pages는 상대경로 `./favicon.svg` 등).

### 코드
- `relay/Favicon.kt` (신규, 순수 로직·단위 테스트 가능):
  - `PATHS` 6종 + `isFavicon(path)` + `assetName(path)` + `contentType(path)` 매핑.
  - `[INFO] [FEATURE] 파비콘` 로그 1개는 라우트 측에서 서빙 시.
- `RelayServer.kt`:
  - `isGuestAllowed()`에 파비콘 경로 허용 추가.
  - 보안 intercept: `isFavicon`이면 DeviceGate 승인대기 + Basic Auth 검사 스킵.
  - routing에 6개 GET 추가: `context.assets.open("web/...")` 바이트 응답 +4703 `Cache-Control` + 404 시 `HttpStatusCode.NotFound`.
- `WebAssets.kt`: `dashboardHtml`·`debugHtml` head에 5개 link + theme-color meta 삽입.

### 안전
- 파비콘 응답에 사용자 정보·토큰 포함 없음. 경로 고정 매핑이라 디렉터리 탈출 불가.
- PNG는 빌드 시 확정 바이너리, 런타임 생성 없음 → PERF 영향 없음.

## 테스트
- `FaviconTest` (신규): 6경로 판정 true/일반 API 경로 false, asset 매핑·Content-Type 매핑, `WebAssets.dashboardHtml`·`debugHtml`에 link 5종 존재.
- 기존 unit 전체 회귀.

## 검증 게이트
1. `./build_and_run.sh test android` GREEN.
2. `./build_and_run.sh lint android` GREEN.
3. `./build_and_run.sh debug android` assemble (실기기 있으면 설치).
4. `curl -I` 6경로 200 + Content-Type + Auth ON이어도 401 아님 (가능 시).
5. Chrome 탭/북마크 + iOS Safari 홈화면目視 (가능 시) + DebugPanel ERROR 0.
6. TODO T-1005~T-1009 + CHANGELOG + 세션 로그.

## PERF/CACHE 영향
- 아이콘만 장기 캐시(86400s), HTML·API 캐시 정책 무변경 → PERF 예산 영향 없음, CACHE 히트율 소폭 상승.
