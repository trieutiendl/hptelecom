#!/bin/bash
set -eu
test -n "${RUNNER_TEMP:-}"
BUILD_WORK="$RUNNER_TEMP/hp-ios-screenshot-build"
if [ -f "$BUILD_WORK/simulators.txt" ]; then
  while IFS= read -r SIMULATOR; do
    if [[ "$SIMULATOR" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
      xcrun simctl shutdown "$SIMULATOR" >/dev/null 2>&1 || true
      xcrun simctl delete "$SIMULATOR" >/dev/null 2>&1 || true
    fi
  done < "$BUILD_WORK/simulators.txt"
fi
case "$BUILD_WORK" in
  "$RUNNER_TEMP/hp-ios-screenshot-build") rm -rf -- "$BUILD_WORK" ;;
esac
