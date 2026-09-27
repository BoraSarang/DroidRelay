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
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/apps/android"
PKG="com.borasarang.droidrelay"

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
resolve_device() {
  local d; d=$(pick_device)
  case "$d" in
    NO_DEVICE) echo "⚠️  연결된 디바이스 없음"; return 1 ;;
    INVALID_SERIAL) echo "❌ DRD_SERIAL='$DRD_SERIAL' 이(가) 연결 목록에 없음"; return 1 ;;
    MULTIPLE)
      echo "❌ 디바이스가 여러 대 연결됨 — $DRD_SERIAL 로 지정하세요:"
      adb devices | awk '$2=="device"{printf "     %s  %s %s\n",$1,$4,$5}'
      return 1 ;;
    *) SERIAL="$d"; return 0 ;;
  esac
}

# JAVA_HOME: Android Studio JBR 우선, 그 외 시스템 Java
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
elif [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi

cd "$ANDROID_DIR"

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
    # 설치만 하지 않고 앱을 재시작 — 대시보드가 새 HTML 을 서빙하도록
    if ! resolve_device; then exit 1; fi
    echo "🔄 $PKG 재시작 ($SERIAL)…"
    adb -s "$SERIAL" shell am force-stop "$PKG" || true
    sleep 1
    adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep "${2:-5}"
    echo "✅ 실행됨"
    ;;
  log)
    if ! resolve_device; then exit 1; fi
    adb -s "$SERIAL" logcat -d -t "${2:-200}" "$PKG:V" "*:S"
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
    if ! resolve_device; then exit 1; fi
    mkdir -p "$ROOT/docs/screenshots/android"
    echo "📱 a11y 덤프 수집 중… ($SERIAL)"
    adb -s "$SERIAL" shell uiautomator dump /sdcard/window_dump.xml 2>/dev/null
    adb -s "$SERIAL" pull /sdcard/window_dump.xml "$ROOT/docs/screenshots/android/" 2>/dev/null
    echo "✅ a11y 덤프 완료"
    ;;
  *)
    echo "usage: ./build_and_run.sh [debug|run|log|test|lint|clean|a11y|devices] [android|macos]"
    echo "  DRD_SERIAL=<시리얼> 로 대상 기기 지정 (여러 대 연결 시 필수)"
    exit 1
    ;;
esac
