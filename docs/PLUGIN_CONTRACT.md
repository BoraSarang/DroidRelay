# PLUGIN_CONTRACT.md — DroidRelay 연동 구현 기록 v2

> 규칙 원천: RelayConsole `docs/PLUGIN_SDK.md` v2 (유일 원천 — 규칙 변경은 거기서 한다).
> 본 문서는 DroidRelay 제공자 구현 기록만 남긴다.
> 제공자: DroidRelay (Android, `com.borasarang.droidrelay`) · 소비자: RelayConsole (macOS) 등

## 구현 상태

* v2 승격됨 (T-1096, `feat/android-plugin-sdk-v2`). L2 체크리스트 전부 충족.
* `announce`는 T-1090 머지 후 v2 확장으로 연기.

## 구현 매핑 (SDK 절 ↔ 코드)

| SDK | 구현 |
|---|---|
| §2 프로브 | `.receiver.PluginProbeReceiver` (명시적 브로드캐스트, 앱 미실행 응답, UI 없음) + manifest meta-data 3키(`version=2`) |
| §3 Provider | `.plugin.PluginInfoProvider` (`content://com.borasarang.droidrelay.plugin/info`, 1행 7컬럼, `actionsJson` 4종). `iconBase64`는 빈값 → 소비자 폴백 |
| §4 액션 | `.plugin.PluginActionReceiver` (`PLUGIN_ACTION`, `--es cmd`/`--es arg`). MainActivity 전면 경로는 v2에서 제거 |
| §5 REMOTE | `PluginContract` 빌더 + `PluginLog` 직접 `Log` 출력. `action=`·`ok=` 전부, 실패 `errorCode=` + 꼬리 `note=` |
| §6 EVENT | 완료 분기 옆 1회 emit. 완료 `level=info`·실패 `level=warning`. 파일명은 공백 sanitize (`token()`, §5.2 값 규칙) |
| §7 ON/OFF | 설정 `외부 연동 허용` (기본 ON, 보안 섹션). OFF면 거부 로그 + 이벤트 침묵. 소비자 토글과 AND |
| §8 단일 emit | `DebugLogger` 미러 없음 — 한 사건 한 줄 |

## 소비자 전달 (복붙용)

```bash
adb -s <serial> shell pm list packages | grep com.borasarang.droidrelay
adb -s <serial> shell am broadcast -a com.borasarang.droidrelay.PLUGIN_PROBE -n com.borasarang.droidrelay/.receiver.PluginProbeReceiver
adb -s <serial> shell "logcat -d -s DroidRelay:*" | grep PLUGIN
adb -s <serial> shell content query --uri content://com.borasarang.droidrelay.plugin/info
adb -s <serial> shell am broadcast -a com.borasarang.droidrelay.PLUGIN_ACTION -n com.borasarang.droidrelay/.plugin.PluginActionReceiver --es cmd server_status
```

> 주의 (실측): 액션 호출에 `-n` 명시적 컴포넌트가 필수다. `-a`만 쓴 암시적 브로드캐스트는
> Android 8+ manifest 수신기 제한으로 도달하지 않는다 (`Broadcast completed`여도 무응답).
> SDK §4.1 예시에 `-n <package>/.plugin.PluginActionReceiver` 추가가 필요하다 — RelayConsole 측 반영 요망.

## 변경 이력

* v1 (2026-10-08): 액션 4종 + 결과/이벤트 로그. MainActivity 전면 경로. (T-1095)
* v2 (2026-10-08): SDK v2 승격 — 수신기 `.receiver` 이동 + `version=2`·`appVersion` + 메타 Provider + 브로드캐스트 액션 + EVENT `level=` + 단일 emit + 파일명 sanitize. 전면 경로 제거. (T-1096)
