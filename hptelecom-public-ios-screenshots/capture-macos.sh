#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ "$(uname -s)" == Darwin ]] || { echo 'iPhone capture needs macOS and Xcode.' >&2; exit 1; }
helpers="$(cd "$(dirname "$0")" && pwd)"
source_dir="${PUBLIC_APP_SOURCE:-$helpers/../app}"
destination="${PUBLIC_SCREENSHOT_OUTPUT:?Set PUBLIC_SCREENSHOT_OUTPUT to a new or empty image directory.}"
mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/hptelecom-public-simulator.XXXXXXXX")"
simulator=''
cleanup() {
  if [[ -n "$simulator" ]]; then
    xcrun simctl delete "$simulator" >/dev/null || true
  fi
  rm -rf -- "$work"
}
trap cleanup EXIT

mkdir "$work/app"
rsync -a --exclude=node_modules --exclude=.git --exclude=.expo --exclude='/ios/' --exclude='/android/' \
  "$source_dir/" "$work/app/"
cd "$work/app"
node - <<'JS'
const {expo} = require('./app.json');
if (expo.name !== 'hptelecom' || expo.ios.bundleIdentifier !== 'vn.hptelecom.fieldwork' ||
    expo.version !== '1.0.69' || String(expo.ios.buildNumber) !== '1' || expo.ios.supportsTablet !== false)
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
xcrun simctl boot "$simulator"
export CI=1 EXPO_NO_TELEMETRY=1
npm ci --install-links --no-fund --no-audit
npx --no-install expo prebuild --platform ios --clean --no-install
(cd ios && pod install)
grep -q 'PersonalCredentials (' ios/Podfile.lock
ruby "$helpers/configure-tests.rb" "$work/app/ios" "$helpers/PublicScreenshots.swift"
xcrun simctl bootstatus "$simulator" -b
xcrun simctl status_bar "$simulator" override --time '9:41' --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
workspace="$(find ios -maxdepth 1 -name '*.xcworkspace' -print -quit)"
[[ -n "$workspace" ]]
build_args=(-workspace "$workspace" -scheme PublicScreenshots -configuration Release
  -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$work/DerivedData"
  -parallel-testing-enabled NO -jobs 3 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=-)
xcodebuild "${build_args[@]}" build-for-testing
python3 "$helpers/verify-simulator.py" "$work/DerivedData/Build/Products/Release-iphonesimulator"
if [[ -n "${PUBLIC_SIMULATOR_CACHE:-}" ]]; then
  mkdir -p "$PUBLIC_SIMULATOR_CACHE"
  product="$(find "$work/DerivedData/Build/Products/Release-iphonesimulator" -maxdepth 2 -name main.jsbundle -print -quit)"
  test -n "$product"
  ditto -c -k --keepParent "$(dirname "$product")" "$PUBLIC_SIMULATOR_CACHE/simulator-app.zip"
fi
result=0
xcodebuild "${build_args[@]}" -resultBundlePath "$work/public.xcresult" \
  -only-testing:PublicScreenshots/PublicScreenshots/testStoreScreenshots test-without-building || result=$?
xcrun xcresulttool export attachments --path "$work/public.xcresult" --output-path "$work/attachments"
if [[ "$result" != 0 ]]; then
  python3 "$helpers/collect.py" "$work/attachments" "$destination" --partial
  exit "$result"
fi
python3 "$helpers/collect.py" "$work/attachments" "$destination"
