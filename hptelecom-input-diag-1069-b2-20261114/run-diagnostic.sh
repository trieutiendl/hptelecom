#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ "$(uname -s)" == Darwin ]] || { echo 'iPhone capture needs macOS and Xcode.' >&2; exit 1; }
export DEVELOPER_DIR="${DEVELOPER_DIR:-$(xcode-select -p)}"
helpers="$(cd "$(dirname "$0")" && pwd)"
source_dir="${DIAGNOSTIC_APP_SOURCE:-$helpers/../app}"
destination="${DIAGNOSTIC_OUTPUT:?Set DIAGNOSTIC_OUTPUT to a fresh diagnostic directory.}"
mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/hptelecom-input-diagnostic.XXXXXXXX")"
simulator=''
phase='SETUP_FAILED'
touch "$work/private-test.log"
cleanup() {
  local result=$?
  trap - EXIT
  set +e
  if [[ ! -e "$destination/validated" ]]; then
    local evidence='-'
    [[ ! -f "$work/product.json" ]] || evidence="$work/product.json"
    node "$helpers/collect-diagnostic.cjs" "$work/private-test.log" "$evidence" "$destination/validated" "$phase"
  fi
  if [[ -n "$simulator" ]]; then
    xcrun simctl delete "$simulator" >/dev/null || true
  fi
  rm -rf -- "$work"
  exit "$result"
}
trap cleanup EXIT

mkdir "$work/app"
rsync -a --exclude=node_modules --exclude=.git --exclude=.expo --exclude='/ios/' --exclude='/android/' \
  "$source_dir/" "$work/app/"
cd "$work/app"
node - <<'JS'
const {expo} = require('./app.json');
if (expo.name !== 'hptelecom' || expo.ios.bundleIdentifier !== 'vn.hptelecom.fieldwork' ||
    expo.version !== '1.0.69' || String(expo.ios.buildNumber) !== '2' || expo.ios.supportsTablet !== false)
  throw Error('Unexpected simulator app configuration.');
JS

# A new simulator has no saved accounts, reports, camera roll, or cached sessions.
xcrun simctl list runtimes --json > "$work/runtimes.json"
simulator="$(python3 - "$work/runtimes.json" <<'PY'
import json, re, subprocess, sys
runtimes = [r for r in json.load(open(sys.argv[1]))['runtimes']
            if r.get('isAvailable') and '.iOS-' in r['identifier']]
runtimes.sort(key=lambda r: tuple(map(int, r['version'].split('.'))), reverse=True)
for runtime in runtimes:
    for model in ('iPhone 17 Pro Max', 'iPhone 16 Pro Max'):
        device_type = 'com.apple.CoreSimulator.SimDeviceType.' + model.replace(' ', '-')
        created = subprocess.run(['xcrun', 'simctl', 'create', model, device_type, runtime['identifier']],
                                 capture_output=True, text=True)
        if created.returncode == 0 and re.fullmatch(r'[0-9A-Fa-f-]{36}', created.stdout.strip()):
            print(created.stdout.strip())
            sys.exit(0)
sys.exit('No compatible 1320 x 2868 iPhone Pro Max simulator runtime is installed.')
PY
)"
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool NO
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b
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
export CI=1 EXPO_NO_TELEMETRY=1
npm ci --install-links --no-fund --no-audit
npx --no-install expo prebuild --platform ios --clean --no-install
(cd ios && pod install)
grep -q 'PersonalCredentials (' ios/Podfile.lock
ruby "$helpers/configure-tests.rb" "$work/app/ios" "$helpers/InputDiagnostics.swift"
xcrun simctl bootstatus "$simulator" -b
xcrun simctl status_bar "$simulator" override --time '9:41' --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
workspace="$(find ios -maxdepth 1 -name '*.xcworkspace' -print -quit)"
[[ -n "$workspace" ]]
build_args=(-workspace "$workspace" -scheme InputDiagnostics -configuration Release
  -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$work/DerivedData"
  -parallel-testing-enabled NO -jobs 3 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=-)
phase='BUILD_FAILED'
xcodebuild "${build_args[@]}" build-for-testing
python3 "$helpers/product-evidence.py" "$work/DerivedData/Build/Products/Release-iphonesimulator" "$work/product.json"
phase='TEST_NO_TRACE'
xcrun simctl bootstatus "$simulator" -b
result=0
xcodebuild "${build_args[@]}" -destination-timeout 60 -resultBundlePath "$work/input.xcresult" \
  -only-testing:InputDiagnostics/InputDiagnostics/testSyntheticRegistrationInput test-without-building > "$work/private-test.log" 2>&1 || result=$?
node "$helpers/collect-diagnostic.cjs" "$work/private-test.log" "$work/product.json" "$destination/validated" "$phase"
exit "$result"
