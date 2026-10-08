# PLUGIN_CONTRACT.md — DroidRelay 연동 계약 v1

> 제공자: DroidRelay (Android, `com.borasarang.droidrelay`) · 소비자: RelayConsole (macOS) 등
> 원칙: 서로 간섭 없음. 둘 다 꺼져 있어도 각자 정상 동작. 상시 연결 없음 (adb 요청 시점에만 만남).
> 원천: SpotShift `docs/PLUGIN_CONTRACT.md` v1.1 패턴을 따른다 (프로브 + fire-and-forget + logcat 스크랩).
>
> 상태: **제공자 구현됨** (T-1095, `feat/android-plugin-contract`).

## 1. 명찰 (Discovery)

DroidRelay는 능력을 두 겹으로 선언한다.

* 정적 명찰: `AndroidManifest`의 application meta-data 3키 (문서용 선언).
  * `droidrelay.plugin.version=1`
  * `droidrelay.plugin.actions=server_status,server_control,download_add,torrent_add`
  * `droidrelay.plugin.logTag=DroidRelay`
* 실제 discovery: 명시적 브로드캐스트 프로브로 질의한다. 앱 꺼져 있어도 응답하며 UI 없음, 상시 연결 없음.

```bash
adb -s <serial> shell pm list packages | grep com.borasarang.droidrelay
adb -s <serial> shell am broadcast -a com.borasarang.droidrelay.PLUGIN_PROBE \
  -n com.borasarang.droidrelay/.plugin.PluginProbeReceiver
adb -s <serial> shell "logcat -d -s DroidRelay:*" | grep PLUGIN
```

응답 한 줄 형식:

```
[PLUGIN] version=1 actions=server_status,server_control,download_add,torrent_add logTag=DroidRelay allowed=<true|false>
```

* `연동 허용` OFF여도 응답은 한다 (`allowed=false`로 꺼짐 상태 전달). 무응답 = 미설치 또는 구버전(계약 없음).
* 형식 규칙: `key=value`, 값은 공백·콤마 전까지. `actions`는 콤마 구분. 필드 순서·키 이름을 바꾸면 계약 버전 올림.

## 2. 액션 v1 (fire-and-forget, 결과 대기 없음)

호출은 `MainActivity(singleTop)` 경유. 앱 미실행 시 콜드스타트 후 실행, 실행 중이면 즉시 실행. 성공/실패는 §3 로그로만 전달. 호출 측에 콜백 없음.

| 액션 | 호출 | 비고 |
|---|---|---|
| `server_status` | `adb -s <serial> shell am start -n com.borasarang.droidrelay/.MainActivity --es droidrelay.server status` | 현재 서버 상태 1회 보고 (§3 `action=server_status`) |
| `server_control` | `adb -s <serial> shell am start -n com.borasarang.droidrelay/.MainActivity --es droidrelay.server start` (또는 `stop`) | `RelayService.start/stop` 재사용. `start`는 이미 떠 있으면 유지 |
| `download_add` | `adb -s <serial> shell am start -n com.borasarang.droidrelay/.MainActivity --es droidrelay.download_add "<https-url>"` | `DownloadEngine.enqueue` 재사용. 중복 URL은 등록 없이 안내 |
| `torrent_add` | `adb -s <serial> shell am start -n com.borasarang.droidrelay/.MainActivity --es droidrelay.torrent_add "<magnet:...>"` | `TorrentEngine.addMagnet` 재사용. 중복 magnet은 등록 없이 안내 |

* 입력 검증: 빈 값·비URL·비magnet은 실행 없이 실패 로그 (§3 `ok=false`). `download_add`는 `http(s)://`만 받는다.
* `announce` (수동 "나 여기있소" 발신)는 v1 범위 밖 — T-1090 브랜치 미머지이므로 v1.1로 연기.

## 3. 결과 로그 (Report)

태그: `DroidRelay` (`adb -s <serial> shell logcat -d -s DroidRelay:*`).

| 상황 | 한 줄 형식 |
|---|---|
| 서버 상태 | `[REMOTE] action=server_status ok=true running=<true\|false> ip=<ip\|-> port=<port> version=<version>` |
| 서버 제어 | `[REMOTE] action=server_control ok=true op=<start\|stop> running=<true\|false>` |
| 다운로드 등록 | `[REMOTE] action=download_add ok=true id=<jobId> duplicate=<true\|false>` |
| 토렌트 등록 | `[REMOTE] action=torrent_add ok=true id=<torrentId> duplicate=<true\|false>` |
| 실패 (공통) | `[REMOTE] action=<action> ok=false errorCode=<E-AND-PLG-....> note=<원인 한 줄>` |
| 거부 (연동 OFF) | `[REMOTE] 거부됨 (연동 OFF)` |

* `duplicate=true`는 실패가 아니다 — 이미 있는 항목이라 새로 만들지 않았다는 안내다.
* 에러코드 (예정, 구현 시 `error_message_ko.json` 등록): `E-AND-PLG-0001` 연동 거부, `E-AND-PLG-0002` 중복 아님·입력 무효, `E-AND-PLG-0003` 실행 실패(원인 포함). 원인 없는 실패 표시 금지.
* 형식은 정규식으로 파싱 가능해야 하며, 필드 순서·키 이름을 바꾸면 계약 버전 올림.

## 4. 이벤트 로그 (Event, 제공자발)

완료 알림은 요청에 대한 답이 아니라 제공자가 먼저 내는 소식이다. 별도 구독 없이 §3과 같은 logcat 스크랩으로 읽는다. macOS 소비자는 adb 너머라 브로드캐스트 푸시를 받을 수 없으므로 이 방식만 쓴다.

