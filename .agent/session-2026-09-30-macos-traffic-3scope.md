# 세션 로그 — 2026-09-30 (macos+android, M-28 기기 트래픽 3구간 분리)

## 1. 목표
사용자: **"외부 네트워크 속도 표시 정확한 데이터 인지 확인해봐"** → 실측 검증 →
**"다운로드로 마찬가지 속도가 다르게 나옴"** · **"업로드는 제대로 나오지 않음"** → 3구간 분리(옵션 2) 구현.

## 2. 조사 결론 (추정 아님 — 실측)

### ① 처음 내 분석이 **틀렸고** 실측이 뒤집었다
처음엔 "external 이 전체의 21% 만 본다 / 40GB 누락" 이라 단정하고 A·B안 수정을 제안했다.
**틀렸다.** 동일 구간 증분 비교:
```
rmnet_ipa0  Δtx = 5,717,550
rmnet_data1 Δtx = 5,677,102   ← 99.2% 일치
```
→ `ipa0` 는 **쿨컴 IPA 오프로드 가상 장치**로 `data1` 을 통과시킨다.
`dumpsys netstats` 에도 **한 번도 안 나온다** — 시스템이 트래픽 경로로 안 쓴다.
**더하면 2배 계상.** `data2` 는 죽은 인터페이스(rx 25GB, Δ=0, 활성 PDP 2개만).

> **가설을 검증하지 않고 원인을 지어냈습니다.**
> "값이 작아 보인다"는 이유만으로 데이터 원인을 만들어낸 잘못입니다.

### ② 진짜 원인은 **정의 불일치** (버그 아님)
통제 실험(11MB 다운로드, 실제 전송 확인):
```
swlan0      Δtx = 11,693,373  ← 실제 전송(11.36MB) ★ 일치
rmnet_data1 Δrx =   295,326  ← 셀룰러 2.6%
external    Δrx =         0  ← ★ 화면에 0
```
`external` = `rmnet*`만 세므로 **핫스팟 ↔ 클라이언트 구간이 누락.**
이 앱의 핵심 동작이 안 보이는 지표였다.

## 3. 구현 (M-28)
- **서버** `?scope=hotspot` + `TrafficScope.HOTSPOT` + `sum()` 공통화
- **`ipa`/`ccmni` 오프로드 명시적 제외** (중복 계상)
- **캐시 30초 TTL** (`IfaceCache`) — 재접속 후 죽은 이름 방지
- **`wlan0`(STA) 은 핫스팟으로 안 잡음** — 없는 값 금지
- **Core** `case hotspot` · `label`/`explanation`(방향 명시) · 3구간
- **설정창** `allCases` 순회로 자동 3항목
- **진단** 3구간 비교 출력

## 4. 검증
| 대상 | 결과 |
|---|---|
| macOS | 297 → **302건** 0 실패 |
| Android | 329 → **336건** 0 실패 |
| **실기기 11MB 다운로드** | `hotspot Δtx=11,783,254` vs 실제 `11,363,590` (**3.7% 오차**) · `external Δtx=512` |
| 구버전 호환 | 파라미터 없음/`bogus` → `all` ✓ |

## 5. 함정 기록 (M-24 로그에 있던 것을 다시 밟음)
- **JUnit `assertTrue(condition, msg)` 가 아니라 `assertTrue(msg, condition)`** — 메시지가 첫 인자
- **역따옴표 테스트명 안에 `**` 강조 마커가 들어가면 파싱 깨짐** — 함수명에서 마커 제거
- 정규식 이름 치환이 **기존 테스트명을 침식**시킴 → `Conflicting overloads`
- **Tool 출력에 표시 마커가 주입되어** `od`/grep 결과가 원본과 다르게 보임 → 컴파일러가 최종 판정자
- `monkey` 로 앱을 띄워야 함 — `adb install` 후 자동 시작 안 됨(REPLACED)

## 6. 남긴 것
| 항목 | 내용 |
|---|---|
| **사용자 확인 대기** | 설정에서 **핫스팟** 선택 → 파일 주고받을 때 속도 표시 |
| 미지원 | `hotspot` 미탐색 시 `supported=false` + `note` (0 으로 대체 안 함) |

## 7. 다음 세션 시작 시
```
git checkout main && git pull
swift test --package-path apps/macos          # 302건 기준선
cd apps/android && ./gradlew test            # 336건 기준선 (JAVA_HOME 필요)
./build_and_run.sh debug android && ./build_and_run.sh run macos
./build_and_run.sh diagnose macos            # 3구간 출력 확인
```
