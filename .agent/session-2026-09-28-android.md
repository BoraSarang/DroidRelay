# 세션 로그 — 2026-09-28 (android, T-1085 토렌트 보관함 이동 + 웹 삭제)

## 1. 목표
사용자 제보 2건을 조사·수정:
1. "토렌트 완료 되도 보관함으로 이동 안 됨"
2. "웹에서 토렌트 다운로드 완료 시 삭제해도 삭제가 안 됨"

## 2. 결론 — 원인이 달랐고, 둘 다 v0.42 회귀
| 증상 | 원인 | 도입 |
|---|---|---|
| 완료 → 보관함 이동 0건 | 폴링의 "완료 전이" 판정과 "prevState==DONE 조기 반환"이 **같은 함수 안에서 모순** | 8cb8ce7 (T-1055) |
| 웹 삭제·일시정지·재개 전부 415 | `CrossOriginGuard` "Content-Type 비면 거부"가 **본문 없는** 요청을 막음 | 0312dbe (T-1073) |

### 함정 1 — "알림 유실 대비" 코드가 알림이 잘 오는 경로를 죽였다
폴링은 완료 **전이**를 보고 이동을 시도했으나, `TORRENT_FINISHED` 알림이 `state = DONE` 을
**먼저** 기록하고 폴링 주기는 5초. 완료 직후 5초 안에 알림이 먼저 오므로 사실상 항상
`prevState == DONE` → 같은 폴링의 `return@forEach` 에 막혀 이동 코드에 **한 번도 도달하지 못함**.
알림 유실을 대비하려고 `moveToStorage()` 를 알림에서 뺐지만, 알림이 state 를 선기록한다는
사실을 함께 손보지 않았다.

```
v0.42  알림 먼저(정상 경로) → early-return, 이동 안 됨   | 폴링 먼저(드묾) → 이동 됨
v0.43  알림 먼저(정상 경로) → 이동 됨                   | 폴링 먼저(드묾) → 이동 됨
```

### 함정 2 — CSRF 방어 규칙이 정상 트래픽을 막았다
브라우저는 본문이 없으면 `Content-Type` 헤더를 아예 안 보낸다. 대시보드 제어 요청 다수가
본문 없이 호출하는데("비면 거부"가 415 로 막음) → 웹의 모든 버튼이 죽고, 맥 메뉴바
`RelayClient.control()` 도 415 를 받은 뒤 응답을 버려 "아무 일도 없다"로 보였다.

## 3. 변경
- 브랜치 `fix/torrent-storage-web-delete-v0.43` (main 직접 push 금지, push 안 함)
- `TorrentEngine.kt` — `shouldMoveToStorage`(완료+미이동 판정) · `storageMoved` 재이동 방지 ·
  `MAX_STORAGE_MOVE_ATTEMPTS=3` · `moveToStorage()` 성공 반환 · `restoreTorrents()` 미완료 이동 수습
- `CrossOriginGuard.kt` — `hasBody()` 신설 + `contentTypeAllowed(..., hasBody)` · 본문 있을 때만 검사
- `RelayServer.kt` — Content-Length/Transfer-Encoding 으로 hasBody 산출 후 전달
- 테스트 `TorrentStorageMoveContractTest` 신규 13건 · 버전 0.42.0/33 → **0.43.0/34**
- 문서: `CHANGELOG.md` v0.43.0 · `TODO.md` v0.43 섹션

## 4. 검증 (실기기 SM-S901N · Android 16 · 10.38.120.211:5555)
| 항목 | 결과 |
|---|---|
| 단위 테스트 | 300 → **313** 0 failures |
| **수정 전 재현** | 본문 없는 DELETE = **415** / Content-Type 붙이면 **404** → 가드가 막고 있음을 확정 |
| **수정 후 제어 요청** | DELETE·pause·resume·rss·debrid·overlay **전부 라우트 도달** |
| **보안 9종 재검증** | text/plain·x-www-form-urlencoded·CT 누락(본문 有) = **415**<br>교차 출처 Origin·Sec-Fetch-Site cross-site·Origin null = **403** |
| **보관함 이동 실증** | stuck 상태 `START-633`(DONE, 3.7GB) → `완료 torrent 복구 이동 → /sdcard/Download/DroidRelay/START-633`<br>saveDir 은 비고 보관함에 파일 2개(3.77GB + 2MB) 등장 |
| 대시보드 verify | 35/35 · `assembleDebug` 성공 · 프로브 잔여물 정리 완료 |

**보안 강도 저하 없음** — 실기기 9종으로 확인. 본문 없는 cross-origin 요청은
`Origin`·`Sec-Fetch-Site` 가 계속 막는다(브라우저가 항상 붙이고 JS 위조 불가).

