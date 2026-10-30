#!/bin/bash
set -eu
BUILD_WORK="$RUNNER_TEMP/hp-map-ios-build"
if [ -f "$BUILD_WORK/ios-signing.keychain-db" ]; then
  security delete-keychain "$BUILD_WORK/ios-signing.keychain-db" >/dev/null 2>&1 || true
fi
if [ -f "$BUILD_WORK/installed-profile.txt" ]; then
  PROFILE_PATH="$(cat "$BUILD_WORK/installed-profile.txt")"
  case "$PROFILE_PATH" in
    "$HOME/Library/MobileDevice/Provisioning Profiles/"*.mobileprovision) rm -f -- "$PROFILE_PATH" ;;
  esac
fi
if [ -f "$BUILD_WORK/simulator-choice.json" ]; then
  ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["udid"])' "$BUILD_WORK/simulator-choice.json")"
  xcrun simctl shutdown "$ID" >/dev/null 2>&1 || true
fi
case "$BUILD_WORK" in
  "$RUNNER_TEMP/hp-map-ios-build") rm -rf -- "$BUILD_WORK" ;;
esac
