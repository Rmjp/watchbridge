# WatchBridge — personal prototype 0.1

Original Android + watchOS companion for a Galaxy S25 Ultra and Apple Watch Series 8. This repository includes a compiled Android debug APK, a standalone watchOS Xcode project, a local relay, and shared cryptographic test vectors.

**This is the first device-test build, not a verified replacement for Merge.** The original Windows verification covered Android compilation and its crypto test. Mac verification now passes the Swift interoperability test, all three relay integration tests, and an unsigned watchOS simulator build with Xcode 16.2. See [docs/BUILD_STATUS.md](docs/BUILD_STATUS.md). End-to-end device functionality, background delivery, and battery impact remain unverified.

## Included

- Two-screen security-code comparison and independent approval, P-256 ECDH, HKDF-SHA256, directional AES-256-GCM keys, encrypted local watch state and Android Keystore storage.
- Phone BLE GATT service and watch BLE central; encrypted relay queue as a second transport; message ID deduplication.
- Android notification app allowlist; encrypted watch inbox; source-supported text replies with ten-minute action expiry.
- Manual HealthKit → Health Connect import for steps, heart rate, active energy, sleep samples and workout summaries. One-day default backfill, optional seven days. Subsequent syncs use an overlapping time window and source UUIDs for duplicate protection.
- Explicit bridge start/pause, revoke/unpair, visible phone foreground service, expiring relay queues.

## Deliberate prototype limits

- The watch polls the relay while its app is active. APNs/FCM push and background HealthKit scheduling are **not implemented**. BLE callbacks may work during available runtime, but do not assume notifications arrive when the watch app is suspended.
- Watch local alerts use generic text; content is decrypted inside the inbox. No promise of full-text wrist-down alerts.
- Health sync handles additions, not source deletions/edits. Sleep samples are individual Health Connect sleep sessions, not yet consolidated into complete nights. Source data on the watch may differ from the paired iPhone's Health library. Empty HealthKit queries do not prove read permission was granted.
- Health batches require all represented write permissions. If a batch fails after partial insertion, retrying uses stable client IDs to avoid duplicates. A cursor advances only after all batches receive successful import results; retry manual sync if a result is lost.
- First health query is bounded to 10,000 samples per type; a full result at that limit is rejected instead of silently advancing past missing samples. Use a shorter first backfill.
- Replies are at-most-once attempts: Android persists receipt before invoking a PendingIntent. If the phone crashes between those steps, a reply may remain unexecuted rather than be sent twice. Notification actions expire after ten minutes and after process loss.
- There is no automatic reboot start, system call audio, Apple activation replacement, ECG interpretation, automatic hotspot control, or Samsung Health display guarantee.
- Payloads are encrypted, but relay device IDs, timings, sizes and tokens are metadata. Pairing and operational security need independent review before public distribution.

## First setup

Use [docs/DEVICE_SETUP.md](docs/DEVICE_SETUP.md) for the complete phone/Mac/watch instructions. Your watch is already activated and you have a Mac; retain that working Apple setup.

1. Start the relay on a machine both devices can reach through **HTTPS**.
2. Install `dist/WatchBridge-0.1-debug.apk` on the S25 Ultra.
3. On your Mac, open `watchos/WatchBridge.xcodeproj`, choose your Apple signing team and a unique bundle ID, select the physical Series 8, then build/run.
4. Use the same relay URL on both devices. Create a pairing code on Android, join on the watch, compare the separate security codes, and confirm on both.
5. Grant phone Bluetooth and notification-listener access, choose allowed apps, and start the bridge. Allow Health Connect writes on the phone, then tap **Sync health now** on the watch.

## Development

Relay: Node.js 24+, no npm dependencies.

```sh
cd server
npm test
npm start
```

The relay binds `127.0.0.1:8787` by default; use a local HTTPS reverse proxy with a device-trusted certificate. Only expose the HTTPS endpoint. `DB_PATH`, `HOST`, and `PORT` configure storage/listening. The server's HTTP port is a reverse-proxy backend, not a production public endpoint. No public hosting was purchased or deployed.

Android: JDK 21, Android SDK platform 36, Gradle wrapper 8.13.

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

On Windows use `gradlew.bat`. Set `ANDROID_HOME` or create `android/local.properties` with your SDK path. Release builds forbid cleartext networking; the debug APK permits HTTP for isolated local tests. Watch ATS remains HTTPS-only.

Mac crypto checks:

```sh
cd watchos
swift test
xcodebuild -project WatchBridge.xcodeproj -scheme WatchBridge \
  -destination 'generic/platform=watchOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The Swift test validates against the same public test vector used by Android. From the repository root, `sh scripts/check-mac.sh` runs both Mac checks and saves logs and build products to `dist/mac-checks/`. Install a watchOS simulator runtime in Xcode Settings > Components if only the SDK is present. `python3 watchos/generate_project.py` regenerates the checked-in Xcode project without XcodeGen.

Protocol: [protocol/SPEC.md](protocol/SPEC.md). Initial implementation uses versioned JSON and native platform P-256, rather than the planning document's candidate protobuf/X25519. This reduces initial dependencies and provides shared test vectors; any incompatible format change requires a protocol version bump. Test keys under `test-vectors/` are public fixtures, not real credentials.

## Next milestone

Run [docs/DEVICE_TEST_CHECKLIST.md](docs/DEVICE_TEST_CHECKLIST.md) on the actual phone/watch. Record OS versions, foreground and wrist-down behavior, real Health Connect writes, reconnects and reply results. Use that evidence to implement the planned push/background behavior and improve sleep/source-deletion handling. See [COMPANION_APP_PLAN.md](COMPANION_APP_PLAN.md) for the broader roadmap.
