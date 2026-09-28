#!/bin/bash
# usage: ./build_and_run.sh [debug|test|lint|clean|a11y|devices] [android|macos]
# AGENTS.md 9장 빌드 디스패처 — DroidRelay Android
#
# 디바이스 선택: 같은 폰이 USB 와 무선 adb 로 동시에 잡히면 `adb <cmd>` (대상 미지정) 가
# "more than one device" 로 실패한다. 그래서 모든 adb 호출은 반드시 `-s "$SERIAL"` 을 쓴다.
# 선택 순서: $DRD_SERIAL → 1대면 그 대로 → 여러 대면 **USB 우선** (무선이 남아 있는 경우가 많다)
#          → USB 도 없으면 임의 선택 대신 명시적으로 실패 (오설치 위험)
set -e
CMD="${1:-debug}"
# 플랫폼은 $2. 다만 **숫자면 예전 방식**(`. build_and_run.sh run 8` = 8초 대기)으로
# 해석해 기존 호출을 깨지 않는다. 두 번째 인자를 플랫폼으로generic 재정의한 결과로
# `sleep "$2"` 가 "android" 을 받아 에러가 났다.
ARG2="${2:-}"
if printf '%s' "$ARG2" | grep -qE '^[0-9]+$'; then
  LEGACY_SLEEP="$ARG2"; PLATFORM="android"
else
  LEGACY_SLEEP=""; PLATFORM="${ARG2:-android}"
fi
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/apps/android"
MACOS_DIR="$ROOT/apps/macos"
PKG="com.borasarang.droidrelay"
APP_NAME="DroidRelay"
APP_ID="com.borasarang.droidrelay.mac"
# SwiftPM **타깃** 이름. 번들 안에서는 APP_NAME 으로 바꿔 넣으므로 둘이 다르다.
# (번들 실행 파일명 ≠ 빌드 산출물명 — Info.plist 의 CFBundleExecutable 과
#  MacOS/ 안의 실제 파일명이 일치하기만 하면 되고, 그 일치를 APP_NAME 이 보장한다)
MACOS_TARGET="DroidRelayMac"

# ── 디바이스 선택 ──────────────────────────────────────────
# 결과: 직렬번호 / NO_DEVICE / MULTIPLE 중 하나를 stdout 으로 낸다
pick_device() {
  local connected
  connected=$(adb devices | awk '$2=="device"{print $1}')
  local n
  n=$(printf '%s' "$connected" | grep -c . || true)

  if [ -n "$DRD_SERIAL" ]; then
    if printf '%s\n' "$connected" | grep -qx "$DRD_SERIAL"; then
      echo "$DRD_SERIAL"; return 0
    fi
    echo "INVALID_SERIAL"; return 0
  fi
  if [ "$n" = "0" ]; then echo "NO_DEVICE"; return 0; fi
  if [ "$n" = "1" ]; then printf '%s\n' "$connected" | head -1; return 0; fi

  # 여러 대 — USB 트랜스포트가 있으면 그쪽이 안정적 (무선은 USB 를 뽑아도 남아 있다)
  # 트랜스포트 열($3)은 `adb devices -l` 에만 나온다. `-l` 없이는 판별 불가.
  local usb
  usb=$(adb devices -l | awk '$2=="device" && $3 ~ /^usb:/{print $1}' | head -1)
  if [ -n "$usb" ]; then echo "$usb"; return 0; fi
  echo "MULTIPLE"
}

# pick_device 결과를 검증하고 $SERIAL 에 넣는다. 실패 시 exit.
# 선택과 사용 사이에 기기가 사라질 수 있다 — USB 케이블이 흔들리면 실제로 그렇다.
# 그래서 살아 있는지 한 번 더 확인하고, 사라졌으면 다른 후보로 한 번 더 시도한다.
device_alive() { adb devices | awk '$2=="device"{print $1}' | grep -qx "$1"; }

