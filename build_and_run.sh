#!/bin/bash
# usage: ./build_and_run.sh [debug|test|lint|clean|screenshot] [android|macos]
# AGENTS.md 9장 빌드 디스패처 — DroidRelay
set -e
CMD="${1:-debug}"
PLATFORM="${2:-android}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/apps/android"
MACOS_DIR="$ROOT/apps/macos"
PKG="com.borasarang.droidrelay"
APP_NAME="DroidRelayClient"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

case "$PLATFORM" in
  android)
    case "$CMD" in
      debug)
        cd "$ANDROID_DIR"
        ./gradlew :app:assembleDebug --console=plain
        adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
        adb shell am force-stop "$PKG" 2>/dev/null || true
        sleep 1
        adb shell am start -n "$PKG/.MainActivity" | tail -1
        IP=$(adb shell ip -f inet addr show 2>/dev/null | grep -A2 swlan0 | awk '/inet /{print $2}' | cut -d/ -f1)
        echo "✅ Android 빌드·설치 완료 — 접속 주소: http://${IP:-<폰IP>}:8080"
        ;;
      test)
        cd "$ANDROID_DIR"; ./gradlew :app:testDebugUnitTest --console=plain ;;
      lint|ktlint)
        cd "$ANDROID_DIR"; ./gradlew :app:ktlintCheck --console=plain ;;
      clean)
        cd "$ANDROID_DIR"; ./gradlew clean --console=plain ;;
      screenshot)
        "$ROOT/scripts/screenshot.sh" android "${3:-main}" ;;
      *)
        echo "usage: ./build_and_run.sh [debug|test|lint|clean|screenshot] android"; exit 1 ;;
    esac
    ;;
  macos)
    case "$CMD" in
      debug)
        cd "$MACOS_DIR"
        xcodegen generate
        xcodebuild -project DroidRelayClient.xcodeproj \
                   -scheme DroidRelayClient \
                   -configuration Debug \
                   -derivedDataPath .build build 2>&1 | tail -3
        rm -rf ~/Applications/$APP_NAME.app 2>/dev/null || true
        cp -R .build/Build/Products/Debug/$APP_NAME.app ~/Applications/
        echo "✅ macOS 앱 빌드 완료: ~/Applications/$APP_NAME.app"
        ;;
      test)
        cd "$MACOS_DIR"
        xcodegen generate
        xcodebuild -project DroidRelayClient.xcodeproj \
                   -scheme DroidRelayClient \
                   -configuration Debug \
                   -derivedDataPath .build test 2>&1 | grep -E "tests passed|tests failed|error:" | tail -5
        ;;
      clean)
        cd "$MACOS_DIR"; rm -rf .build DroidRelayClient.xcodeproj ;;
      screenshot)
        "$ROOT/scripts/screenshot.sh" macos "${3:-popover}" ;;
      *)
        echo "usage: ./build_and_run.sh [debug|test|clean|screenshot] macos"; exit 1 ;;
    esac
    ;;
  *)
    echo "지원 플랫폼: android, macos (현재: $PLATFORM)"; exit 1 ;;
esac