## 5. 미해결 (이번 범위 밖 — 사용자 결정 필요)
1. **DNS 리바인딩이 현재 가드로 차단되지 않는다.** 실측:
   `Host: evil.com:3000` + `Origin: http://evil.com:3000` → **200 통과**.
   리바인딩 시점에는 Host 와 Origin 이 **항상 같으므로** `originAllowed`(Origin==Host)는
   구조적으로 이를 막을 수 없다. 기존 테스트의 "DNS 리바인딩" 2건은 *다른* 시나리오
   (Origin≠Host, 접미사 혼동)라 실제 공격을 커버하지 않는다. 정치는 Host 허용목록이 필요하지만
   mDNS 호스트명·커스텀 DNS·포트포워딩 접근을 막을 수 있어 설계 결정이 요구된다.
   `CrossOriginGuardTest` 의 "DNS 리바인딩은 Host 로 판정해 차단된다" 설명도 부정확.
   → **사용자 결정: 문서화만 하고 방치** (v0.43 에서 미해결 유지)
2. 참고: `POST /api/jobs/{id}/pause` 가 없는 id 에도 200 "ok" 를 반환(선재). 무해하지만 조용.

## 6. 2차 작업 — 작업 위치를 보관함 안으로 (사용자 선택) + 사고 2건

사용자가 40초 정체의 해법으로 "저장 위치 자체를 보관함으로" 를 선택.
구현 중 **내가 사고를 두 번 냈다** — 둘 다 기록한다.

### 사고 A — 마이그레이션이 3분 53초를 먹고 목록을 날렸다
작업 위치 변경에 필요한 마이그레이션을 `start()`(**서비스 메인 스레드**)에 두었다.
크로스마운트 **동기 복사** 6.8GB = 3분 53초. 그 동안
①ANR ②5초 폴링 `persistDebounced()` 가 **복원 전인 빈 저장소**를 `torrents.json` 에
`[]` 로 덮어씀(→ `jobs.json` 도) ③`load()` 가 그 `[]` 를 읽어 **토렌트 4건 + 작업 목록 소실**.

> **교훈** — 목록을 메모리에만 두는 구조에서 **복원 완료 전 저장은 언제나 위험**하다.
> "아무것도 안 읽은 시점"과 "사용자가 다 지운 시점"은 파일로 구분되지 않는다(둘 다 `[]`).
> 저장은 **복원 완료 플래그**로 막아야 한다. 이건 이 저장소의 구조적 위험이지
> 마이그레이션만의 문제가 아니다.

수정: `restoreTorrentsAsync()`(IO 스레드) + `persistNow()` 복원 전 스킵 +
`onDestroy`·`onTaskRemoved` 복원 전 스킵(선재 결함, 동일 유형이라 함께 닫음).

### 사고 B — 마이그레이션에 복사 폴백을 안 넣었다
`renameTo` 실패 시 `skipped++` 만 하고 넘어갔다. 조용히 건너뛴 결과
진행 중 3건(JUR-838 3.9GB · SDAB-357 5.6GB · T38-072 4.3GB)이 **0바이트에서 재시작**.
→ 폴백 추가 + 수동 복구(일시정지 → 폴더 교체 → 재개)로 전량 복원함.

### 설계 함정 — "보관함 루트로 직접 받기" 는 데이터 손실 위험
사용자가 고른 방향을 그대로 쓰면 `enforceQuota`(`sortedBy { lastModified }`)가
**진행 중인 대용량 파일 때문에 오래된 사용자 파일을 휴지통으로 보낸다.**
그래서 **루트가 아니라 숨김 하위 폴더**(`.torrents`)를 썼다 —
같은 파일시스템(복사 소멸) + 쿼터·노출 부작용 제거를 동시에 얻는다.
`.trash` 와 동일 취급이며, 숨김 판정이 6곳 하드코딩돼 있어 `isHidden()` 으로 모았다.

### 최종 실기기 검증
| 항목 | 결과 |
|---|---|
| 기동 시간 | **3분53초 → 5.8초** |
| 가드 실증 | 항목 있는 상태로 재시작 → **살아남음** (`복원 완료`→`플래그 설정`→`저장 완료`) |
| `.torrents` 노출 | 목록에 안 보임 · `?path=.torrents` 직접 접근 차단 |
| 보안 4종 | 415/415/403/403 유지 · 본문 없는 DELETE = 404 |
| 테스트 | 300 → **320** 0 failures |

### 데이터 손실 최종 보고
- **소실**: 토렌트 6건의 **magnet 링크** + 진행 목록.
  → 사용자가 같은 torrent 를 다시 받기하면 **부분 데이터 26.6GB 가 재사용**된다(0부터가 아님).
- **무손실**: 설정(datastore) · 보관함 파일 · START-633(완료분).
- 복구 없음: magnet 링크는 파일과 함께 사라져 자동 복구 불가. 재추가가 유일한 경로.
