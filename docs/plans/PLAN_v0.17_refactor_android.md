# PLAN v0.17 — 리팩토링 4묶음 (android)

> 조사: TorrentEngine/RelayServer/WebAssets 전수 리뷰 (2026-09-06).
> 사용자 결정: 4묶음 전부 진행 / 속도 UI=양쪽 슬라이더 통일 / 기본값=업로드 512·동시 2 / 키스토어PW=BuildConfig.

## Phase 1 — 긴급 버그 (T-933)
- F1: 웹 `toast(` → `showDlToast(` 교체 3곳 (MCP/스케줄 저장 확인 복구)
- F2: 웹 `||기본값` falsy 버그 → null 판별 (torrentUpload/Download·동시수·가드·워치독)
- F3: 전역 속도 슬라이더 바인딩 (input→라벨, change→autoSave) + 데드 `onSpeedLimitChange` 정리
- F4: 키스토어 PW 하드코딩 → `BuildConfig` (local.properties, git 미추적)
- F5: DeviceGate `f.get()` 무타임아웃 → 60초 타임아웃+503 폴백 (워커 고갈 방지)

## Phase 2 — T-931 후속 안정화 (T-934)
- S1: FINISHED/ERROR/METADATA alert.handle() → session.find() 통일 (dangling UAF 재발 방지)
- S2: FINISHED의 moveToStorage+persist를 게이트 밖으로 (id/name만 캡처)
- S3: register/unregisterMapping 단일 헬퍼 (cancel 원자화, polling 무효제거 job.infoHash 폴백, 수동매핑 3곳 통합)
- S4: apply* 3종의 session null 체크를 게이트 안으로

## Phase 3 — 설정 단일화 (T-935)
- C1: `SettingsConstraints` 단일 진실 (범위·기본값·업로드512·동시2) + Repo 기본값 정합
- C2: 앱 속도 UI 슬라이더 통일 (0~1024 step32 + "끔/무제한/KB/s" 라벨 함수)
- C3: 웹 라벨·||·datalist 정합 (임의값 표시 정상화)

## Phase 4 — 구조 분리 (T-936)
- D1: RelayServer 라우트 영역별 분리 (Torrent/Job/Settings/Storage/Debug/Video)
- D2: WebAssets JS 헬퍼 (apiGet/apiPost, $(), bindSlider, postSettings) + switchTab 중복 삭제 + 모달 통합 기반
- D3: error_message_ko.json 미등록 8종 추가 + 웹 showError(code) 매핑 + DebugLogger 태그 교정

## 검증 게이트
- 매 Phase: `assembleDebug` GREEN → `R5CT215F4QK` install -r → API 스모크 (설정 저장/조회, magnet 생명주기)
- 커밋/푸시는 사용자 요청 시에만. 버전 bump는 최종 검증 후 결정.

## 후속 후보 (이번 범위 제외)
- U1: `firstBlocking()` 15곳 Netty 워커 블로킹 → suspend first()+스냅샷 캐시 (위험도 상, 범위 큼)
- U2: WebAssets assets 분리 (dashboard.html/app.js/style.css) + CSS 변수 + `$()` 캐시 + autoSave 전 필드
- U3: 모달 2벌 통합 (openModal/closeModal), 웹 `showError(code)` json 매핑
- U4: MCP/리셋 용어 통일, 저장경로·포트·가드 범위 상수 확대 적용
- U5: `getTorrentDetail/pieceInfo` 게이트 배치 조회 (목록 API P95)
