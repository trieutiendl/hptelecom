#!/usr/bin/env bash
set -euo pipefail
umask 077
udid="${1:?Supply a booted iPhone simulator UDID}"
work="${2:?Supply the simulator run temporary directory}"
source_dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$work"
stage="$(mktemp -d "$work/password-control.XXXXXXXX")"
bundle="$stage/PasswordControl.app"
mkdir "$bundle"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
arch="$(uname -m)"
case "$arch" in arm64|x86_64) ;; *) echo 'Unexpected simulator architecture.' >&2; exit 1 ;; esac
xcrun --sdk iphonesimulator swiftc -parse-as-library -module-name PasswordControl \
  -sdk "$sdk" -target "$arch-apple-ios16.0-simulator" -framework UIKit \
  "$source_dir/PasswordControl.swift" -o "$bundle/PasswordControl"
cat > "$bundle/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>PasswordControl</string>
  <key>CFBundleIdentifier</key><string>vn.hptelecom.simulator.password-control</string>
  <key>CFBundleName</key><string>PasswordControl</string>
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
        <key>UISceneDelegateClassName</key><string>PasswordControl.ControlScene</string>
      </dict></array>
    </dict>
  </dict>
</dict></plist>
PLIST
/usr/bin/plutil -lint "$bundle/Info.plist"
/usr/bin/codesign --force --sign - --timestamp=none "$bundle"
xcrun simctl install "$udid" "$bundle"
