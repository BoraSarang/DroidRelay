# PLAN v0.14 — 안정성·보관함·토렌트 개선 (7개 이슈) (2026-08-31)

> v0.13.3 공개 릴리즈 후 실사용 중 보고된 7개 이슈를 수정한다.
> 핵심: ①24시간 연속 동작 안정성(부팅 자동시작+waatchdog) ②토렌트 중복/교차 매핑
> ③속도 제한 정상화 ④피어 보유율%+시더 부재 자동 중단 ⑤보관함 업로드 진행률
> ⑥보관함 폴더 날짜 표시 ⑦iPad(같은 테더링) 접속 불가.

## 개요 (사용자 요구 7건)
1. **24시간 연속 동작** — 수 시간~1일 뒤 웹(8080)응답 없음, 앱 실행해야 복구.
2. **토렌트 중복 추가** — 받는 중 마그넷 추가 시 새 다운로드가 아니라 "같은 토렌트처럼"(추출 중...) 표시.
3. **속도 제한 미동작** — 다운로드/업로드 제한 걸어도 제대로 반영 안 됨.
4. **피어 보유율** — 피어가 파일을 몇 % 가졌는지 표시 + 시더(100%) 없으면 자동 중단.
5. **보관함 업로드** — 업로드 진행률 없음(큰 파일 시 동작 여부 불명). 해당 폴더에서 올리면 그 폴더로.
6. **보관함 폴더 생성일** — 파일처럼 폴더에도 날짜 표시.
7. **iPad 접속 불가** — 같은 테더링에 맥은 접속, iPad는 안 됨.

## 조사 결론 (코드 위치)
| 이슈 | 원인 | 위치 |
|------|------|------|
| 1 | 프로세스 사망 후 복구 경로 전무(유일=MainActivity|RelayService.start). 배터리최적화·부팅·watchdog 전무 | RelayService.kt:234-244,428, MainActivity.kt:62, Manifest 리시버 부재 |
| 2 | `SessionManager.download()` 동일 infohash에 조용한 return → 새 job FETCHING_METADATA 영구잔류. `findJobIdForNewTorrent`가 `maxByOrNull(id)` 가잭 매핑(교차 레이스) | TorrentEngine.kt:156-184,436-452,512-517; RelayServer.kt:1162-1198 |
| 3 | 설정 2중(speedLimitKbps vs maxDownloadBps). `downloadBps.toInt()` Long→Int 오버플로우 | TorrentEngine.kt:350-379; RelayServer.kt:378-431 |
| 4 | API에 `pi.progress()` 이미 포함, UI 미표시. 시더 자동중단 없음 | TorrentEngine.kt:605-624; WebAssets.kt:1399-1413 |
| 5 | raw-upload/upload 진행률 없음. 폴더지정은 `curPath` 기반(이미 지원) | RelayServer.kt:1501-1583; WebAssets.kt:1197-1209 |
| 6 | `/api/storage` 응답에 `modified` 이미 포함, UI 미표시 | RelayServer.kt:1264-1284; WebAssets.kt 보관함 렌더 |
| 7 | HTTP→HTTPS 리다이렉트 `targetHost=call.request.local.localHost`가 다중 인터페이스에서 잘못된 IP 유도 / 사설 인증서 / IP게이트 | RelayServer.kt:271-284 |

## 결정 사항 (사용자 확정)
| 항목 | 결정 |
|------|------|
| 1 watchdog | 기본 **60초** 주기, **설정**(`watchdogIntervalSec`) 추가 |
| 1 부팅자동시작 | `RECEIVE_BOOT_COMPLETED` + BootReceiver → RelayService.start |
| 4 시더 중단 | 피어 100% 시더 없으면 **N초 대기 후 자동 일시정지**, **설정**(`torrentMinSeedWaitSec`, 기본 0=꺼짐) |
| 5 업로드 진행률 | XHR `upload.onprogress`로 진행률 바 + 완료/실패 toast, 버튼 옆 대상 폴더 라벨 |
| 7 검증 | 사용자가 직접 맥+iPad 폰 핫스팟 동시 접속 |
| 7 수정 | 리다이렉트 대상 호스트를 `lanAddress()`(핫스팟 IP) 우선 보정 |

## 신규 설정 (AppSettings)
| 필드 | 기본 | 타입 | 용도 |
|------|------|------|------|
| `watchdogIntervalSec` | 60 | Int | '이슈1' 헬스체크 주기(웹 설정) |
| `torrentMinSeedWaitSec` | 0 | Int | '이슈4' 시더 부재 시 대기 후 자동 중단(0=꺼짐) |

## 구현 단계 (기능별 커밋)
1. **ISSUE1** boot receiver + watchdog (RelayService, BootReceiver, Manifest, Settings)
2. **ISSUE2** 중복 가드 + 교차 매핑 해결 (TorrentEngine, RelayServer)
3. **ISSUE3** 속도 제한 경로 정리 + 오버플로우 방지 (TorrentEngine, RelayServer, WebAssets 설정)
4. **ISSUE4** 피어% 표시 + 시더 자동 중단 (TorrentEngine, WebAssets, TorrentScreen, Settings)
5. **ISSUE5** 업로드 진행률 (WebAssets)
6. **ISSUE6** 폴더/파일 날짜 (WebAssets, FilesScreen)
7. **ISSUE7** 리다이렉트 호스트 보정 (RelayServer)

## 검증 (DoD)
- 신규 단위 테스트: 토렌트 중복 가드, 오버플로우(coerceIn), findJobIdForNewTorrent
- ktlint + unit + `assembleRelease` + 실기기 설치
- 실기기 E2E: ①토렌트 병렬/중복 ②속도제한 실측 ③업로드 진행률 ④피어% ⑤(iPad는 사용자) ⑥폴더 날짜
- DebugPanel 로그 + CHANGELOG/TODO/세션 로그
