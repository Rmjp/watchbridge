# Build status — 1 November 2026

Original Windows verification reported by the prototype author (not rerun on this Mac):

- Android `assembleDebug`: passed, using API 36 / AGP 8.11.1 / Gradle 8.13 / JDK 21.
- Android `testDebugUnitTest`: one test passed, covering Node-reference P-256 key derivation, matching security code, directional HKDF, deterministic AES-GCM ciphertext, and rejection of modified associated data.
- Android `lintDebug`: passed with zero errors. Remaining warnings include newer dependency availability, target SDK compatibility, and style suggestions.
- Android debug APK signature: verified, APK v2, one debug signer.
- Relay tests: three passed, covering both approvals, authorization, encrypted routing, duplicate handling, recipient-scoped acknowledgements, revocation, pairing expiry, database restart persistence, payload expiry, and health retry lifetime.
- Apple property lists, entitlements, scheme XML, asset JSON and project source references: parsed successfully. This is static format validation only.

Mac verification, 1 November 2026:

- Environment: macOS 15.6.1, Xcode 16.2 (16C5032a), Swift 6.0.3, watchOS SDK 11.2.
- Swift crypto interoperability test: passed after correcting the public fixture's private-key encoding. Node had exported the scalar `2` as one byte; the generator now preserves the 32-byte width required by CryptoKit. The derived key, security code, associated data and ciphertext are unchanged.
- Corrected an installation failure caused by declaring both `WKWatchOnly` and `WKRunsIndependentlyOfCompanionApp`. Kept `WKWatchOnly`, matching [Apple's watch-only app definition](https://developer.apple.com/documentation/bundleresources/information-property-list/wkwatchonly), and removed the conflicting companion-app flag.
- Relay: all three integration tests passed using the locally installed Node.js 24.21.0.
- The initial watchOS 11.5 / Developer Mode reading came from an old cached Mac pairing record, not a live device connection. The watch was later confirmed to run watchOS 26.4; Developer Mode was enabled during physical installation below.
- Installed Apple's watchOS 11.2 simulator runtime (22S99); the SDK alone was insufficient for asset compilation.
- `sh scripts/check-mac.sh`: passed, including the Swift interoperability test and unsigned watchOS simulator build for arm64 and x86_64. Logs and build products are under `dist/mac-checks/` (ignored by Git).
- Unsigned physical-device build: passed with `-sdk watchos -destination 'generic/platform=watchOS' CODE_SIGNING_ALLOWED=NO`, covering arm64 and arm64_32. Build products are under `dist/mac-checks/DeviceDerivedData/`; this does not sign or install the app.
- Simulator installation and launch: passed on a dedicated Series 8 (45mm), watchOS 11.2. The relay URL, pairing code and Join phone controls were visible; screenshot: `dist/mac-checks/watch-simulator.png`. The simulator's initial boot took about nine minutes to finish its system migration.
- The unsigned app opened but reported a Keychain access error. Rebuilt with normal simulator signing enabled to embed simulated entitlements. The cloud-synced workspace attached Finder metadata that blocked the final signing step; copied only the generated bundle to `/private/tmp/WatchBridge-simulator.app` without extended attributes, completed ad hoc signing with Xcode's generated entitlements, and passed `codesign --verify --deep --strict` before reinstalling and launching it. For future signed builds, use Xcode's default DerivedData location outside the synced workspace.
- Nonblocking compiler warning: the app's one-parameter SwiftUI `onChange` closure is deprecated in watchOS 10.0.

Physical installation, 1 November 2026:

- Installed and verified Apple's Xcode 26.3 (17C529) at `/Applications/Xcode-26.3.app`, with watchOS SDK 26.2. Physical build commands used `DEVELOPER_DIR=/Applications/Xcode-26.3.app/Contents/Developer`.
- Refreshed the stale Mac developer pairing, prepared the USB-connected companion iPhone, and established the watch's Wi-Fi developer connection. Live watch details confirmed watchOS 26.4 and Developer Mode enabled; watch developer disk image services reported compatible and usable.
- Signed physical Series 8 build: passed, targeting `arm64_32`. Code signature verified; provisioning profile includes the watch and the HealthKit entitlement.
- `devicectl device install app`: succeeded on the physical Series 8. Installed bundle ID: `com.rmjp.watchbridge.watch`, version 0.1.0. Development signing expires 8 November 2026 at 23:54 Bangkok time.
- Remote launch verification timed out when the developer network tunnel dropped again. Manual first launch and app-level phone/watch communication remain unverified; successful installation alone does not establish those results.
- Removed the task-added watchOS 11.2 simulator runtime and temporary Xcode installer to recover disk space. Earlier simulator results above remain historical checks; watchOS 26.2 simulator support was installed with the newer Xcode.

Not verified on physical devices:

- First launch / pairing-screen behavior.
- Physical BLE connectivity on S25 Ultra / Series 8.
- Real notification forwarding/replies, HealthKit data access, or Health Connect writes.
- Suspended watch delivery, automatic background health sync, battery use or long-duration reliability.

Deliverable is a first prototype and device-test handoff. APNs/FCM and scheduled background health collection are not implemented in 0.1. Physical-device tests are required before claiming end-to-end operation.
