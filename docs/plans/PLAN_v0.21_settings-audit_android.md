# PLAN v0.21 — 설정 감사 + 업로드 최소화 (android)

> 53필드 전수 감사 결과 데드 4종 발견. 목표(업로드 최소화) 직결 배선 묶음.

## 감사 결과 (T-955)
- **적용됨 49종**: 엔진·데몬·라우트에서 실제 읽음 (상세 표는 TODO T-955 행·세션 로그 참조).
- **데드 4종**: `torrentSavePath`(하드코딩 `/sdcard/Download/DroidRelay`), `torrentSeedRatio`(미참조),
  `torrentDhtEnabled`(기동 시 무조건 startDht), `torrentPexEnabled`(libtorrent4j에 토글 API 없음).

## 배선 (T-956~959)
- **T-956 시드 비율 강제**: 폴링에서 SEEDING/DONE 잡의 `totalUpload/totalDownload` ≥ 설정 비율이면
  `th.pause()` + PAUSED + 로그. `0` = 제한 없음. 기본값 2.0 유지.
- **T-957 DHT 토글**: `startDht/stopDht/isDhtRunning` 활용 — `applySettings` 반영 + 기동 시 분기.
- **T-958 저장 경로**: `storageDir`·`moveToStorage`가 설정값 사용 (기본값 기존 경로, 마이그레이션 없음).
- **T-959 PEX 안내 + 프리셋**: PEX 토글 유지 + "피어 탐색 보조 (트래픽 미미)" 문구.
  "업로드 최소화" 원터치: 비율 0.5 + 업로드 32KB/s + DHT 끔 (앱/웹).

## 검증 게이트 (T-960)
- `test` + `lint`(스크립트 제외) + `assembleDebug` GREEN → 실기기 E2E(비율 도달 일시정지·DHT 토글·저장경로) →
  CHANGELOG + 세션 로그 + 커밋/푸시(요청 시).
