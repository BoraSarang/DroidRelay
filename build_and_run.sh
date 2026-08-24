#!/bin/bash
# usage: ./build_and_run.sh [debug|test|lint|clean|screenshot] [android]
# AGENTS.md 9장 빌드 디스패처 — DroidRelay (Android 전용)
set -e
CMD="${1:-debug}"
PLATFORM="${2:-android}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/apps/android"
PKG="com.borasarang.droidrelay"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [ "$PLATFORM" != "android" ]; then
  echo "지원 플랫폼: android (현재: $PLATFORM)"; exit 1
fi

case "$CMD" in
  debug)
    cd "$ANDROID_DIR"
    ./gradlew :app:assembleDebug --console=plain
    adb install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
    adb shell am force-stop "$PKG" 2>/dev/null || true
    sleep 1
    adb shell am start -n "$PKG/.MainActivity" | tail -1
    IP=$(adb shell ip -f inet addr show 2>/dev/null | grep -A2 swlan0 | awk '/inet /{print $2}' | cut -d/ -f1)
    echo "✅ 설치·실행 완료 — 접속 주소: http://${IP:-<폰IP>}:8080"
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
