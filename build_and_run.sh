#!/bin/bash
# usage: ./build_and_run.sh [debug|test|lint|clean|a11y]
# AGENTS.md 9장 빌드 디스패처 — DroidRelay Android
set -e
CMD="${1:-debug}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/apps/android"
PKG="com.borasarang.droidrelay"

# JAVA_HOME: Android Studio JBR 우선, 그 외 시스템 Java
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
elif [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi

cd "$ANDROID_DIR"

case "$CMD" in
  debug)
    echo "🔨 assembleDebug 빌드 중…"
    ./gradlew assembleDebug -q 2>&1 | tail -3
    APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
    if [ -z "$APK" ]; then
        echo "❌ APK 생성 실패"; exit 1
    fi
    DEVICE=$(adb devices | grep -w device | awk '{print $1}' | head -1)
    if [ -z "$DEVICE" ]; then
        echo "⚠️  디바이스 연결 없음. APK: $APK"; exit 0
    fi
    echo "📲 $DEVICE에 설치 중…"
    adb install -r "$APK" 2>&1 | tail -1
    echo "✅ 빌드·설치 완료"
    ;;
  test)
    ./gradlew test 2>&1 | grep -E "BUILD|FAIL|tests" | tail -5
    ;;
  lint|ktlint)
    ./gradlew ktlintCheck 2>&1 | tail -10
    ;;
  clean)
    ./gradlew clean
    echo "🧹 클린 완료"
    ;;
  a11y)
    echo "📱 a11y 덤프 수집 중…"
    adb shell uiautomator dump /sdcard/window_dump.xml 2>/dev/null
    adb pull /sdcard/window_dump.xml "$ROOT/docs/screenshots/android/" 2>/dev/null
    echo "✅ a11y 덤프 완료"
    ;;
  *)
    echo "usage: ./build_and_run.sh [debug|test|lint|clean|a11y]"
    exit 1
    ;;
esac
