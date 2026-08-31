# PLAN v0.16 — 앱 설정 미러 1차 + MD3 디자인 개편 (android)

- **날짜**: 2026-08-31
- **플랫폼**: Android (`apps/android/app`)
- **관련 docs**: `docs/TODO.md`(T-916~T-921), `docs/CHANGELOG.md`

## 목표
1. 웹 대시보드 설정 탭에만 있고 앱 설정에 없는 항목을 앱으로 미러링(1차: 전역 속도 제한·토렌트 고급·가드).
2. 그 계기로 앱 UI를 MD3 기준으로 전면 정돈(디자인 개편).

## 사용자 결정
- 1차 범위 = **토렌트 고급 + 가드 + 전역 속도 제한** (권장안 채택)
- "1차부터 계속 확장" → 2차(스케줄/Debrid/터널/MCP/기본값 복원) 예약
- 디자인 개편 범위 **A. 구조+스타일 전면 정돈**, dynamicColor "기기 테마 따라감 유지", 이모지 → Material Icons 전환
- 릴리즈 분리: **v0.16.0**(설정 추가) → **v0.16.1**(디자인 개편)

## v0.16.0 — 앱 설정 미러 1차
백엔드 필드/setter는 이미 완비(RelayServer.applySettings가 DownloadEngine·TorrentEngine·GuardDaemon에 반영). UI만 추가.

- **전역 속도 제한** (`SettingsScreen` '다운로드' 섹션)
  - 다운로드/업로드 각 Switch(`paused = maxBps==0`) + 슬라이더 1~10 Mbps
  - 저장 = `* 1_048_576L`(bps), 끄면 0(무제한) → `setMaxDownloadBps/setMaxUploadBps`
- **토렌트 고급** ('Torrent' 섹션)
  - 시퀀셜 다운로드 Switch → `setTorrentSequentialDownload`
  - 시더 부재 대기(초, 0~3600) + 적용 → `setTorrentMinSeedWaitSec`
  - 리슨 포트(1024~65535, 랜덤 버튼) + 적용 → `setTorrentListenPort` (변경 시 토렌트 엔진 재시작 안내 텍스트)
  - 저장 경로 + 적용(존재/생성 여부 규칙) → `setTorrentSavePath` (기본 `/sdcard/Download/DroidRelay`)
- **가드 보호** (새 섹션)
  - 활성화 Switch → `setGuardEnabled`
  - 임계 슬라이더: 열 50~70°C / 배터리 5~50%(5단계) / 스토리지 50~99%
  - watchdog 주기(초, 15~3600) + 적용 → `setWatchdogIntervalSec`
  - HTTP→HTTPS 강제 Switch → `setForceHttpsRedirect`

## v0.16.1 — MD3 디자인 개편
- `Theme.kt`: `surfaceContainer*` 표면 토큰 Light/Dark 명시(Light #F3F6FC/#EDF0F6/#E7EAF2/#E1E4EC/#FFFFFF, Dark #0B0E13/#181C22/#1C2026/#262A31/#31353D), 셰이프 MD3 기본(4/8/12/16/28)
- `MainActivity.kt`(`RootApp`): `TopAppBar`(탭 타이틀 + 디버그 패널 아이콘), 탭 라벨 한글화("토렌트"), 디버그 패널을 `ModalBottomSheet`로 이전, 패딩을 각 화면으로 이관
- `ui/DebugPanel.kt`(신규 공용): `DebugPanelContent()` 이동 — 다중 선택 복사/전체 복사/비우기
- `DownloadsScreen.kt`: 중첩 `LazyColumn` 제거 → **단일 LazyColumn**(서버카드→URL→비디오→작업 목록→빈 상태), "📡 DroidRelay" 타이틀·5탭 힌트 제거, 이모지 → 아이콘(VideoLibrary 등), ⚡/⏱ 제거
- `TorrentScreen.kt`: 중첩 Scaffold 제거 → `Box`+FAB(하단 우측), 스낵은 Root 콜백으로 이관(`onShowSnack`), ⏱ 제거
- `FilesScreen.kt`: "📱 보관함" 이모지 제거, 카드색 `surfaceContainerHigh`→`surfaceContainer` 통일, 스낵 콜백 이관, 빈 상태 정돈
- `SettingsScreen.kt`: Root 패딩 이관에 따른 수평 패딩 추가

## 검증
- `:app:testDebugUnitTest` GREEN / `ktlintCheck` GREEN(우회 플래그) / `:app:assembleRelease` GREEN
- 실기기 `R5CT215F4QK` 인플레이스 설치: v0.16.0(versionCode 18) → v0.16.1(versionCode 19) 확인
- DebugPanel 확인(ERROR 0)은 **사용자 직접**

## 진행 이력
- v0.16.0 설치 완료 → v0.16.1 디자인 개편 구현 완료 → 설치 완료. 커밋은 사용자 요청 시 분리(설정/디자인/문서).