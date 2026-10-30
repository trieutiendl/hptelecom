HP Map iOS 1.0.0 (3) - separate app vn.hptelecom.map

Local staging only. These files do not publish code or configure secrets.

1. Copy these helper files (except build-hp-map-ios.yml) to map-ios-build in the
   public trieutiendl/hptelecom repository. Copy the workflow to
   .github/workflows/build-hp-map-ios.yml. Do not change the reporting-app workflow.
2. Create a source ZIP with HPMap.xcodeproj, HPMap, HPMapUITests, scripts, shared,
   tests at its root. Exclude .git, private material, checks and CI artifacts.
3. Set release.json sourceSha256 to the exact plaintext ZIP SHA-256, encrypt the
   ZIP with crypt.cjs (HP_IOS_BUILD_KEY env, 64 hex characters) as source.enc.
4. Configure temporary Actions secrets HP_MAP_IOS_SOURCE_100_3 (same encryption
   key) and HP_MAP_IOS_SIGNING_100_3 (JSON: teamId, bundleIdentifier, p12 base64,
   password and profile base64). Use an App Store profile specifically for
   vn.hptelecom.map and team DX247Z5GQ8. The reporting-app profile is rejected.
5. After dispatch, download hp-map-ios-RUN_ID, decrypt hp-map-ios-result.zip.enc
   with the same key, inspect verification.json and simulator screenshots.
6. Remove both temporary Actions secrets after the run. Runner cleanup always
   removes its keychain, profile and plaintext working directory.

The public workflow exposes only generic status and encrypted result files.
No Apple upload or App Store release is performed by this workflow.
Tests include JS API regressions, isolated iOS bridge tests, native security/KMZ
checks, simulator UI tests, archive identity and final codesign verification.
Physical iPhone testing remains required after TestFlight upload.
