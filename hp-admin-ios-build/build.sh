#!/bin/bash
set -euo pipefail
umask 077
BUILD_HELPERS="$GITHUB_WORKSPACE/hp-admin-ios-build"
BUILD_WORK="$RUNNER_TEMP/hp-admin-ios-build"
BUILD_OUTPUT="$RUNNER_TEMP/hp-admin-ios-artifacts"
export BUILD_HELPERS BUILD_WORK BUILD_OUTPUT
mkdir -p "$BUILD_WORK" "$BUILD_OUTPUT"
if [ -z "${HPADMIN_IOS_SOURCE_100_1:-}" ] || [ -z "${HPADMIN_IOS_SIGNING_100_1:-}" ]; then
  echo 'Admin release credentials are missing. No compilation or signing started.'
  exit 1
fi
# Keep private values out of browser, Swift, simulator and Xcode process environments.
HP_ADMIN_SOURCE_VALUE="$HPADMIN_IOS_SOURCE_100_1"
HP_ADMIN_SIGNING_VALUE="$HPADMIN_IOS_SIGNING_100_1"
export -n HP_ADMIN_SOURCE_VALUE HP_ADMIN_SIGNING_VALUE
unset HPADMIN_IOS_SOURCE_100_1 HPADMIN_IOS_SIGNING_100_1 HP_IOS_BUILD_KEY HP_IOS_SIGNING_JSON
exec 3>&1 4>&2
finish() {
  local status=$?
  trap - EXIT
  set +e
  export HP_ADMIN_BUILD_EXIT="$status"
  for family in iPhone iPad; do
    if [ -d "$BUILD_WORK/$family.xcresult" ]; then
      mkdir -p "$BUILD_WORK/screenshots/$family"
      xcrun xcresulttool export attachments --path "$BUILD_WORK/$family.xcresult" --output-path "$BUILD_WORK/screenshots/$family"
    fi
  done
  python3 "$BUILD_HELPERS/package-result.py"
  local packaged=$?
  if [ "$packaged" -eq 0 ]; then
    HP_IOS_BUILD_KEY="$HP_ADMIN_SOURCE_VALUE" node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/hp-admin-ios-result.zip" "$BUILD_OUTPUT/hp-admin-ios-result.zip.enc"
    packaged=$?
  fi
  unset HP_ADMIN_SOURCE_VALUE HP_ADMIN_SIGNING_VALUE
  if [ "$status" -eq 0 ] && [ "$packaged" -eq 0 ]; then
    echo 'Admin iPhone/iPad offline UI tests and signed archive passed. Result and diagnostics encrypted.' >&3
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
node tests/browser-export-bridge.cjs >"$BUILD_WORK/javascript-tests.log" 2>&1
node tests/browser-upload-adapter.cjs >>"$BUILD_WORK/javascript-tests.log" 2>&1
cp tests/browser-bridge-result.json tests/browser-upload-result.json "$BUILD_WORK/"
xcrun swiftc Admin/Sources/AdminSecurity.swift tests/AdminSecurityQA.swift -o "$BUILD_WORK/admin-security-qa"
"$BUILD_WORK/admin-security-qa" >"$BUILD_WORK/native-tests.log" 2>&1
xcrun simctl list devices available --json >"$BUILD_WORK/simulators.json"
python3 "$BUILD_HELPERS/select-simulators.py" >"$BUILD_WORK/selected-simulators.txt"
while read -r family simulator; do
  xcrun simctl boot "$simulator" || true
  xcrun simctl bootstatus "$simulator" -b
  xcrun simctl status_bar "$simulator" override --time '9:41' --dataNetwork wifi --wifiMode active --wifiBars 3 --batteryState charged --batteryLevel 100 || true
  echo "Testing offline native admin UI on $family." >&3
  xcodebuild -project HPTelecomAdmin.xcodeproj -scheme HPTelecomAdmin -configuration Debug \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$BUILD_WORK/SimulatorDerivedData" \
    -resultBundlePath "$BUILD_WORK/$family.xcresult" -parallel-testing-enabled NO \
    ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO test
  printf 'passed\n' >"$BUILD_WORK/$family-tests-passed.txt"
  xcrun simctl shutdown "$simulator" || true
done <"$BUILD_WORK/selected-simulators.txt"
echo 'Offline iPhone/iPad tests passed; preparing signed admin archive.' >&3
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
