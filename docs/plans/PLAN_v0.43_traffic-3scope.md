# PLAN v0.43 — 기기 트래픽 3구간 분리 (M-28)

> 작성: 2026-09-30 · 플랫폼: android(서버) + macos(클라이언트) · M/L

## 1. 문제

사용자 보고: **"다운로드로 마찬가지 속도가 다르게 나옴"**, **"업로드는 제대로 나오지 않음"**

M-27(2구간: 외부/전체)의 실측 결함. 11MB 다운로드 통제 실험:

```
swlan0      Δtx = 11,693,373   ← 실제로 Mac 에 전송된 양 (11.36MB) ★ 일치
rmnet_data1 Δrx =    295,326   ← 셀룰러 경유 2.6%뿐
서버 external Δrx =          0   ← ★ 화면에 0 이 뜬다
```

**원인은 버그가 아니라 정의 불일치다.** `external` = `rmnet*`(셀룰러)만 세므로,
**핫스팟 ↔ 클라이언트 구간이 통째로 누락**된다. 이 앱의 목적(핫스팟으로 파일 주고받기)에서는
**핫스팟 구간이 지표의 핵심**인데 표시되지 않는다.

## 2. 실측으로 확정한 사실 (추정 아님)

| 사실 | 근거 |
|---|---|
| `swlan0` = 핫스팟 AP. rx=클라이언트→폰, tx=폰→클라이언트 | `ip addr` + 방향 실측 |
| `all` = `rmnet` + `swlan0` 합계와 일치 | 증분 대조 `all Δrx=25,286` vs `swlan0 Δrx+Δtx=88,863` |
| **`rmnet_ipa0` 는 중복 계상** | 동일 구간 tx `ipa0=15,174` vs `data1=15,082` B/s (99.2%) |
| `rmnet_ipa0` = IPA 오프로드 가상 장치 | `dumpsys netstats` 에 **한 번도 안 나옴** |
| `rmnet_data2` 는 죽은 인터페이스 (rx 25GB, Δ=0) | 활성 PDP 2개만 (`LinkProperties`) |
| 활성 셀룰러는 `rmnet_data1`(본선) + `data0`(IMS) | `dumpsys connectivity` |

## 3. 설계 — 3구간

| 구간 | 대상 | 의미 |
|---|---|---|
| `cellular` | 활성 `rmnet*` (IPA 제외) | 폰이 **셀룰러로** 주고받은 양 |
| `hotspot` | `swlan0` (+ `ap*`) | **클라이언트와 핫스팟으로** 주고받은 양 |
| `all` | `getTotalRx/TxBytes` | 전체 |

**기존 `external` 은 `cellular` 로 유지** (구버전 클라이언트 호환 — 파라미터 없음 = ALL 유지).

## 4. 열거 전략 — 왜 `NetworkInterface` 로 충분한가

`java.net.NetworkInterface.getNetworkInterfaces()` 는 **IP 가 붙은 활성 인터페이스만** 반환한다.
실측으로 확인됨:

```
보이는 것: lo, dummy0, rmnet_data0, rmnet_data1, swlan0
안 보이는 것: rmnet_ipa0(중복), rmnet_data2(죽음)
```

→ **우연히 원하는 것만 보인다.** 죽은 `data2`·중복 `ipa0` 가 자동으로 배제된다.
`ACCESS_NETWORK_STATE` 이미 선언됨(v0.43 변경 불필요).

**단, 캐시 무효화 필요** — 셀룰러 재접속 시 인터페이스가 바뀐다. TTL 도입.

## 5. 구현

### 5.1 서버 `NetSpeedRoutes.kt`
- `TrafficScope` 에 `HOTSPOT("hotspot")` 추가
- `discoverIfaces(kind)` — 패턴 기반 탐색
- `rmnetIfaces` 캐시 **TTL 30초** (무효화)
- `ipa*` **명시적 제외** (중복 계상 방지 — 실측 근거)
- 기존 테스트 진입점 확장

### 5.2 Core `TrafficScope.swift`
- `case hotspot` 추가 · `label`/`explanation` 확장
- `mismatchNote` 는 그대로 (3구간도 동일 규칙)

### 5.3 AppModel / SettingsWindow
- 설정창 "기기 속도 범위" 3항목
- **UI 결정: 3열 동시 표시** — 사용자가 업/다운을 함께 봐야 함 (M-26 폭 예산 준수)

## 6. 검증

| 대상 | 방법 | 기준 |
|---|---|---|
| Android 단위 | `./gradlew test` | 329 → 0 실패 |
| macOS 단위 | `swift test` | 297 → 0 실패 |
| **실기기 대조** | 11MB 다운로드 증분 | `hotspot Δtx` ≈ 11,363,590 |
| **UP 검증** | Mac→폰 트래픽 | `hotspot Δrx` > 0, `cellular` ≈ 0 |

## 7. 위험

- **캐시 TTL** 로 셀룰러 재접속 직후 30초간 어긋날 수 있음 — 허용 (점진적)
- `hotspot` 미지원 기기(Wi-Fi 전용) → `supported=false` + `note` (0 으로 대체 금지)

## 8. DoD

- [ ] PLAN/TODO 문서
- [ ] 서버·Core·AppModel·설정창 구현
- [ ] 단위 테스트 통과 (양쪽)
- [ ] 실기기 3구간 대조
- [ ] `--diagnose` 3구간 출력
- [ ] CHANGELOG + session 로그
