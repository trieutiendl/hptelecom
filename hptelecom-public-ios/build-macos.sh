#!/usr/bin/env bash
# Build the public product on a macOS runner with its matching App Store profile.
set -euo pipefail
umask 077

if [[ "$(uname -s)" != Darwin ]]; then
  echo 'This command needs macOS with Xcode and CocoaPods.' >&2
  exit 1
fi

tools="$(cd "$(dirname "$0")" && pwd)"
source_dir="${PUBLIC_APP_SOURCE:-$tools/../app}"
artifacts="${PUBLIC_APP_ARTIFACTS:-$tools/../artifacts}"
: "${HP_PUBLIC_BUNDLE_ID:?Set the bundle ID of the separate public app.}"
: "${HP_PUBLIC_VERSION:?Set the marketing version of the public build.}"
: "${HP_PUBLIC_BUILD:?Set the build number of the public build.}"
: "${HP_IOS_SIGNING_JSON:?Provide the App Store certificate and profile through a protected runner secret.}"

export BUILD_WORK="$(mktemp -d "${TMPDIR:-/tmp}/hptelecom-public.XXXXXXXX")"
export BUILD_HELPERS="$BUILD_WORK/helpers"
cleanup() {
  if [[ -f "$BUILD_WORK/ios-signing.keychain-db" ]]; then
    security delete-keychain "$BUILD_WORK/ios-signing.keychain-db" >/dev/null 2>&1 || true
  fi
  if [[ -f "$BUILD_WORK/keychain-list.json" ]]; then
    python3 - "$BUILD_WORK/keychain-list.json" <<'PY'
import json, subprocess, sys
subprocess.run(["security", "list-keychains", "-d", "user", "-s",
                *json.load(open(sys.argv[1]))], check=True)
PY
  fi
  if [[ -f "$BUILD_WORK/installed-profile.txt" ]]; then
    installed="$(cat "$BUILD_WORK/installed-profile.txt")"
    case "$installed" in
      "$HOME/Library/MobileDevice/Provisioning Profiles/"*.mobileprovision)
        if [[ -f "$BUILD_WORK/previous-profile" ]]; then
          cp "$BUILD_WORK/previous-profile" "$installed"
        else
          rm -f -- "$installed"
        fi
        ;;
    esac
  fi
  rm -rf -- "$BUILD_WORK"
}
trap cleanup EXIT
mkdir -p "$BUILD_HELPERS" "$BUILD_WORK/app" "$artifacts"

# Copy source to an isolated tree so native project generation cannot affect other apps.
rsync -a --exclude=node_modules --exclude=.git --exclude=.expo --exclude='/ios/' --exclude='/android/' \
  "$source_dir/" "$BUILD_WORK/app/"
cd "$BUILD_WORK/app"
python3 - "$BUILD_HELPERS/release.json" <<'PY'
import json, os, pathlib, sys
cfg = json.loads(pathlib.Path("app.json").read_text(encoding="utf-8-sig"))["expo"]
bundle, version, build = (os.environ["HP_PUBLIC_" + key] for key in ("BUNDLE_ID", "VERSION", "BUILD"))
if (bundle, version, build) != ("vn.hptelecom.fieldwork", "1.0.69", "1") or (cfg["name"], cfg["ios"]["bundleIdentifier"], cfg["version"], str(cfg["ios"]["buildNumber"])) != ("hptelecom", bundle, version, build):
    sys.exit("The app configuration is not the intended separate public target.")
pathlib.Path(sys.argv[1]).write_text(json.dumps({"teamId": "DX247Z5GQ8", "bundleIdentifier": bundle, "version": version, "build": build}))
PY
export CI=1 EXPO_NO_TELEMETRY=1
signing_json="$HP_IOS_SIGNING_JSON"
unset HP_IOS_SIGNING_JSON
npm ci --install-links --no-fund --no-audit
npm run check
npm test
npx --no-install expo prebuild --platform ios --clean --no-install
(cd ios && pod install)
if ! grep -q 'PersonalCredentials (' ios/Podfile.lock; then
  echo 'PersonalCredentials is missing from the iOS app dependencies.' >&2
  exit 1
fi

python3 - "$BUILD_WORK/keychain-list.json" <<'PY'
import json, pathlib, shlex, subprocess, sys
old = shlex.split(subprocess.check_output(["security", "list-keychains", "-d", "user"], text=True))
pathlib.Path(sys.argv[1]).write_text(json.dumps(old))
PY
HP_IOS_SIGNING_JSON="$signing_json" python3 "$tools/signing.py"
unset signing_json
ruby "$tools/configure-project.rb"
workspace="$(find "$BUILD_WORK/app/ios" -maxdepth 1 -name '*.xcworkspace' -print -quit)"
scheme="$(cat "$BUILD_WORK/scheme.txt")"
xcodebuild -workspace "$workspace" -scheme "$scheme" -configuration Release \
  -destination generic/platform=iOS -archivePath "$BUILD_WORK/hptelecom.xcarchive" \
  -derivedDataPath "$BUILD_WORK/DerivedData" -jobs 3 archive
xcodebuild -exportArchive -archivePath "$BUILD_WORK/hptelecom.xcarchive" \
  -exportPath "$BUILD_WORK/export" -exportOptionsPlist "$BUILD_WORK/ExportOptions.plist"
ipa="$(find "$BUILD_WORK/export" -maxdepth 1 -name '*.ipa' -print -quit)"
ditto -xk "$ipa" "$BUILD_WORK/verified"
apps=("$BUILD_WORK/verified"/Payload/*.app)
test "${#apps[@]}" = 1
codesign --verify --deep --strict "${apps[0]}"
python3 "$tools/verify-product.py" "$ipa" --app-config app.json \
  --bundle-id "$HP_PUBLIC_BUNDLE_ID" --version "$HP_PUBLIC_VERSION" --build "$HP_PUBLIC_BUILD" \
  >"$BUILD_WORK/verification.json"
cp "$ipa" "$artifacts/hptelecom-$HP_PUBLIC_VERSION-$HP_PUBLIC_BUILD.ipa"
cp "$BUILD_WORK/verification.json" "$artifacts/verification.json"
echo "Verified public iPhone archive saved in $artifacts."
