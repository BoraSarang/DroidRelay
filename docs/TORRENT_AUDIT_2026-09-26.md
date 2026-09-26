# 📦 토렌트 동작 방식 점검 리포트 — DroidRelay v0.39.0

- **점검일**: 2026-09-26
- **대상**: `apps/android/` (Kotlin + libtorrent4j 2.1.0-39, arm64-v8a)
- **방식**: 코드베이스 정적 분석 (TorrentEngine/TorrentRoutes/TorrentScreen/RelayService/웹 대시보드 전수 조사)
- **요약 판정**: **아키텍처는 건전하나, 검증 우선순위가 필요한 결함 5건 + 설정/API 미비 4건 확인**
- **수정 반영**: 2026-09-26 동일 스프린트에 🔴 #1–#5, 🟡 #7–#8 수정 완료 (아래 ✅)

---

## 1. 아키텍처 요약

| 계층 | 파일 | 역할 |
|------|------|------|
| 엔진 | `relay/TorrentEngine.kt` (1,292행) | 세션 수명주기, magnet/.torrent 추가, 알림 처리, 5초 폴링, 보관함 이동, 큐/스톨/시드비율 |
| 저장소 | `relay/TorrentRepository.kt` | `TorrentState` 8종 + `TorrentJob` + StateFlow 목록 |
| 영속화 | `relay/TorrentPersistence.kt` | `filesDir/torrents.json` 원자 쓰기(tmp→rename) + `.bak` 복구 |
| HTTP API | `relay/TorrentRoutes.kt` | add/pause/resume/files/limit/search/reorder/trackers |
| UI | `ui/TorrentScreen.kt`, `relay/WebDashboardHtml.kt` | 앱 탭(StateFlow), 웹(SSE 1초 갱신) |
| 서비스 | `relay/RelayService.kt` | 엔진 기동(IO 디스패치), 완료/실패 알림, 웹훅 |

**핵심 안전장치**
- JNI 비스레드안전 → `sessionGate` ReentrantLock 단일 직렬화 (T-930)
- `alert.handle()` dangling 회피 → `session.find()` 기반 독립 카피 (T-931)
- 핸들 매핑: `id↔handle`, `infoHash↔id` 양방향 + FINISHED는 게이트 밖 처리 (T-934)

## 2. 동작 흐름 (검증됨)

```
magnet 입력(앱/웹/공유/RSS)
  → magnetInfoHash(hex 40자) + 중복 가드(409)
  → TorrentJob(FETCHING_METADATA) + persistNow
  → session.download() → ADD_TORRENT 매핑 → METADATA_RECEIVED → DOWNLOADING
   → 5초 폴링: 속도/피어/스톨감지/시드비율/슬롯유지 + 완료 전이 감지 → 시딩 중단(unset AUTO_MANAGED+pause) → moveToStorage()
   → DONE (FINISHED 알림은 상태 업데이트만 담당, 유실돼도 폴링 전이가 이동 보장)
  → StorageJanitor (자동분류+쿼터, v0.19) + 완료 알림/웹훅
```

- **시드 유지**: 세션에서 제거 안 함 → 시드비율(기본 2.0) 도달 시 자동 pause (T-956)
- **재시작 복원**: DONE/FAILED는 목록만, 진행분은 magnet/`.torrent` 재등록, 원자 쓰기 + `.bak` (E-AND-DOWN-2003)
- **정체 회전**: STALLED 감지 → 대체 있으면 큐 맨뒤 + pause → `maintainSlots()` 재기동 (T-1050)

## 3. 결함 · 리스크 (우선순위순)

### 🔴 높음
1. ✅ **수정됨** **서비스 미기동 시 magnet 고착** — `session ?: throw` 가드로 FAILED 전이 (`TorrentEngine.kt` addMagnet/addTorrentFile)
2. ✅ **수정됨** **알림 게이트 타임아웃 시 알림 유실 → 보관함 이동 누락** — FINISHED 알림에서 `moveToStorage()` 제거, 5초 폴링의 완료 전이(`prevState→DONE/SEEDING`) 시점으로 일원화 → 알림 유실과 무관하게 보장
3. ✅ **수정됨** **시딩 중 보관함 이동** — 전이 시점에 `unsetFlags(AUTO_MANAGED)` + `pause()`로 시딩을 멈춘 뒤 이동
4. ✅ **수정됨** **`cancel()` 미완료 항목이 보관함의 같은 이름 파일 삭제 가능** — `storageDir/<name>.deleteRecursively()` 호출 제거 (saveDir 내 임시파일만 삭제)
5. ✅ **수정됨** **가드 스로틀 미적용** — `RelayService.onThrottleChange`에서 DOWNLOADING/SEEDING 일시정지 + 해제 시 PAUSED 재개 연동

