# 세션 2026-09-12 (android, Phase A)

## 무엇을
- Motrix-Next 차용 Phase A 구현: 설정 마이그레이션(P0-1) + 진단 번들/무결성 가드(P0-2)
- 신규: SettingsMigration.kt / PersistenceGuard.kt / DebugBundle.kt + 테스트 3종 13건
- 수정: SettingsRepository(configVersion+ensureMigrated) / Jobs·TorrentPersistence(손상 시 .bak) / DebugRoutes(/bundle+status 확장) / RelayService(기동 시 마이그레이션)

## 플랫폼
- Android (S22 실기기 R5CT215F4QK, Wi-Fi 10.154.225.186:8080)

## 빌드+PERF+CACHE
- testDebugUnitTest GREEN (신규 13건 포함 전체)
- ktlint 본문 GREEN (kts 파서 기존 이슈 `build.gradle.kts parse fail` 제외, 우리 변경과 무관)
- assembleDebug GREEN + 실기기 설치 성공
- PERF: 번들 ZIP level 0 패스스루+256KB 버퍼 (T-939 패턴 재사용), 메모리 버퍼 금지
- CACHE: 영향 없음

## 남은 TODO
- T-966 ✅ (E2E 4종 모두 PASS, 아래 전달로그 참조)
- 다음: Phase B (P0-3 쿠키 전달 + P1-4 파일명 + P1-6 트래커) — PLAN_v0.23 예정

## 전달로그 (E2E 실측)
1. 마이그레이션: `[I][Settings] [FEATURE] 설정마이그레이션 v0→v1 완료` + status `configVersion:1`
2. 번들: `200 9767B`, 7엔트리(logs/api-calls/metrics/settings/jobs/torrents/device), `webPassword:***` 마스킹 확인
3. 손상복구: corrupt jobs.json → `손상 파일 백업 jobs.json → jobs.json.bak` + `E-AND-DOWN-2003` + 앱 생존
4. 복구: 백업 복원 후 `/api/jobs []` + status 정상, 테스트 `.bak` 정리 완료

## 문서갱신
- docs/plans/PLAN_v0.22_phaseA_android.md 신설
- docs/TODO.md T-961~T-966 등록·완료
- docs/CHANGELOG.md 0.22.0 (미배포) 항목 추가
- error_message_ko.json 변경 없음 (기존 E-AND-DOWN-2003 재사용)

## 큐상태/E2E
- adb reverse는 Mac 8080 충돌로 사용 불가 → Wi-Fi 직결(10.154.225.186:8080)로 검증
- Full E2E: T-966 4종 PASS. 잔여 실사용 관찰 없음 (jobs 비어 있어 안전하게 손상 테스트 가능했음)
