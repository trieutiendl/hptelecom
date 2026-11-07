#!/bin/bash
set -euo pipefail
umask 077
BUILD_HELPERS="$GITHUB_WORKSPACE/hp-admin-ios-build"
BUILD_WORK="$RUNNER_TEMP/hp-admin-ios-build"
BUILD_OUTPUT="$RUNNER_TEMP/hp-admin-ios-artifacts"
export BUILD_HELPERS BUILD_WORK BUILD_OUTPUT
mkdir -p "$BUILD_WORK" "$BUILD_OUTPUT"
HP_ADMIN_CAPTURE_REQUESTED="${HP_ADMIN_RUN_CAPTURE:-0}"
export HP_ADMIN_CAPTURE_REQUESTED
if [ "$HP_ADMIN_CAPTURE_REQUESTED" != 0 ] && [ "$HP_ADMIN_CAPTURE_REQUESTED" != 1 ]; then exit 1; fi
if [ -z "${HPADMIN_IOS_SOURCE_100_1:-}" ] || [ -z "${HPADMIN_IOS_SIGNING_100_1:-}" ]; then
  echo 'Admin release credentials are missing. No compilation or signing started.'
  exit 1
fi
if [ "$HP_ADMIN_CAPTURE_REQUESTED" = 1 ] && [ -z "${HPADMIN_IOS_CAPTURE_100_1:-}" ]; then
  echo 'Requested authenticated capture requires the approved limited reviewer session.'
  exit 1
fi
# Keep private values out of browser, Swift, simulator and Xcode process environments.
HP_ADMIN_SOURCE_VALUE="$HPADMIN_IOS_SOURCE_100_1"
HP_ADMIN_SIGNING_VALUE="$HPADMIN_IOS_SIGNING_100_1"
HP_ADMIN_CAPTURE_VALUE=''
if [ "$HP_ADMIN_CAPTURE_REQUESTED" = 1 ]; then HP_ADMIN_CAPTURE_VALUE="$HPADMIN_IOS_CAPTURE_100_1"; fi
export -n HP_ADMIN_SOURCE_VALUE HP_ADMIN_SIGNING_VALUE HP_ADMIN_CAPTURE_VALUE
unset HPADMIN_IOS_SOURCE_100_1 HPADMIN_IOS_SIGNING_100_1 HPADMIN_IOS_CAPTURE_100_1 HP_IOS_BUILD_KEY HP_IOS_SIGNING_JSON HP_ADMIN_CAPTURE_SESSION
exec 3>&1 4>&2
finish() {
  local status=$?
  trap - EXIT
  set +e
  export HP_ADMIN_BUILD_EXIT="$status"
  for family in iPhone iPad; do
    if [ -d "$BUILD_WORK/$family.xcresult" ] && [ ! -f "$BUILD_WORK/screenshots/$family/manifest.json" ]; then
      mkdir -p "$BUILD_WORK/screenshots/$family"
      xcrun xcresulttool export attachments --path "$BUILD_WORK/$family.xcresult" --output-path "$BUILD_WORK/screenshots/$family"
    fi
    if [ -d "$BUILD_WORK/$family-smoke.xcresult" ] && [ ! -f "$BUILD_WORK/smoke-screenshots/$family/manifest.json" ]; then
      mkdir -p "$BUILD_WORK/smoke-screenshots/$family"
      xcrun xcresulttool export attachments --path "$BUILD_WORK/$family-smoke.xcresult" --output-path "$BUILD_WORK/smoke-screenshots/$family"
    fi
  done
  if [ "$status" -ne 0 ]; then python3 "$BUILD_HELPERS/collect-diagnostics.py"; fi
  python3 "$BUILD_HELPERS/package-result.py"
  local packaged=$?
  if [ "$packaged" -eq 0 ]; then
    HP_IOS_BUILD_KEY="$HP_ADMIN_SOURCE_VALUE" node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/hp-admin-ios-result.zip" "$BUILD_OUTPUT/hp-admin-ios-result.zip.enc"
    packaged=$?
  fi
  unset HP_ADMIN_SOURCE_VALUE HP_ADMIN_SIGNING_VALUE HP_ADMIN_CAPTURE_VALUE
  if [ "$status" -eq 0 ] && [ "$packaged" -eq 0 ]; then
    echo 'Admin iPhone/iPad tests and signed archive passed. Requested capture status is in the encrypted receipt.' >&3
  else
    echo 'Admin build stopped; available diagnostics encrypted, no plaintext uploaded.' >&3
    [ "$status" -ne 0 ] || status=1
  fi
  exit "$status"
}
trap finish EXIT
exec >"$BUILD_WORK/build.log" 2>&1
HP_IOS_BUILD_KEY="$HP_ADMIN_SOURCE_VALUE" node "$BUILD_HELPERS/crypt.cjs" decrypt "$BUILD_HELPERS/source.enc" "$BUILD_WORK/source.zip"
python3 "$BUILD_HELPERS/verify-source.py"
cd "$BUILD_WORK/app"
HP_ADMIN_CAPTURE_LABELS='{}'
if [ "$HP_ADMIN_CAPTURE_REQUESTED" = 1 ]; then
  HP_ADMIN_CAPTURE_SESSION="$HP_ADMIN_CAPTURE_VALUE" node "$BUILD_HELPERS/prepare-capture.cjs" >&3
  HP_ADMIN_CAPTURE_LABELS="$(cat AdminUITests/capture-config.json)"
