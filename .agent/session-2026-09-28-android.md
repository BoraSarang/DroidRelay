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
2. **보관함 이동이 폴링 코루틴에서 동기 복사**로 수행된다. `getExternalFilesDir` 와
   `/sdcard/Download` 이 다른 마운트라 rename 이 EXDEV 로 실패 → 3.7GB 복사에 **40초**
   (09:21:08 → 09:21:48). 그 동안 **5초 폴링 전체가 멈춰** 진행률·속도가 얼었다.
   개선안: 폴링에서 분리된 IO 코루틴, 또는 libtorrent 저장 위치를 보관함으로 직접 지정.
3. 참고: `POST /api/jobs/{id}/pause` 가 없는 id 에도 200 "ok" 를 반환(선재). 무해하지만 조용.
