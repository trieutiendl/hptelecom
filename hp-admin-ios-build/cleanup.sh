#!/bin/bash
set -eu
BUILD_WORK="$RUNNER_TEMP/hp-admin-ios-build"
if [ -f "$BUILD_WORK/ios-signing.keychain-db" ]; then
  security delete-keychain "$BUILD_WORK/ios-signing.keychain-db" >/dev/null 2>&1 || true
fi
if [ -f "$BUILD_WORK/installed-profile.txt" ]; then
  PROFILE_PATH="$(cat "$BUILD_WORK/installed-profile.txt")"
  case "$PROFILE_PATH" in
    "$HOME/Library/MobileDevice/Provisioning Profiles/"*.mobileprovision) rm -f -- "$PROFILE_PATH" ;;
  esac
fi
if [ -f "$BUILD_WORK/selected-simulators.txt" ]; then
  while read -r family simulator; do
    xcrun simctl shutdown "$simulator" >/dev/null 2>&1 || true
  done <"$BUILD_WORK/selected-simulators.txt"
fi
case "$BUILD_WORK" in
  "$RUNNER_TEMP/hp-admin-ios-build") rm -rf -- "$BUILD_WORK" ;;
esac
