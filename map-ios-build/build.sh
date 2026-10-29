#!/bin/bash
set -euo pipefail
umask 077
BUILD_HELPERS="$GITHUB_WORKSPACE/map-ios-build"
BUILD_WORK="$RUNNER_TEMP/hp-map-ios-build"
BUILD_OUTPUT="$RUNNER_TEMP/hp-map-ios-artifacts"
export BUILD_HELPERS BUILD_WORK BUILD_OUTPUT
mkdir -p "$BUILD_WORK" "$BUILD_OUTPUT"
if [ -z "${HP_MAP_IOS_SOURCE_100_1:-}" ] || [ -z "${HP_MAP_IOS_SIGNING_100_1:-}" ]; then
  echo 'HP Map build credentials are missing. No compilation or signing started.'
  exit 1
fi
export HP_IOS_BUILD_KEY="$HP_MAP_IOS_SOURCE_100_1"
export HP_IOS_SIGNING_JSON="$HP_MAP_IOS_SIGNING_100_1"
unset HP_MAP_IOS_SOURCE_100_1 HP_MAP_IOS_SIGNING_100_1
exec 3>&1 4>&2
finish() {
  local status=$?
  trap - EXIT
  set +e
  if [ "$status" -ne 0 ] && [ -n "${SIMULATOR_ID:-}" ]; then
    mkdir -p "$BUILD_WORK/screenshots"
    xcrun simctl io "$SIMULATOR_ID" screenshot "$BUILD_WORK/screenshots/failure.png"
  fi
  if [ -d "$BUILD_WORK/Tests.xcresult" ]; then
    mkdir -p "$BUILD_WORK/screenshots/test-attachments"
    xcrun xcresulttool export attachments --path "$BUILD_WORK/Tests.xcresult" --output-path "$BUILD_WORK/screenshots/test-attachments"
  fi
  export HP_MAP_BUILD_EXIT="$status"
  python3 "$BUILD_HELPERS/package-result.py"
  local package_status=$?
  if [ "$package_status" -eq 0 ]; then
    node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/hp-map-ios-result.zip" "$BUILD_OUTPUT/hp-map-ios-result.zip.enc"
    package_status=$?
  fi
  if [ "$status" -eq 0 ] && [ "$package_status" -eq 0 ]; then
    echo 'HP Map simulator tests and signed archive passed. Result and diagnostics are encrypted.' >&3
  else
    echo 'HP Map build stopped. Available diagnostics are encrypted; no plaintext files were uploaded.' >&3
    [ "$status" -ne 0 ] || status=1
  fi
  exit "$status"
}
trap finish EXIT
exec >"$BUILD_WORK/build.log" 2>&1
echo 'Preparing isolated HP Map build.'
python3 "$BUILD_HELPERS/test-pipeline.py"
node "$BUILD_HELPERS/crypt.cjs" decrypt "$BUILD_HELPERS/source.enc" "$BUILD_WORK/source.zip"
python3 "$BUILD_HELPERS/verify-source.py"
cd "$BUILD_WORK/app"

# The runner's selected Xcode is preferred. A fallback only selects an installed
# Xcode.app; no installer, beta download, paid runner or third-party tool is used.
if ! xcodebuild -version; then
  DEVELOPER_DIR="$(python3 "$BUILD_HELPERS/select-xcode.py")"
  export DEVELOPER_DIR
  xcodebuild -version
fi
xcrun --sdk iphoneos --show-sdk-version
python3 - <<'PY'
import subprocess
xcode=subprocess.check_output(['xcodebuild','-version'],text=True).splitlines()[0]
sdk=subprocess.check_output(['xcrun','--sdk','iphoneos','--show-sdk-version'],text=True).strip()
assert int(xcode.split()[1].split('.')[0]) >= 26, 'Xcode 26 or later is required'
assert int(sdk.split('.')[0]) >= 26, 'iOS 26 SDK or later is required'
PY
node scripts/prepare-api.cjs --check
node --test tests/*.test.cjs >"$BUILD_WORK/javascript-tests.log" 2>&1
echo 'JavaScript tests passed.'
echo 'HP Map data tests passed; checking native iPhone components.' >&3

mkdir -p "$BUILD_WORK/native-tests"
cp "$BUILD_HELPERS/native-tests.swift" "$BUILD_WORK/native-tests/main.swift"
xcrun swiftc HPMap/Sources/MapSecurity.swift HPMap/Sources/MapKMZ.swift "$BUILD_WORK/native-tests/main.swift" -o "$BUILD_WORK/native-tests/run"
"$BUILD_WORK/native-tests/run" "$BUILD_WORK/native-tests/export.kmz" >"$BUILD_WORK/native-tests.log" 2>&1
python3 - <<'PY'
import os, pathlib, zipfile
p=pathlib.Path(os.environ['BUILD_WORK'])/'native-tests/export.kmz'
with zipfile.ZipFile(p) as z:
    assert z.namelist()==['doc.kml'] and z.testzip() is None
    assert 'HP Hà Phong'.encode() in z.read('doc.kml')
print('Native security checks and KMZ ZIP/CRC verified.')
PY

xcrun simctl list devices available --json >"$BUILD_WORK/simulators.json"
SIMULATOR_ID="$(python3 "$BUILD_HELPERS/select-simulator.py")"
export SIMULATOR_ID
xcrun simctl boot "$SIMULATOR_ID" || true
xcrun simctl bootstatus "$SIMULATOR_ID" -b
echo 'Building and testing the iPhone interface.' >&3
xcodebuild -project HPMap.xcodeproj -scheme HPMap -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath "$BUILD_WORK/SimulatorDerivedData" \
  -resultBundlePath "$BUILD_WORK/Tests.xcresult" \
  -parallel-testing-enabled NO ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO SWIFT_ACTIVE_COMPILATION_CONDITIONS='DEBUG HPMAP_UI_TESTS' test
printf 'passed\n' >"$BUILD_WORK/simulator-tests-passed.txt"
xcrun simctl status_bar "$SIMULATOR_ID" override --time '9:41' --dataNetwork wifi --wifiMode active --wifiBars 3 --batteryState charged --batteryLevel 100 || true
xcrun simctl launch "$SIMULATOR_ID" vn.hptelecom.map
sleep 3
mkdir -p "$BUILD_WORK/screenshots"
xcrun simctl io "$SIMULATOR_ID" screenshot "$BUILD_WORK/screenshots/HP-Map-iPhone-login.png"

python3 "$BUILD_HELPERS/signing.py"
unset HP_IOS_SIGNING_JSON
SIGNING_IDENTITY="$(cat "$BUILD_WORK/signing-identity.txt")"
PROFILE_UUID="$(cat "$BUILD_WORK/profile-uuid.txt")"
xcodebuild -project HPMap.xcodeproj -scheme HPMap -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$BUILD_WORK/HPMap.xcarchive" \
  -derivedDataPath "$BUILD_WORK/ArchiveDerivedData" -jobs 3 \
  CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=DX247Z5GQ8 \
  CODE_SIGN_IDENTITY="$SIGNING_IDENTITY" PROVISIONING_PROFILE_SPECIFIER="$PROFILE_UUID" \
  OTHER_CODE_SIGN_FLAGS="--keychain $BUILD_WORK/ios-signing.keychain-db" archive
xcodebuild -exportArchive -archivePath "$BUILD_WORK/HPMap.xcarchive" \
  -exportPath "$BUILD_WORK/export" -exportOptionsPlist "$BUILD_WORK/ExportOptions.plist"
python3 "$BUILD_HELPERS/verify-ipa.py"