resolve_device() {
  local d attempt
  for attempt in 1 2; do
    d=$(pick_device)
    case "$d" in
      NO_DEVICE)
        if [ "$attempt" = "1" ]; then sleep 1; continue; fi
        echo "⚠️  연결된 디바이스 없음"; return 1 ;;
      INVALID_SERIAL)
        echo "❌ DRD_SERIAL='$DRD_SERIAL' 이(가) 연결 목록에 없음"; return 1 ;;
      MULTIPLE)
        echo "❌ 디바이스가 여러 대 연결됨 — $DRD_SERIAL 로 지정하세요:"
        adb devices -l | awk '$2=="device"{printf "     %s  %s %s\n",$1,$4,$5}'
        return 1 ;;
      *)
        if device_alive "$d"; then SERIAL="$d"; return 0; fi
        echo "… $d 가 응답하지 않아 다시 선택합니다"
        sleep 1 ;;
    esac
  done
  echo "❌ 연결된 디바이스가 안정적이지 않습니다. USB 케이블/무선 연결을 확인하세요."
  return 1
}

# JAVA_HOME: Android Studio JBR 우선, 그 외 시스템 Java
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
elif [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi

# ── macOS ───────────────────────────────────────────────────
# `.app` 번들을 만들어 /Applications 에 설치한다. **번들 없이는 못 쓴다** —
# SwiftUI/SwiftUI 를 쓰는 앱은 Info.plist 없이 실행하면 즉시 죽거나,
# Dock 아이콘이 뜨고(메뉴바 앱인데) macOS 가 경고를 띄운다.
macos_bundle() {
  local bin="$1" app="$2" cfg="${3:-release}"
  rm -rf "$app"
  mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
  cp "$bin" "$app/Contents/MacOS/$APP_NAME"
  chmod +x "$app/Contents/MacOS/$APP_NAME"

  # LSUIElement = 앱이 Dock/Cmd-Tab 에 뜨지 않는다(메뉴바 전용).
  # 코드가 NSApp.setActivationPolicy(.accessory) 로도 설정하지만 그건 **실행 뒤**이므로
  # 번들로는 이 플래그가 있어야 첫 프레임부터 Dock 에 안 뜬다.
  cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>$APP_NAME</string>
    <key>CFBundleDisplayName</key><string>$APP_NAME</string>
    <key>CFBundleExecutable</key><string>$APP_NAME</string>
    <key>CFBundleIdentifier</key><string>$APP_ID</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>0.1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

  # ad-hoc 서명 — M4(자체 서명·공증)의 자리막이. 서명 없으면 Gatekeeper 가
  # "개발자가 확인하지 않은 앱" 으로 막는다. 정식 배포에선 여기서 실제 키로 교체한다.
  codesign --force --deep --sign - "$app" 2>/dev/null \
    && echo "   · ad-hoc 서명 완료" \
    || echo "   ⚠ ad-hoc 서명 실패 — 실행 시 Gatekeeper 차단 가능"
}

# 설치 위치는 **사용자 앱 폴더**. /Applications 는 시스템 영역이라
# 승인 프롬프트·권한 문제가 붙고, 사용자 폴더는 본인만 다루는 영역이라
# 빌드 스크립트가 건드려도 된다. (사용자 지정)
MACOS_APPS_DIR="$HOME/Applications"

macos_install() {
  mkdir -p "$MACOS_APPS_DIR"
  local app="$MACOS_APPS_DIR/$APP_NAME.app"
  # 실행 중이면 먼저 종료 — 덮어쓰는 동안 실행 파일이 묶여 있으면 실패한다
  pkill -x "$APP_NAME" 2>/dev/null && { echo "   · 실행 중이라 종료함"; sleep 1; } || true
  rm -rf "$app"
  cp -R "$MACOS_DIR/.build/$APP_NAME.app" "$app"
  echo "✅ 설치 완료: $app"
}

case "$PLATFORM" in
  android) cd "$ANDROID_DIR" ;;
  macos)
    cd "$MACOS_DIR"
    export PATH="/usr/bin:$PATH"
    ;;
  *) echo "❌ 알 수 없는 플랫폼: $PLATFORM (android|macos)"; exit 1 ;;
