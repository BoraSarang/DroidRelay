# 터널 가이드 — Tailscale / Cloudflare Tunnel

> 마지막 갱신: 2026-08-27 (v0.10.1)

## 1. 터널이란?

DroidRelay의 웹 대시보드는 기본적으로 **같은 네트워크(랜)** 안에서
`http://{폰 IP}:8080`으로 접속한다. 터널 기능은 이 대시보드를 **집 밖/외부
네트워크에서도** 안전하게 접속할 수 있게 해 주는 경로를 만드는 기능이다.

현재 구현 범위는 "터널 상태 관리 + 연결 테스트"이며, 실제 터널 바이너리
(cloudflared / tailscale)는 아직 앱에 번들되지 않았다. 아래 "5. 현재 한계점" 참고.

## 2. 어디서 확인할 수 있나?

- 상태 API: `GET /api/tunnel/status`
  ```json
  {
    "connected": false,
    "reason": "터널 비활성화",
    "tailscaleInstalled": false,
    "tailscaleConnected": false,
    "cloudflaredAvailable": false
  }
  ```
- 설정 저장 API: `GET /api/settings/tunnel`, `POST /api/settings/tunnel`
  ```json
  { "tunnelEnabled": true, "tunnelProvider": "TAILSCALE" }
  ```
- 디버그 로그: `GET /api/debug/logs` 필터 `Tunnel`

> 웹 대시보드 설정 사이드바에서 터널 토글/제공자/상태를 관리한다 (v0.10.2+, 🔗 터널 섹션)
> (전역/다운로드/토렌트/RSS/Debrid/가드/MCP/스케줄/스토리지/디버그만 존재).
> 따라서 지금은 REST API로만 설정·조회한다.

## 3. 시나리오별 사용법

### 3-1. 같은 네트워크 (기본, 터널 불필요)

1. 폰에서 RelayService 실행
2. PC/노트북 브라우저로 `http://{폰 IP}:8080` 접속
   (IP는 대시보드 하단 또는 `GET /api/info`에서 확인)

### 3-2. Tailscale — 추천 (Tailscale 앱 설치 시)

- **사전 준비**: 폰에 Tailscale 앱 설치 + 로그인 (`io.tailscale.tailscale`)
- **동작 방식**: TunnelManager가 Tailscale 앱 존재를 감지하고
  네트워크 인터페이스에서 `100.x.x.x` (Tailscale 가상 IP)를 탐지한다.
  탐지되면 `GET /api/tunnel/status`가
  `{"connected": true, "url": "http://100.x.x.x:8080"}`을 반환한다.
- **접속**: 같은 Tailscale 계정을 사용하는 모든 기기(PC 등)에서
  `http://100.x.x.x:8080` 접속 — 폰이 어느 네트워크에 있든 동일한 IP로 접근 가능
- **기대 시나리오**: 폰이 집 밖 LTE/5G에 있을 때 PC에서 폰의 대시보드 원격 접속

### 3-3. Cloudflare Tunnel

- **사전 준비**: 앱 내장 `cloudflared` 바이너리 필요
  (`context.filesDir/cloudflared`, ARM64)
- **동작 방식**: 바이너리 실행 시
  `cloudflared tunnel --url http://localhost:8080 --no-autoupdate` 프로세스가
  `trycloudflare.com` 임시 URL을 생성, 출력에서 URL을 추출해
  `currentUrl`로 저장한다.
- **기대 시나리오**: Tailscale 미사용 시 외부 접속용 공개 URL 확보.

## 4. 설정 방법 (API 기준)

```bash
# 1) 터널 활성화 + 프로바이더 지정
curl -X POST http://{폰 IP}:8080/api/settings/tunnel \
  -H "Content-Type: application/json" \
  -d '{"tunnelEnabled": true, "tunnelProvider": "TAILSCALE"}'

# 2) 상태 확인
curl http://{폰 IP}:8080/api/tunnel/status
```

- `tunnelProvider` 값: `TAILSCALE` 또는 `CLOUDFLARE`
- 설정은 DataStore에 영구 저장되며 앱 재시작 후에도 유지됨

## 5. 현재 한계점 (v0.10.1)

1. **터널 바이너리 미번들**: `cloudflared`/`tailscale` 실행 파일이 앱에 들어있지
   않음 (소스 주석: "ARM64 번들 (추후 구현)"). `filesDir`에 수동으로 넣어야 실제
   연결이 가능
2. **자동 시작 없음**: RelayService가 TunnelManager를 생성만 하고 `start()`를
   호출하지 않음 — 앱 부팅 시 터널이 자동으로 켜지지 않음
3. **Tailscale 상태 감지는 조회 시점 기준**: 폰이 Tailscale에 연결돼 있고 100.x
   IP가 있으면 connected로 표시됨 (폰 Tailscale 앱에서 연결 토글 필요)

## 6. 문제 해결 (Troubleshooting)

| 증상 | 확인할 것 |
|------|-----------|
| `reason: 터널 비활성화` | `tunnelEnabled=true` 설정 여부 |
| `reason: 터널 프로바이더 미설정` | `tunnelProvider` 값 지정 |
| `reason: Tailscale 바이너리가 없습니다` | `filesDir/tailscale` 존재 여부 |
| `reason: cloudflared 바이너리가 없습니다` | `filesDir/cloudflared` 존재 여부 |
| `connected: false`지만 Tailscale 앱 설치됨 | 폰 Tailscale 앱에서 연결 상태 + 100.x IP 생성 여부 (`Tailscale` 디버그 로그) |
| 403/연결 불가 (외부 접속) | RelayServer가 `0.0.0.0` 바인딩인지, 폰 방화벽/절전 모드 |

## 7. 로드맵 제안

- [ ] `cloudflared`/`tailscale` ARM64 바이너리 앱 번들
- [x] 웹 설정 사이드바에 "🔗 터널" 섹션 추가 (토글 + 상태 + URL 표시) — v0.10.2
- [ ] RelayService에서 시작 시 자동 `TunnelManager.start(settings)` 호출
- [ ] 외부 URL로 대시보드 접속 시 서버 CORS/인증 보강