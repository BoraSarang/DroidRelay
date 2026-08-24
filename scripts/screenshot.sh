#!/bin/bash
# usage: ./scripts/screenshot.sh android [name]
# AGENTS.md 7.6 — Android: adb exec-out screencap
set -e
CMD="${1:-android}"
NAME="${2:-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="0.1"

if [ "$CMD" != "android" ]; then echo "지원: android"; exit 1; fi

OUT="$ROOT/docs/screenshots/android/v${VER}_${NAME}.png"
mkdir -p "$(dirname "$OUT")"
adb exec-out screencap -p > "$OUT"
echo "📸 저장: $OUT ($(du -h "$OUT" | cut -f1))"