esac

case "$CMD" in
  devices)
    echo "── 연결된 디바이스 ──"
    adb devices -l
    echo
    d=$(pick_device)
    case "$d" in
      NO_DEVICE)   echo "선택: 없음" ;;
      MULTIPLE)    echo "선택: 불가 (여러 대 — DRD_SERIAL 지정 필요)" ;;
      INVALID_SERIAL) echo "선택: 불가 (DRD_SERIAL='$DRD_SERIAL' 없음)" ;;
      *)           echo "선택: $d  (이 기기로 진행)" ;;
    esac
    ;;
  debug)
    if [ "$PLATFORM" = "macos" ]; then
      echo "🔨 swift build (release) 중…"
      swift build -c release 2>&1 | tail -3
      BIN=$(swift build -c release --show-bin-path)/$MACOS_TARGET
      if [ ! -x "$BIN" ]; then echo "❌ 바이너리 생성 실패: $BIN"; exit 1; fi
      echo "📦 .app 번들 생성 중…"
      macos_bundle "$BIN" "$MACOS_DIR/.build/$APP_NAME.app" release
      echo "📲 $MACOS_APPS_DIR 에 설치 중…"
      macos_install
      echo
      echo "실행   : ./build_and_run.sh run macos"
      echo "진단   : ./build_and_run.sh diagnose macos"
      exit 0
    fi
    echo "🔨 assembleDebug 빌드 중…"
    ./gradlew assembleDebug -q 2>&1 | tail -3
    APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
    if [ -z "$APK" ]; then
        echo "❌ APK 생성 실패"; exit 1
    fi
    if ! resolve_device; then
        echo "   APK: $APK"
        exit 1
    fi
    echo "📲 $SERIAL 에 설치 중…"
    adb -s "$SERIAL" install -r "$APK" 2>&1 | tail -1
    echo "✅ 빌드·설치 완료"
    ;;
  run)
    if [ "$PLATFORM" = "macos" ]; then
      # 메뉴바 앱은 창이 없으므로 `open` 이 "아무 일도 없다" 고 보인다.
      # 프로세스가 실제로 떴는지까지 확인해야 사용자가 "설치됐다"고 믿을 수 있다.
      pkill -x "$APP_NAME" 2>/dev/null && sleep 1 || true
      open -a "$APP_NAME" 2>&1 | tail -1
      sleep 3
      if pgrep -x "$APP_NAME" >/dev/null; then
        echo "✅ 실행됨 (pid $(pgrep -x "$APP_NAME" | head -1)) — 메뉴바 우측 상단에서 아이콘을 찾으세요"
      else
        echo "❌ 실행되지 않음. diagnosing 하세요:"
        echo "   $MACOS_DIR/.build/$APP_NAME.app/Contents/MacOS/$APP_NAME --diagnose"
        exit 1
      fi
      exit 0
    fi
    # 설치만 하지 않고 앱을 재시작 — 대시보드가 새 HTML 을 서빙하도록
    if ! resolve_device; then exit 1; fi
    echo "🔄 $PKG 재시작 ($SERIAL)…"
    adb -s "$SERIAL" shell am force-stop "$PKG" || true
    sleep 1
    adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep "${LEGACY_SLEEP:-5}"
    echo "✅ 실행됨"
    ;;
  diagnose)
    if [ "$PLATFORM" = "macos" ]; then
      BIN="$MACOS_DIR/.build/$APP_NAME.app/Contents/MacOS/$APP_NAME"
      [ -x "$BIN" ] || BIN=$(swift build --show-bin-path)/$MACOS_TARGET
      echo "🔍 진단 실행…"
      "$BIN" --diagnose
      exit $?
    fi
    echo "❌ diagnose 는 macOS 전용입니다 (android 는 'log')"
    exit 1
    ;;
  uninstall)
    if [ "$PLATFORM" = "macos" ]; then
      pkill -x "$APP_NAME" 2>/dev/null && echo "   · 실행 종료" || true
      rm -rf "$MACOS_APPS_DIR/$APP_NAME.app" "$MACOS_DIR/.build/$APP_NAME.app"
      echo "✅ 제거 완료: $MACOS_APPS_DIR/$APP_NAME.app"
      exit 0
    fi
    if ! resolve_device; then exit 1; fi
    adb -s "$SERIAL" uninstall "$PKG" 2>&1 | tail -1
    ;;
  run)
    # 설치만 하지 않고 앱을 재시작 — 대시보드가 새 HTML 을 서빙하도록
    if ! resolve_device; then exit 1; fi
    echo "🔄 $PKG 재시작 ($SERIAL)…"
    adb -s "$SERIAL" shell am force-stop "$PKG" || true
    sleep 1
    adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep "${LEGACY_SLEEP:-5}"
    echo "✅ 실행됨"
    ;;
  log)
    if ! resolve_device; then exit 1; fi
    adb -s "$SERIAL" logcat -d -t "${LEGACY_SLEEP:-200}" "$PKG:V" "*:S"
    ;;
  test)
    if [ "$PLATFORM" = "macos" ]; then
      swift test 2>&1 | grep -E "Executed .* tests|error:|BUILD" | tail -6
    else
      ./gradlew test 2>&1 | grep -E "BUILD|FAIL|tests" | tail -5
    fi
    ;;
  lint|ktlint)
    ./gradlew ktlintCheck 2>&1 | tail -10
    ;;
  clean)
    if [ "$PLATFORM" = "macos" ]; then
      pkill -x "$APP_NAME" 2>/dev/null || true
      swift package clean 2>/dev/null || rm -rf "$MACOS_DIR/.build"
      rm -rf "$MACOS_DIR/.build/$APP_NAME.app"
      echo "🧹 클린 완료 ($MACOS_DIR/.build)"
    else
      ./gradlew clean
      echo "🧹 클린 완료"
    fi
    ;;
  a11y)
    if [ "$PLATFORM" = "macos" ]; then
      # 메뉴바 앱은 창이 없어 a11ity hierarchy 를 떠낼 대상이 없다.
      # 대신 "설치본이 실제로 뜨는가" 를 확인하는 것이 이 플랫폼에서 같은 역할을 한다.
      if pgrep -x "$APP_NAME" >/dev/null; then
        echo "✅ 실행 중 (pid $(pgrep -x "$APP_NAME" | head -1))"
      else
        echo "❌ 실행 중이 아님 — './build_and_run.sh run macos'"
        exit 1
      fi
      exit 0
    fi
    if ! resolve_device; then exit 1; fi
    mkdir -p "$ROOT/docs/screenshots/android"
    echo "📱 a11y 덤프 수집 중… ($SERIAL)"
    adb -s "$SERIAL" shell uiautomator dump /sdcard/window_dump.xml 2>/dev/null
    adb -s "$SERIAL" pull /sdcard/window_dump.xml "$ROOT/docs/screenshots/android/" 2>/dev/null
    echo "✅ a11y 덤프 완료"
    ;;
  *)
    echo "usage: ./build_and_run.sh [debug|run|test|diagnose|uninstall|clean|a11y|devices] [android|macos]"
    echo
    echo "  android (기본)"
    echo "    debug      빌드·설치        run     재시작         log     logcat"
    echo "    test       단위 테스트      lint    ktlint         clean   클린"
    echo "    a11y       덤프 수집        devices 연결 목록"
    echo
    echo "  macos"
    echo "    debug      swift build + .app 번들 → ~/Applications 설치"
    echo "    run        설치본 실행 (프로세스 생존 확인까지)"
    echo "    diagnose   서버 탐색 · SSE 생존 (메뉴바 앱의 유일한 진단 수단)"
    echo "    test       swift test       clean   .build 클린"
    echo "    uninstall  ~/Applications 에서 제거"
    echo
    echo "  DRD_SERIAL=<시리얼> 로 Android 대상 기기 지정 (여러 대 연결 시 필수)"
    exit 1
    ;;
esac
