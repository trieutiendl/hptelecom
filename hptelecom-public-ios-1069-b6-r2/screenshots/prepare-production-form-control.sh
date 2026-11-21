#!/usr/bin/env bash
set -euo pipefail
umask 077
udid="${1:?Supply a booted iPhone simulator UDID}"
work="${2:?Supply the simulator run temporary directory}"
controller="${PUBLIC_APP_SOURCE:?Supply the candidate app source directory}/modules/personal-credentials/ios/AccountEntryController.swift"
helpers="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$work"
stage="$(mktemp -d "$work/production-form-control.XXXXXXXX")"
bundle="$stage/ProductionFormControl.app"
mkdir "$bundle"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
arch="$(uname -m)"
case "$arch" in arm64|x86_64) ;; *) echo 'Unexpected simulator architecture.' >&2; exit 1 ;; esac
xcrun --sdk iphonesimulator swiftc -parse-as-library -module-name ProductionFormControl \
  -sdk "$sdk" -target "$arch-apple-ios16.0-simulator" -framework UIKit \
  "$controller" "$helpers/ProductionFormControl.swift" -o "$bundle/ProductionFormControl"
cat > "$bundle/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>ProductionFormControl</string>
  <key>CFBundleIdentifier</key><string>vn.hptelecom.simulator.production-form</string>
  <key>CFBundleName</key><string>ProductionFormControl</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>MinimumOSVersion</key><string>16.0</string>
  <key>UILaunchScreen</key><dict/>
  <key>UIApplicationSceneManifest</key><dict>
    <key>UIApplicationSupportsMultipleScenes</key><false/>
    <key>UISceneConfigurations</key><dict>
      <key>UIWindowSceneSessionRoleApplication</key><array><dict>
        <key>UISceneConfigurationName</key><string>Control</string>
        <key>UISceneDelegateClassName</key><string>ProductionFormControl.ProductionFormScene</string>
      </dict></array>
    </dict>
  </dict>
</dict></plist>
PLIST
/usr/bin/plutil -lint "$bundle/Info.plist"
/usr/bin/codesign --force --sign - --timestamp=none "$bundle"
xcrun simctl install "$udid" "$bundle"