fi
if ! xcodebuild -version; then
  DEVELOPER_DIR="$(python3 "$BUILD_HELPERS/select-xcode.py")"
  export DEVELOPER_DIR
fi
python3 - <<'PY'
import subprocess
xcode=subprocess.check_output(['xcodebuild','-version'],text=True).splitlines()[0]
sdk=subprocess.check_output(['xcrun','--sdk','iphoneos','--show-sdk-version'],text=True).strip()
assert int(xcode.split()[1].split('.')[0])>=26
assert int(sdk.split('.')[0])>=26
print(xcode, 'iOS SDK',sdk)
PY
export HP_PLAYWRIGHT_MODULE="$RUNNER_TEMP/hp-admin-qa-deps/node_modules/playwright"
export PLAYWRIGHT_BROWSERS_PATH="$RUNNER_TEMP/hp-admin-browser"
python3 scripts/check-privacy.py --json-output "$BUILD_WORK/privacy-source.json"
python3 -m unittest discover -s tests -p 'test_privacy_manifest.py' >"$BUILD_WORK/privacy-tests.log" 2>&1
python3 scripts/test-capture-pipeline.py >"$BUILD_WORK/capture-pipeline-tests.log" 2>&1
node tests/browser-export-bridge.cjs >>"$BUILD_WORK/javascript-tests.log" 2>&1
node tests/browser-upload-adapter.cjs >>"$BUILD_WORK/javascript-tests.log" 2>&1
cp tests/browser-bridge-result.json "$BUILD_WORK/browser-bridge-production.json"
cp tests/browser-upload-result.json "$BUILD_WORK/browser-upload-production.json"
xcrun swiftc Admin/Sources/AdminSecurity.swift tests/AdminSecurityQA.swift -o "$BUILD_WORK/admin-security-qa"
"$BUILD_WORK/admin-security-qa" >"$BUILD_WORK/native-tests.log" 2>&1
xcrun swiftc -D DEBUG Admin/Sources/AdminSecurity.swift Admin/Sources/DebugCaptureSession.swift tests/DebugCaptureQA.swift -o "$BUILD_WORK/admin-capture-qa"
"$BUILD_WORK/admin-capture-qa" >>"$BUILD_WORK/native-tests.log" 2>&1
xcrun simctl list devices available --json >"$BUILD_WORK/simulators.json"
python3 "$BUILD_HELPERS/select-simulators.py" >"$BUILD_WORK/selected-simulators.txt"
while read -r family simulator; do
  xcrun simctl boot "$simulator" || true
  xcrun simctl bootstatus "$simulator" -b
  xcrun simctl status_bar "$simulator" override --time '9:41' --dataNetwork wifi --wifiMode active --wifiBars 3 --batteryState charged --batteryLevel 100 || true
  # The real Keychain requires app identity entitlements even on Simulator.
  # An ad-hoc simulator signature uses no Apple private signing material.
  # Keep device-thinned resources and their signatures separate for each family.
  xcodebuild -project HPTelecomAdmin.xcodeproj -scheme HPTelecomAdmin -configuration Debug \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$BUILD_WORK/SimulatorDerivedData-$family" \
    -parallel-testing-enabled NO ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- build-for-testing
  python3 "$BUILD_HELPERS/verify-simulator.py" --family "$family"
  echo "Testing public/offline admin UI first on $family." >&3
  # Public-login assertions must run before capture persists the real session.
  # This also verifies normal cold launch before the DEBUG-only preflight path.
  xcodebuild -project HPTelecomAdmin.xcodeproj -scheme HPTelecomAdmin -configuration Debug \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$BUILD_WORK/SimulatorDerivedData-$family" \
    -resultBundlePath "$BUILD_WORK/$family-smoke.xcresult" -parallel-testing-enabled NO \
    -only-testing:AdminUITests/SmokeTests \
    ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- test-without-building
  mkdir -p "$BUILD_WORK/smoke-screenshots/$family"
  xcrun xcresulttool export attachments --path "$BUILD_WORK/$family-smoke.xcresult" --output-path "$BUILD_WORK/smoke-screenshots/$family"
  if [ "$HP_ADMIN_CAPTURE_REQUESTED" = 1 ]; then
  echo "Capturing ten real limited-reviewer screenshots on $family." >&3
  # xcodebuild forwards TEST_RUNNER_* variables to XCTest without the prefix.
  TEST_RUNNER_HP_ADMIN_CAPTURE_ENABLED=1 TEST_RUNNER_HP_ADMIN_CAPTURE_SESSION="$HP_ADMIN_CAPTURE_VALUE" TEST_RUNNER_HP_ADMIN_CAPTURE_CONFIG="$HP_ADMIN_CAPTURE_LABELS" \
  xcodebuild -project HPTelecomAdmin.xcodeproj -scheme HPTelecomAdmin -configuration Debug \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$BUILD_WORK/SimulatorDerivedData-$family" \
    -resultBundlePath "$BUILD_WORK/$family.xcresult" -parallel-testing-enabled NO \
    -only-testing:AdminUITests/AuthenticatedScreenshots \
    ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- test-without-building
  mkdir -p "$BUILD_WORK/screenshots/$family"
  xcrun xcresulttool export attachments --path "$BUILD_WORK/$family.xcresult" --output-path "$BUILD_WORK/screenshots/$family"
  python3 "$BUILD_HELPERS/verify-screenshots.py" --family "$family"
  fi
  printf 'passed\n' >"$BUILD_WORK/$family-tests-passed.txt"
  xcrun simctl shutdown "$simulator" || true
