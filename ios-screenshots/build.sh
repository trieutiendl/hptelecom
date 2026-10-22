#!/bin/bash
set -euo pipefail
umask 077
test -n "${GITHUB_WORKSPACE:-}" && test -n "${RUNNER_TEMP:-}"
BUILD_HELPERS="$GITHUB_WORKSPACE/ios-screenshots"
BUILD_WORK="$RUNNER_TEMP/hp-ios-screenshot-build"
BUILD_OUTPUT="$RUNNER_TEMP/hp-ios-screenshot-artifacts"
export BUILD_HELPERS BUILD_WORK BUILD_OUTPUT
mkdir -p "$BUILD_WORK/results" "$BUILD_OUTPUT"
if [ -z "${HP_IOS_BUILD_KEY:-}" ]; then
  echo 'The temporary screenshot source key is missing; compilation has not started.'
  exit 1
fi

finish() {
  local result=$?
  local encrypted=0
  set +e
  trap - EXIT
  if [ "$result" -ne 0 ] && [ -n "${BUILD_APP:-}" ] && [ -d "$BUILD_APP" ]; then
    ditto -c -k --keepParent "$BUILD_APP" "$BUILD_WORK/results/simulator-app.zip" >> "$BUILD_WORK/build.log" 2>&1
  fi
  if [ -f "$BUILD_WORK/build.log" ]; then
    cp "$BUILD_WORK/build.log" "$BUILD_WORK/results/build.log"
  fi
  printf '%s\n' "$result" > "$BUILD_WORK/results/exit-code.txt"
  if (cd "$BUILD_WORK/results" && zip -q -r "$BUILD_WORK/screenshot-results.zip" .) && \
     node "$BUILD_HELPERS/crypt.cjs" encrypt "$BUILD_WORK/screenshot-results.zip" "$BUILD_OUTPUT/screenshot-results.zip.enc"; then
    encrypted=1
  else
    echo 'Encrypted result packaging failed; no plaintext artifact will be uploaded.'
  fi
  bash "$BUILD_HELPERS/cleanup.sh"
  if [ "$encrypted" -ne 1 ]; then result=1; fi
  if [ "$result" -ne 0 ]; then
    echo 'Screenshot build stopped. Any available diagnostics are inside the encrypted result.'
  fi
  exit "$result"
}
trap finish EXIT

node "$BUILD_HELPERS/crypt.cjs" decrypt "$BUILD_HELPERS/source.enc" "$BUILD_WORK/source.zip"
node - <<'NODE'
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const release=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_HELPERS,'release.json')));
const source=fs.readFileSync(path.join(process.env.BUILD_WORK,'source.zip'));
if (!/^[0-9a-f]{64}$/.test(release.sourceSha256) || crypto.createHash('sha256').update(source).digest('hex')!==release.sourceSha256) {
  throw Error('Screenshot source integrity failed');
}
NODE
mkdir -p "$BUILD_WORK/app"
unzip -q "$BUILD_WORK/source.zip" -d "$BUILD_WORK/app"
cd "$BUILD_WORK/app"
test -f capture.json
node - <<'NODE'
const fs=require('node:fs'), path=require('node:path');
const release=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_HELPERS,'release.json')));
const config=JSON.parse(fs.readFileSync('app.json')).expo;
if (config.version!==release.version || config.ios.bundleIdentifier!==release.bundleIdentifier || config.ios.buildNumber!==release.build) {
  throw Error('Screenshot app identity mismatch');
}
NODE
echo 'Verified private source. Installing dependencies for unsigned simulator build.'
npm ci --no-audit --no-fund >> "$BUILD_WORK/build.log" 2>&1
npm run check >> "$BUILD_WORK/build.log" 2>&1
echo 'Generating the native iPhone project.'
npx --no-install expo prebuild --platform ios --clean --no-install >> "$BUILD_WORK/build.log" 2>&1
(cd ios && pod install) >> "$BUILD_WORK/build.log" 2>&1
ruby -rxcodeproj -e '
  project_path = Dir.glob(File.join(ENV.fetch("BUILD_WORK"), "app/ios/*.xcodeproj")).first
  raise "Missing iOS project" unless project_path
  project = Xcodeproj::Project.open(project_path)
  targets = project.native_targets.select { |t| t.product_type == "com.apple.product-type.application" }
  raise "Expected one app target" unless targets.length == 1
  File.write(File.join(ENV.fetch("BUILD_WORK"), "scheme.txt"), targets.first.name)
' >> "$BUILD_WORK/build.log" 2>&1
python3 "$BUILD_HELPERS/select-simulator.py" >> "$BUILD_WORK/build.log" 2>&1
SIMULATOR="$(cat "$BUILD_WORK/simulator.txt")"
BUILD_SCHEME="$(cat "$BUILD_WORK/scheme.txt")"
BUILD_WORKSPACE="$(find "$BUILD_WORK/app/ios" -maxdepth 1 -name '*.xcworkspace' -print -quit)"
test -n "$BUILD_WORKSPACE"
echo 'Compiling the real iPhone interface without Apple signing credentials.'
xcodebuild -workspace "$BUILD_WORKSPACE" -scheme "$BUILD_SCHEME" -configuration Release \
  -sdk iphonesimulator -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath "$BUILD_WORK/DerivedData" -jobs 3 \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build >> "$BUILD_WORK/build.log" 2>&1
BUILD_APP="$(find "$BUILD_WORK/DerivedData/Build/Products/Release-iphonesimulator" -maxdepth 1 -name '*.app' -print -quit)"
test -n "$BUILD_APP"
export BUILD_APP
echo 'Launching isolated iPhone simulators and capturing the application screens.'
python3 -u "$BUILD_HELPERS/capture.py" 2>> "$BUILD_WORK/build.log"
echo 'Screenshots captured. Packaging the encrypted result and removing temporary files.'
