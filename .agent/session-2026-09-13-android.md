# 세션 2026-09-13 (android, T-1004 + T-1005~T-1019 파비콘·홈상태·바로가기)

## 무엇을
- 서명 URL 파일명 오저장 수정 (Godot tpz → file-ts... 폴백 버그)
- 쿼리 response-content-disposition + 일반 filename 쿼리 우선, 응답 Content-Disposition 헤더 1회 교정

## 플랫폼
- android (Kotlin, UI 변경 없음)

## 빌드+PERF+CACHE
- testDebugUnitTest 전체 GREEN (JobsRepository 12건 + ContentDisposition 7건 포함)
- ktlint 본문 GREEN (kts 파서 기존 이슈 제외)
- assembleDebug + 실기기 설치 성공 (Success)
- 전송 경로 무변경 → PERF/CACHE 예산 영향 없음

## 남은TODO
- 사용자 실기기 확인 (문제 URL 등록 → tpz 이름 + 완료 검증)
- full/E2E full은 사용자 허락 후 (현재 미실행)

## 전달로그
- [FEATURE] Content-Disposition 교정 id=... 'old' → 'new' (폴백일 때만)
- 파일명 결정(쿼리 우선/URL 그대로/범용 폴백) DebugLogger.d

## 문서갱신
- PLAN_v0.32 신규, TODO T-1004 ✅, CHANGELOG 0.32.0(미배포)
- error_message_ko.json 변경 없음 (신규 에러코드 없음)

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리

## E2E
- 실기기 E2E는 사용자 직접 진행 예정

---

## 파비콘 세션 추가분 (T-1005~T-1009)

## 무엇을
- 웹 파비콘/북마크 아이콘 완전 대응 (내장 웹 `/`+`/debug` + Pages 랜딩)
- 앱 런처 벡터 → SVG 이식 + rsvg-convert PNG 16/32/180 + webmanifest

## 플랫폼
- android (Ktor 서버) + web (대시보드/디버그 head) + docs 랜딩

## 빌드+PERF+CACHE
- FaviconTest 7건 GREEN + testDebugUnitTest 전체 GREEN
- ktlint: `.kts` 파서 실패는 기존 이슈(build.gradle.kts 미수정) — 본문 .kt는 ktlint 대상 아님
- assembleDebug + 실기기 설치 Success, APK에 assets/web 5종 포함 확인
- 아이콘만 장기 캐시(86400s) → PERF 영향 없음

## 남은TODO
- 사용자 실기기 확인: 앱에서 서버 시작 → http://폰IP:8080 탭/북마크 아이콘 + iOS 홈화면 추가目視 (서버 미기동 상태라 실서버 curl은 미실시 — 포커스 스틸 방지)

## 전달로그
- [INFO] [FEATURE] 파비콘 응답 /favicon.svg (NNNB)

## 문서갱신
- PLAN_v0.33 신규, TODO T-1005~T-1009 ✅, CHANGELOG 0.33.0(미배포)
- error_message_ko.json 변경 없음 (신규 에러코드 없음)

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리

## E2E
- full/E2E full은 사용자 허락 후 (현재 미실행)

---

## 홈 실행상태·HTTPS포트 세션 추가분 (T-1010~T-1016)

## 무엇을
- 홈 ServerCard 최상단 실행상태 (`●` + HTTP·HTTPS 실행 중/대기 중/에러)
- HTTPS 포트 설정화 (기본 8443, 충돌 시 E-AND-SRV-0111)
- 하드코딩 8080 정리 (홈 QR/주소·알림 이중포트 `http://ip:8080:8080`·터널 URL) + `PORT` 상수 삭제
- `/api/info`에 `httpsPort` 포함

## 플랫폼
- android (Kotlin + Compose, 웹 대시보드 변경 없음)

## 빌드+PERF+CACHE
- testDebugUnitTest 129건 GREEN (HttpsPortTest 6건 포함, failures 0)
- ktlint 본문 GREEN (kts 파서 기존 이슈 제외 — build.gradle.kts 미수정)
- assembleDebug + 실기기 설치 성공 (Success)
- 설정값 배선만 → PERF/CACHE 예산 영향 없음

## 남은TODO
- 사용자 실기기 확인: 홈 dot, 설정 HTTPS 변경→재시작, https://폰IP:8443 접속, 알림 문구
- full/E2E full은 사용자 허락 후

## 전달로그
- [INFO] [FEATURE] HTTPS 포트 변경 {old} → {new} (설정 적용 시)
- [INFO] [FEATURE] HTTPS 포트 변경 감지 {http}/{https} → … — 서버 재시작
- [INFO] [FEATURE] HTTPS 포트 기동 완료 http://… + https://…

## 문서갱신
- PLAN_v0.34 신규, TODO T-1010~T-1016 ✅, CHANGELOG 0.34.0(미배포)
- error_message_ko.json에 E-AND-SRV-0111 추가

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리

## E2E
- 실기기 E2E는 사용자 직접 진행 예정

---

## QR 카드 바로가기 세션 추가분 (T-1017~T-1019)

## 무엇을
- 홈 ServerCard에 HTTP+HTTPS 두 바로가기 링크 (탭하면 브라우저)
- 복사 두 줄, 공유 텍스트 두 주소 포함 (QR은 HTTP 유지)

## 플랫폼
- android (Compose UI만)

## 빌드+PERF+CACHE
- testDebugUnitTest GREEN + assembleDebug + 실기기 설치 성공 (Success)
- 표시만 → PERF/CACHE 영향 없음

## 남은TODO
- 사용자 실기기 확인: 두 링크 탭 이동 (HTTPS는 자체서명 경고 후 진행)
- full/E2E full은 사용자 허락 후

## 전달로그
- UI 표시 변경이라 신규 로그 없음

## 문서갱신
- PLAN_v0.35 신규, TODO T-1017~T-1019 ✅, CHANGELOG 0.35.0(미배포)

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리

## E2E
- 실기기 E2E는 사용자 직접 진행 예정

---

## 릴리즈 세션 추가분 (v0.35.0 배포)

## 무엇을
- v0.21.1~v0.35.0 묶음 릴리즈 배포 (versionCode 27, versionName 0.35.0)
- README·랜딩 업데이트 (v0.13.3→0.35.0, HTTP/HTTPS 듀얼 포트), CHANGELOG 미배포 12건 정리

## 플랫폼
- android (릴리즈 APK)

## 빌드+PERF+CACHE
- test GREEN + assembleRelease 성공, apksigner 서명 확인 (CN=DroidRelay, SHA-256 2f1dc84a…)
- APK 75MB, gh release 업로드 (타임아웃 후 draft→edit으로 재처리)

## 남은TODO
- 사용자 실기기에서 서명 APK 설치 확인 (debug 설치 시 uninstall 필요)

## 전달로그
- gh release v0.35.0 (tag 연결, draft 해제, app-release.apk 첨부)

## 문서갱신
- README·docs/index.html v0.35.0 반영, CHANGELOG 0.35.0 "릴리즈" + 미배포 표시 제거
- TODO.md는 그대로 (릴리즈 마커), 태그 v0.35.0 + 푸시 완료

## 큐상태
- beads DB 없음 → TODO/CHANGELOG로만 관리
