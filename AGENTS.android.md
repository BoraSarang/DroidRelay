# AGENTS.android.md — DroidRelay 플랫폼 특화 규칙

> 공통 가이드(`~/.config/opencode/AGENTS.md` v2.3)를 참조만 하며 재작성하지 않는다. 본 파일은 이 프로젝트의 Android 빌드·배포 특화 사항만 담는다. (12.0)

## 적용 플랫폼
- `apps/android/` — Kotlin + Jetpack Compose Material 3 (S22 SM-S901N, One UI 8.0 / Android 16)

## 빌드
- JDK: Android Studio 내장 JBR 필수 → `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- 표준 진입점은 루트 디스패처 사용: `./build_and_run.sh debug|test|lint|clean|screenshot android`
- Gradle 9.5.0 wrapper · AGP 9.3.1(**kotlin.android 플러그인 금지 — AGP 9 내장 Kotlin**) · Compose 플러그인만 별도 적용

## 배포·디버깅
- 설치는 **USB adb만** 사용 (`adb install -r`)
- 무선 디버깅: 핫스팟 동시 불가(One UI 정책) → 사용 금지, USB adb로 통일
- 로그 확인: `adb logcat -s DroidRelay` (DebugLogger 태그)

## 릴리즈 서명·배포 (v0.13.3+)
- 릴리즈 빌드는 **신규 릴리즈 keystore**로 서명: `apps/android/keystore/droidrelay-release.jks` + `keystore.properties`(둘 다 gitignore, 절대 커밋 금지)
- keystore는 **백업 필수** — 분실 시 재생성 불가(기존 설치 업그레이드 불가). 비밀번호는 `keystore.properties`에만 존재
- 빌드: `JAVA_HOME=... ./gradlew :app:assembleRelease` → `app-release.apk` 서명 확인(`apksigner verify`), SHA-256 `2f1dc84a…`
- GitHub Release: `git tag v0.13.3 && gh release create v0.13.3 <apk>` — 서명 APK만 첨부
- 실기기 서명 전환 시(debug→release): `adb uninstall` 후 `install` (INSTALL_FAILED_UPDATE_INCOMPATIBLE 방지 — 기존 데이터 삭제됨)

## 네트워크 제약 (설계 전제)
- 맥/iPad는 S22 Wi-Fi 핫스팟 경유 인터넷 → 셀룰러 대역폭 낮음(~400KB/s)·단절 잦음
- 서버 포트 8080 고정(E-AND-DOWN-1004), 폰 핫스팟 IP는 `swlan0` 인터페이스
- 대용량 다운로드는 반드시 DroidRelay 앱 경유(폰 LTE 직접 + Range 이어받기)

## 금지 사항
- Flutter/RN 등 크로스플랫폼 도입 (AGENTS.md 네이티브 원칙)
- USB 테더링 의존 기능 추가 (동작 불가 확정)