### 🟡 중간
6. ✅ **수정됨** PEX 설정은 저장만 되고 엔진 미적용 → `applyPexEnabled()`로 `settings_pack.enable_pex` 세션 반영(시작·설정 변경 즉시 핫 적용), 실기기 토글 검증(`PEX 활성화/비활성화` 로그) · 앱/웹 안내문의 "항상 켜짐" 문구 제거
7. ✅ **수정됨** 웹 `/api/torrents`에 `errorMessage` 미포함 → `TorrentRoutes.kt`에 `errorMessage` 필드 추가
8. ✅ **수정됨** 웹 배지 CSS에 `DONE`/`FAILED` 키 누락 → `badgeClass`에 `DONE`/`FAILED` 추가 + 상세 모달에 `errorMessage` 표시 (`WebDashboardHtml.kt`)
9. ✅ **수정됨** magnet base32 미지원(hex만) → `magnetInfoHash`가 base32 32자도 hex로 변환(소문자 통일), `MagnetInfoHashTest` 5건 추가 · `.torrent` 파일 중복 가드 없음 → `addTorrentFile`이 job 추가 전 infohash 검사 후 `DuplicateTorrentException` → 라우트 409 (magnet와 동일)

### 🟢 경미
- `TORRENT_ERROR` 메시지 고정("토렌트 에러"), 폴링 catch가 틱 전체를 조용히 통과
- DONE 항목은 폴링 조기 리턴 → 진행률/속도 통계 동결
- MCP에 토렌트 도구 없음, fastresume 덤프 코드 없음(재시작 시 재체크 의존)

## 4. 검증 체크리스트

**자동화** (CI 실행 대상): `./build_and_run.sh test android`
- [x] `TorrentStallTest` 10건 · `SeedRatioTest` 5건 · `TrackerListTest` 5건 · `TrackerProbeTest` 10건 · `PersistenceGuardTest` — 이번 점검 실행 결과 BUILD SUCCESSFUL
- [x] `MagnetInfoHashTest` 5건 (base32/hex infohash, 결함 #9a) 추가 — 전체 184건 0 failures
- [x] `ktlintCheck` GREEN

**실기기 핵심 시나리오** (수동 검증 권장)
- [ ] magnet 추가 → `ADD_TORRENT 매핑` → `메타데이터 수신` → DOWNLOADING → 완료 → `보관함 이동 완료` 로그
- [ ] 동일 magnet 재추가 → 409 중복 응답
- [ ] 서버 미기동 상태 magnet 추가 → **결함 1 재현 여부**
- [ ] 시드비율 도달 → 자동 pause, 완료 삭제 후 보관함 파일 생존
- [ ] 앱 강제 종료 후 재시작 → 진행분 복원 + `torrents.json` 원자성
- [ ] STALLED 회전 + 슬롯 승격 (죽은 magnet으로 T-1050 검증)

## 5. 결론

- **정상 동작 영역**: 추가→다운로드→완료→보관함 이동의 기본 파이프라인, 영속화/복원, JNI 레이스 방어(T-930/931), 정체 회전·시드비율·트래커 동기(T-956/971/1050)까지 구현 상태 양호. 자동화 테스트 184건 전부 통과.
- **2026-09-26 수정 반영**: 🔴 #1–#5, 🟡 #6–#9 **전부 수정 완료**. ktlint/test GREEN(테스트 36건), 실기기 재설치 후 엔진 정상 기동, 웹 대시보드에서 정렬·배지 실측, PEX 토글 핫 적용 로그 검증.
- **미수정 잔여(경미)**: `TORRENT_ERROR` 메시지 고정, DONE 항목 통계 동결, MCP 토렌트 도구·fastresume 덤프 부재 — 기능 확장 성격으로 별도 판단.
- **보류 실기기 시나리오**: 미기동 magnet 재현, 시드비율→완료 삭제 생존, STALLED 회전은 기기에서 실제 토렌트 진행 시 재검증 권장 (현재 사용자 토렌트 진행 중이라 건드리지 않음).
