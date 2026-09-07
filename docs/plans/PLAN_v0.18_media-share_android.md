# PLAN v0.18 — 담기↔소비 완성 (android)

> v0.17 리팩토링·속도 개선 후, 사용 흐름의 양 끝(담기↔소비)을 완성하는 묶음.

## 범위
- **A1 브라우저 직접 재생(T-940)**: `GET /stream/{name...}` Range(206 Partial Content) 지원 + 보관함 영상행 ▶ 재생 버튼 + `<video>` 오버레이 플레이어. 받기 전 내용 확인용. `StorageGuard` 경로 가드 재사용, `Content-Disposition: inline`.
- **B1 Share Intent 받기(T-941)**: `ACTION_SEND` 텍스트/URL/magnet 수신 → 판별(http/magnet/.torrent) → 기존 jobs/torrents 등록 경로 재사용. Manifest 인텐트 필터 + 수신 토스트.
- **B2 토렌트 파일 선택(T-942)**: 파일 목록 조회 + `POST /api/torrents/{id}/files` 우선순위 API(libtorrent `file_priority`) + 웹 상세 모달 체크박스 + 앱 UI.

## 제외 (v0.19로)
- 썸네일(A2)·WebDAV(A3)·쿼터/분류/중복(C)·검색(B3)·공유링크/게스트/위젯(D).

## 검증 게이트 (T-943)
- `test` + `lint`(스크립트 제외, 기존 파싱 실패 유지) + `assembleDebug` GREEN → `R5CT215F4QK` 설치 → 실기기 E2E(재생 시크·공유 등록·선택 다운로드) → CHANGELOG + 세션 로그 + 커밋/푸시(요청 시).
