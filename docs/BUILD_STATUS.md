# Build status — 1 November 2026

Verified in this Windows workspace:

- Android `assembleDebug`: passed, using API 36 / AGP 8.11.1 / Gradle 8.13 / JDK 21.
- Android `testDebugUnitTest`: one test passed, covering Node-reference P-256 key derivation, matching security code, directional HKDF, deterministic AES-GCM ciphertext, and rejection of modified associated data.
- Android `lintDebug`: passed with zero errors. Remaining warnings include newer dependency availability, target SDK compatibility, and style suggestions.
- Android debug APK signature: verified, APK v2, one debug signer.
- Relay tests: three passed, covering both approvals, authorization, encrypted routing, duplicate handling, recipient-scoped acknowledgements, revocation, pairing expiry, database restart persistence, payload expiry, and health retry lifetime.
- Apple property lists, entitlements, scheme XML, asset JSON and project source references: parsed successfully. This is static format validation only.

Not verified here:

- Swift/CryptoKit tests on macOS, full Xcode compilation, signing and installation.
- Physical BLE connectivity on S25 Ultra / Series 8.
- Real notification forwarding/replies, HealthKit data access, or Health Connect writes.
- Suspended watch delivery, automatic background health sync, battery use or long-duration reliability.

Deliverable is a first prototype and device-test handoff. APNs/FCM and scheduled background health collection are not implemented in 0.1. The Mac build and physical-device tests are required before claiming end-to-end operation.
