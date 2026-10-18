# HP Telecom iOS build

This manual workflow builds the verified HP Telecom 4.1.18 source on a standard
GitHub macOS runner. It does not start an EAS cloud build or subscribe to a plan.
It runs only for the repository owner on main while this repository is public.

The app source is AES-256-GCM encrypted. The key is an Actions secret named
`HP_IOS_SOURCE_KEY_418_47`. Signing uses the separate `HP_IOS_SIGNING_418_47`
Actions secret, authorized by the owner for this build. Never add either secret
value to the repository. Only the existing distribution certificate and
provisioning profile for this app are used.

The workflow verifies the source archive, compiles with Xcode 26.6, signs build 47,
checks the exported IPA and uploads only encrypted output and diagnostics with
one-day retention. It does not publish to the App Store. The signed IPA must be
decrypted locally and uploaded to App Store Connect for TestFlight after verification.

The workflow has no pull-request, push, or scheduled trigger. Signing material is
removed from the ephemeral runner at the end. Remove the signing and source key
Secrets after the authorized build has completed and artifacts are saved locally.
