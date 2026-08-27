# PLAN v0.9 — "Server Edition" (2026-08-27)

## 개요
"안드로이드 폰 = 다운로드 전용 서버" — 유휴 폰을 24/7 안정적 다운로드 서버로.
열/배터리/스토리지 가드, MCP 내장, 원격 터널링, AI 에이전트 연동.

## 핵심 결정사항
| 항목 | 결정 |
|------|------|
| MCP 서버 | 내장 (JSON-RPC 2.0, 도구별 권한, 핵심 5개 기본 ON) |
| 클라우드 디브리드 | 플러그인 방식 (설정에서 API 키만 입력) |
| Tailscale/CF Tunnel | ARM64 바이너리 번들 + VpnService 래퍼 |
| 가드 데몬 | 동일 프로세스 내 WorkManager 30초 워커 |
| 데스크톱 앱 | 나중 (PWA 우선) |
| 가드 임계치 | 열 45°C / 배터리 20% / 디스크 90% (설정에서 변경 가능) |

## Phase 1: 다운로드 엔진 고도화 (2주)

### 1.1 시퀀셜 다운로드
- `SettingsRepository`: `torrentSequentialDownload` 키 추가
- `TorrentEngine`: ADD_TORRENT 시 `handle.setSequentialDownload(true)` 적용
- 웹 UI: 토렌트 > 고급 섹션에 시퀀셜 다운로드 토글

### 1.2 RSS/Atom 피드 자동 다운로드
- `RssFeedManager`: `WorkManager` 15분 폴링, Room 엔티티 `RssFeed`/`RssFilter`
- 필터 매칭 시 자동 큐잉, 중복 방지

### 1.3 RealDebrid/AllDebrid 연동
- `DebridClient`: 공통 인터페이스, 토큰 저장/갱신/만료
- 설정 UI에 API 키 입력란, 언리스트링크 처리

## Phase 2: 서버급 운영 능력 (2~4주)

### 2.1 MCP 서버 내장
- `/mcp` JSON-RPC 2.0, 핵심 도구: file_list, file_read, download_add, download_list, download_control
- 도구별 권한 설정 UI, 프라이버시 모드, LAN만 바인딩

### 2.2 웹훅/콜백 API
- `WebhookManager` Room 엔티티, HMAC-SHA256 서명
- 지수 백오프 재시도, 데드레터 큐

### 2.3 Tailscale/CF Tunnel
- assets에 ARM64 바이너리 번들, VpnService 래퍼
- 설정 토글 ON → 자동 터널 UP, 영구 URL 표시

### 2.4 가드 데몬
- `WorkManager` 30초 워커, 임계치: 열 45°C / 배터리 20% / 디스크 90%
- 액션: 스로틀 → 일시정지 → 알림, 설정에서 임계치/액션 변경 가능

## Phase 3: 관측성 & 자동화 (4~6주)
- Prometheus `/metrics` + Grafana 대시보드 JSON
- 스케줄/조건부 다운로드 (크론 + WorkManager 제약)
- 외장 SSD 자동 마운트 감지 → 다운로드 경로 제안

## 에러코드 (예정)
- E-AND-GUARD-THERMAL-0001: 열 스로틀링 발동
- E-AND-GUARD-BATTERY-0002: 배터리 부족 일시정지
- E-AND-GUARD-STORAGE-0003: 디스크 공간 부족
- E-AND-MCP-AUTH-0004: MCP 인증 실패
- E-AND-TUNNEL-0005: 터널 연결 실패
