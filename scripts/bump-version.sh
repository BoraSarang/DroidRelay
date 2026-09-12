#!/bin/bash
# usage: ./scripts/bump-version.sh <versionName> [versionCode]
# 버전 단일 진실(apps/android/gradle.properties)을 원자적으로 갱신한다.
# versionCode 생략 시 +1. 커밋·태그·릴리즈는 수동 (파괴적 가드: 서명 키에 영향 없음).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/apps/android/gradle.properties"

if [ -z "$1" ]; then
    echo "usage: ./scripts/bump-version.sh <versionName> [versionCode]"
    exit 1
fi
NEW_NAME="$1"

if [ ! -f "$PROPS" ]; then
    echo "❌ $PROPS 없음"; exit 1
fi
CUR_CODE=$(grep -E "^versionCode=" "$PROPS" | cut -d= -f2)
CUR_NAME=$(grep -E "^versionName=" "$PROPS" | cut -d= -f2)
if [ -z "$CUR_CODE" ] || [ -z "$CUR_NAME" ]; then
    echo "❌ gradle.properties에 versionCode/versionName 없음 (수동 수정 금지 — 파일 확인 필요)"
    exit 1
fi
NEW_CODE="${2:-$((CUR_CODE + 1))}"

# macOS/BSD sed 호환 (백업 없이 직접 교체)
sed -i '' "s/^versionCode=.*/versionCode=$NEW_CODE/" "$PROPS"
sed -i '' "s/^versionName=.*/versionName=$NEW_NAME/" "$PROPS"

echo "✅ $CUR_NAME($CUR_CODE) → $NEW_NAME($NEW_CODE)"
echo "다음: docs/CHANGELOG.md 헤더 추가 → ./build_and_run.sh test android → 커밋·태그(v$NEW_NAME)·gh release (수동)"
