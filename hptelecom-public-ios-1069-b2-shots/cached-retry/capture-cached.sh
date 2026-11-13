#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ "$(uname -s)" == Darwin ]] || { echo 'Cached iPhone capture needs macOS and Xcode.' >&2; exit 1; }
export DEVELOPER_DIR="${DEVELOPER_DIR:-$(xcode-select -p)}"
helpers="$(cd "$(dirname "$0")" && pwd)"
parent="$(cd "$helpers/.." && pwd)"
source_dir="${PUBLIC_APP_SOURCE:?Set PUBLIC_APP_SOURCE to the verified frozen source directory.}"
cache="${PUBLIC_SIMULATOR_APP_ZIP:?Set PUBLIC_SIMULATOR_APP_ZIP to the decrypted cache ZIP.}"
run_json="${PUBLIC_SIMULATOR_RUN_JSON:?Set PUBLIC_SIMULATOR_RUN_JSON to the authenticated GitHub run response.}"
artifact_json="${PUBLIC_SIMULATOR_ARTIFACT_JSON:?Set PUBLIC_SIMULATOR_ARTIFACT_JSON to the authenticated GitHub artifact response.}"
artifact_zip="${PUBLIC_SIMULATOR_ARTIFACT_ZIP:?Set PUBLIC_SIMULATOR_ARTIFACT_ZIP to the pinned downloaded artifact ZIP.}"
pin="${PUBLIC_CACHE_PIN:?Set PUBLIC_CACHE_PIN to a reviewed build2 artifact pin.}"
release="${PUBLIC_RELEASE_JSON:?Set PUBLIC_RELEASE_JSON to the frozen release manifest.}"
source_zip="${PUBLIC_SOURCE_ZIP:-$(dirname "$source_dir")/source.zip}"
destination="${PUBLIC_SCREENSHOT_OUTPUT:?Set PUBLIC_SCREENSHOT_OUTPUT to a new or empty image directory.}"
test_source="${PUBLIC_UI_TEST_SOURCE:-$parent/PublicScreenshots.swift}"
mkdir -p "$destination"
[[ -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ]] || { echo 'Screenshot output must be empty.' >&2; exit 1; }
destination="$(cd "$destination" && pwd)"
receipt="${PUBLIC_CACHE_REUSE_RECEIPT:-$(dirname "$destination")/cache-reuse-receipt.json}"
work="$(mktemp -d "${TMPDIR:-/tmp}/hptelecom-cached-capture.XXXXXXXX")"
simulator=''
cleanup() {
  if [[ -n "$simulator" ]]; then xcrun simctl delete "$simulator" >/dev/null || true; fi
  rm -rf -- "$work"
}
trap cleanup EXIT

app="$(python3 "$helpers/verify-cache.py" --pin "$pin" --run "$run_json" --artifact "$artifact_json" --artifact-archive "$artifact_zip" \
  --release "$release" --source-zip "$source_zip" --source-directory "$source_dir" \
  --cache-zip "$cache" --destination "$work/products" --receipt "$receipt")"
[[ -d "$app" ]]
deployment="$(/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' "$app/Info.plist")"
# Only the host and XCTest harness are compiled. The verified application is
# never a target, input build product, re-signing target, or editable source.
ruby -c "$helpers/make-harness.rb"
ruby "$helpers/make-harness.rb" "$work/harness" "$test_source" "$deployment"
xcrun simctl list runtimes --json > "$work/runtimes.json"
simulator="$(python3 - "$work/runtimes.json" <<'PY'
import json, re, subprocess, sys
runtimes = [r for r in json.load(open(sys.argv[1]))['runtimes']
            if r.get('isAvailable') and '.iOS-' in r['identifier']]
runtimes.sort(key=lambda r: tuple(map(int, r['version'].split('.'))), reverse=True)
for runtime in runtimes:
    for model in ('iPhone 17 Pro Max', 'iPhone 16 Pro Max'):
        created = subprocess.run(['xcrun', 'simctl', 'create', model,
            'com.apple.CoreSimulator.SimDeviceType.' + model.replace(' ', '-'), runtime['identifier']],
            capture_output=True, text=True)
        if created.returncode == 0 and re.fullmatch(r'[0-9A-Fa-f-]{36}', created.stdout.strip()):
            print(created.stdout.strip()); sys.exit(0)