| 상황 | 한 줄 형식 | 원천 |
|---|---|---|
| 다운로드 완료 | `[EVENT] type=download_complete id=<jobId> filename=<name> bytes=<n>` | `RelayService` RUNNING→DONE 분기 옆 |
| 다운로드 실패 | `[EVENT] type=download_failed id=<jobId> filename=<name> errorCode=<code>` | RUNNING→FAILED 분기 옆 (동일 원인 1회만) |
| 토렌트 완료 | `[EVENT] type=torrent_complete id=<torrentId> name=<name>` | 토렌트 완료 알림 분기 옆 |
| 토렌트 실패 | `[EVENT] type=torrent_failed id=<torrentId> name=<name> errorCode=<code>` | 토렌트 실패 분기 옆 |

* 보장 배송 아님 — logcat 링버퍼라 폴링 사이 유실 가능. 소비자는 `id`로 중복 제거하고 마지막 한 건을 진실로 본다.
* 제공자 측 폴링 없음 (완료 분기에서 1회 emit, 배터리 영향 없음). `연동 허용` OFF면 이벤트도 suppression.
* 전이 지점: 시스템 다운로드/토렌트 알림과 같은 전이 지점에서 발행한다. 다운로드 알림 토글(`notifications`)이 OFF면 알림과 함께 이벤트도 나가지 않는다.

## 5. 릴리즈 빌드 요구 (계약 조건)

* 계약 로그(`[PLUGIN]`·`[REMOTE]`·`[EVENT]`)는 **`android.util.Log` 직접 출력**이어야 한다. `DebugLogger` 경유 금지.
* 이유: `DebugLogger.write`는 `BuildConfig.DEBUG` false면 조기 반환이라 릴리즈 서명 APK에서 계약 로그가 전부 사라진다 (SpotShift 동일 함정). 실기기 릴리즈에서 `logcat -d -s DroidRelay:*`로 계약 줄이 보여야 계약 충족이다.

## 6. ON/OFF

* DroidRelay 설정 `연동 허용` (기본 ON). OFF면 액션 무시 + 거부 로그 (§3). 이벤트도 미발행 (§4).
* 소비자 측도 자체 토글 보유. 양쪽 AND — 한쪽이라도 OFF면 조용히 각자.
* 거부·미지원은 에러코드 + 원인 포함 (원인 없는 실패 표시 금지).

## 7. 버전 규칙

* 계약 버전은 정수 올림만. 필드 추가는 기존 파서 안 깨지는 선에서 허용.
* 모르는 버전·모르는 액션 만나면 소비자는 연동 끄고 `미지원` 표시 (추측 실행 금지).

## 8. 소비자 구현 가이드 (RelayConsole)

SpotShift 연동(`Sources/RelayConsole/Droid/PluginDiscovery.swift`·`PluginStore.swift`·`Views/PluginView.swift`)과 동일 패턴으로 구현한다.

1. 설치 확인: `pm list packages`에 `package:com.borasarang.droidrelay` 존재 여부 (`isInstalled` 재사용, 패키지명만 교체).
2. 기능 확인: §1 프로브 발송 → 2초 대기(콜드스타트 여유, 실측 기준) → `logcat -d -s DroidRelay:*` 덤프에서 **마지막** `[PLUGIN]` 파싱. 무응답이면 미지원. `version==1` + 액션 교집합이 있을 때만 연동.
3. 동작: 플러그인 탭에 DroidRelay 카드 추가 + 소비자 토글(`relay.plugin.droidrelay.enabled`) → 액션 버튼 4종 (상태 조회·서버 시작/정지·URL 추가·magnet 추가). 액션은 fire-and-forget, 요청 후 `pendingSince` 표시.
4. 결과: `logcat -d -s DroidRelay:*` 스크랩 → **마지막** `[REMOTE]` 표시. 이벤트는 같은 덤프에서 `[EVENT]` 마지막 건 표시. 폴링에 태우지 않고 수동 새로고침만 (폴링 다이어트).
5. 실행: `ProcessRunner.run(adb, args, timeout: 20)` 백그라운드 (`Task.detached(.utility)`), serial별 `busy`·`lastError`·`lastMessage` 유지. 실패 표시는 `[표시②]` 규칙 — 외부 명령 stderr·에러코드를 그대로 보여주고 원인 삭제 금지. 기기 지칭은 `[표시①]` 규칙 — serial 원문 사용, 마스킹 금지.
6. L10n: `plugin.droidrelay.*` 키를 en·ko에 같은 키명으로 동시 추가 (개수 1:1). 기존 `plugin.*` (SpotShift) 키와 별도.

파싱 테스트 벡터:

```
[PLUGIN] version=1 actions=server_status,server_control,download_add,torrent_add logTag=DroidRelay allowed=true
[REMOTE] action=download_add ok=true id=abc123 duplicate=false
[REMOTE] action=server_control ok=true op=start running=true
[REMOTE] 거부됨 (연동 OFF)
[EVENT] type=download_complete id=abc123 filename=a.mp4 bytes=12345
```

## 9. 변경 이력

* v1 (2026-10-08): 최초 — 액션 4종 + 결과/이벤트 로그. `announce`는 T-1090 머지 후 v1.1로 연기. (T-1095)
* v1 구현 (2026-10-08): 제공자 구현 완료 — `plugin/` 3파일 + 설정 `연동 허용` + MainActivity 액션 분기 + 완료 분기 이벤트 4곳 + `E-AND-PLG-0001~0003`. (T-1095)
