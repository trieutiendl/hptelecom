#!/bin/bash
set -euo pipefail
umask 077
BUILD_HELPERS="$GITHUB_WORKSPACE/ios-build"
BUILD_WORK="$RUNNER_TEMP/hp-ios-build"
BUILD_OUTPUT="$RUNNER_TEMP/hp-ios-artifacts"
export BUILD_HELPERS BUILD_WORK BUILD_OUTPUT
mkdir -p "$BUILD_WORK" "$BUILD_OUTPUT"

if [ -z "${HP_IOS_BUILD_KEY:-}" ] || [ -z "${HP_IOS_SIGNING_JSON:-}" ]; then
  echo 'Build secrets have not been configured. No signing or compilation started.'
  exit 1
fi

finish() {
  local result=$?
  trap - EXIT
  if [ -f "$BUILD_WORK/build.log" ]; then
    node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/build.log" "$BUILD_OUTPUT/build.log.enc" || true
  fi
  if [ "$result" -ne 0 ]; then
    echo 'Build stopped. Diagnostics are encrypted in the workflow artifact.'
  fi
  exit "$result"
}
trap finish EXIT

node "$BUILD_HELPERS/crypt.cjs" decrypt "$BUILD_HELPERS/source.enc" "$BUILD_WORK/source.zip"
node - <<'NODE'
const fs=require('fs'), crypto=require('crypto'), path=require('path');
const r=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_HELPERS,'release.json')));
const source=fs.readFileSync(path.join(process.env.BUILD_WORK,'source.zip'));
if(crypto.createHash('sha256').update(source).digest('hex')!==r.sourceSha256)throw Error('Source integrity failed');
NODE
mkdir -p "$BUILD_WORK/app"
unzip -q "$BUILD_WORK/source.zip" -d "$BUILD_WORK/app"
cd "$BUILD_WORK/app"
echo 'Verified release source. Installing dependencies.'
npm ci --no-audit --no-fund >> "$BUILD_WORK/build.log" 2>&1
npm run check >> "$BUILD_WORK/build.log" 2>&1
npm run check:store >> "$BUILD_WORK/build.log" 2>&1
node --test tests/cable-lifecycle.test.cjs tests/cable-photo-details.test.cjs tests/photo-date-update.cjs tests/edit-data.test.cjs tests/route-camera-information.test.cjs tests/camera-parity.test.ts tests/photo-location.test.cjs tests/jsx-text.test.cjs tests/camera-archive.test.cjs tests/camera-video-archive.test.cjs >> "$BUILD_WORK/build.log" 2>&1
node - <<'NODE'
const fs=require('fs'), path=require('path');
const release=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_HELPERS,'release.json')));
const config=JSON.parse(fs.readFileSync('app.json'));
if(config.expo.version!==release.version || config.expo.ios.bundleIdentifier!==release.bundleIdentifier)throw Error('Release identity mismatch');
config.expo.ios.buildNumber=release.build;
fs.writeFileSync('app.json',JSON.stringify(config,null,2));
NODE
echo 'Generating native iOS project.'
npx --no-install expo prebuild --platform ios --clean --no-install >> "$BUILD_WORK/build.log" 2>&1
(cd ios && pod install) >> "$BUILD_WORK/build.log" 2>&1
echo 'Preparing signing credentials.'
python3 "$BUILD_HELPERS/signing.py" >> "$BUILD_WORK/build.log" 2>&1
ruby "$BUILD_HELPERS/configure-project.rb" >> "$BUILD_WORK/build.log" 2>&1
unset HP_IOS_SIGNING_JSON
BUILD_SCHEME="$(cat "$BUILD_WORK/scheme.txt")"
BUILD_WORKSPACE="$(find "$BUILD_WORK/app/ios" -maxdepth 1 -name '*.xcworkspace' -print -quit)"
test -n "$BUILD_WORKSPACE"
echo 'Compiling and archiving iOS. This can take several minutes.'
xcodebuild -workspace "$BUILD_WORKSPACE" -scheme "$BUILD_SCHEME" -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$BUILD_WORK/app.xcarchive" \
  -derivedDataPath "$BUILD_WORK/DerivedData" -jobs 3 archive >> "$BUILD_WORK/build.log" 2>&1
xcodebuild -exportArchive -archivePath "$BUILD_WORK/app.xcarchive" \
  -exportPath "$BUILD_WORK/export" -exportOptionsPlist "$BUILD_WORK/ExportOptions.plist" >> "$BUILD_WORK/build.log" 2>&1
python3 "$BUILD_HELPERS/verify-ipa.py" >> "$BUILD_WORK/build.log" 2>&1
BUILD_IPA="$(find "$BUILD_WORK/export" -maxdepth 1 -name '*.ipa' -print -quit)"
test -n "$BUILD_IPA"
node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_IPA" "$BUILD_OUTPUT/HP-Telecom-iOS.ipa.enc"
node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/verification.json" "$BUILD_OUTPUT/verification.json.enc"
echo 'Signed IPA verified and encrypted. Download the workflow artifact to continue with TestFlight.'