sys.exit('No compatible iPhone Pro Max simulator runtime.')
PY
)"
# The headless runner has no physical keyboard. Use the iPhone software keyboard
# so secure-field focus behaves like the device we are testing.
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool NO
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b
# Recent Xcode also keeps a per-device keyboard preference. Verify both the
# global and this newly created device setting before launching the test host.
defaults export com.apple.iphonesimulator "$work/simulator-preferences.plist"
python3 - "$work/simulator-preferences.plist" "$simulator" <<'PY'
import plistlib, sys
p, udid = sys.argv[1:]
with open(p, 'rb') as f: prefs = plistlib.load(f)
prefs['ConnectHardwareKeyboard'] = False
prefs.setdefault('DevicePreferences', {}).setdefault(udid, {})['ConnectHardwareKeyboard'] = False
with open(p, 'wb') as f: plistlib.dump(prefs, f)
PY
defaults import com.apple.iphonesimulator "$work/simulator-preferences.plist"
defaults export com.apple.iphonesimulator "$work/verified-preferences.plist"
python3 - "$work/verified-preferences.plist" "$simulator" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'rb') as f: prefs = plistlib.load(f)
assert prefs['ConnectHardwareKeyboard'] is False
assert prefs['DevicePreferences'][sys.argv[2]]['ConnectHardwareKeyboard'] is False
print('[capture] Global and per-device software keyboard verified')
PY
simulator_app="$DEVELOPER_DIR/Applications/Simulator.app"
[[ -d "$simulator_app" ]] || { echo 'Selected Xcode Simulator.app is missing.' >&2; exit 1; }
open -a "$simulator_app" --args -CurrentDeviceUDID "$simulator"
xcrun simctl status_bar "$simulator" override --time '9:41' --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl install "$simulator" "$app"
args=(-project "$work/harness/CaptureHarness.xcodeproj" -scheme PublicScreenshots -configuration Release
  -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$work/DerivedData"
  -parallel-testing-enabled NO -jobs 3 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=-)
xcodebuild "${args[@]}" build-for-testing
# The app and harness must target the same device after compilation. Print only
# device state, never app data, when checking for a CoreSimulator interruption.
xcrun simctl bootstatus "$simulator" -b
xcrun simctl list devices available --json > "$work/pretest-devices.json"
python3 - "$work/pretest-devices.json" "$simulator" <<'PY'
import json, sys
devices = [d for group in json.load(open(sys.argv[1]))['devices'].values() for d in group]
matches = [d for d in devices if d['udid'] == sys.argv[2]]
assert len(matches) == 1 and matches[0].get('isAvailable') and matches[0]['state'] == 'Booted', 'Capture device is not available and booted'
print('[capture] Pre-test device is available and booted')
PY
xcrun simctl get_app_container "$simulator" vn.hptelecom.fieldwork app >/dev/null
xcodebuild -project "$work/harness/CaptureHarness.xcodeproj" -scheme PublicScreenshots \
  -showdestinations > "$work/pretest-destinations.txt" 2>&1
if ! grep -F "$simulator" "$work/pretest-destinations.txt" >/dev/null; then
  echo '[capture] Xcode cannot discover the booted capture device.' >&2
  cat "$work/pretest-destinations.txt"
  xcodebuild -version
  xcrun simctl list runtimes
  exit 1
fi
result=0
xcodebuild "${args[@]}" -destination-timeout 60 -resultBundlePath "$work/public.xcresult" \
  -only-testing:PublicScreenshots/PublicScreenshots/testStoreScreenshots test-without-building || result=$?
# Confirm the cached app stayed untouched throughout harness compilation/tests.
python3 "$parent/verify-simulator.py" "$work/products"
xcrun xcresulttool export attachments --path "$work/public.xcresult" --output-path "$work/attachments"
if [[ "$result" != 0 ]]; then
  python3 "$parent/collect.py" "$work/attachments" "$destination" --partial
  exit "$result"
fi
python3 "$parent/collect.py" "$work/attachments" "$destination"