done <"$BUILD_WORK/selected-simulators.txt"
unset HP_ADMIN_CAPTURE_VALUE
echo 'iPhone/iPad tests and requested screenshot checks passed; preparing signed admin archive.' >&3
HP_IOS_SIGNING_JSON="$HP_ADMIN_SIGNING_VALUE" python3 "$BUILD_HELPERS/signing.py"
unset HP_ADMIN_SIGNING_VALUE
SIGNING_IDENTITY="$(cat "$BUILD_WORK/signing-identity.txt")"
PROFILE_UUID="$(cat "$BUILD_WORK/profile-uuid.txt")"
xcodebuild -project HPTelecomAdmin.xcodeproj -scheme HPTelecomAdmin -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$BUILD_WORK/HPTelecomAdmin.xcarchive" \
  -derivedDataPath "$BUILD_WORK/ArchiveDerivedData" -jobs 3 CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=DX247Z5GQ8 \
  CODE_SIGN_IDENTITY="$SIGNING_IDENTITY" PROVISIONING_PROFILE_SPECIFIER="$PROFILE_UUID" \
  OTHER_CODE_SIGN_FLAGS="--keychain $BUILD_WORK/ios-signing.keychain-db" archive
xcodebuild -exportArchive -archivePath "$BUILD_WORK/HPTelecomAdmin.xcarchive" \
  -exportPath "$BUILD_WORK/export" -exportOptionsPlist "$BUILD_WORK/ExportOptions.plist"
python3 "$BUILD_HELPERS/verify-ipa.py"
