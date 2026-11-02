# WatchBridge 0.2 — Bluetooth only

An original companion for Galaxy S25 Ultra and Apple Watch Series 8. **Pairing, notifications, replies and health transfers now use direct encrypted Bluetooth. No relay URL, Wi-Fi, account or running PC is required.**

This update must be installed on **both** devices. The previous relay-based 0.1 watch app cannot connect to 0.2. The watch retains Apple's normal activation/iPhone setup; this app does not replace that.

## Install and pair

1. Install `dist/WatchBridge-0.2-debug.apk` on Galaxy, and build/sign the updated watchOS app on your Mac.
2. Grant phone Bluetooth permissions. Tap **Start new Bluetooth pairing**.
3. On the watch tap **Find Galaxy**. Keep both apps visible and devices nearby.
4. Compare the security codes on both screens; confirm on **both** only if they match. Pairing expires after five minutes.
5. On the phone enable notification-listener access and choose allowed apps. The foreground bridge service stays running while enabled.
6. Allow watch alerts, then send a fresh notification from a selected app. Check the watch's **Phone inbox**.
7. For health, grant phone Health Connect write permissions and tap **Sync health now** on the watch.

Full instructions: [device setup](docs/DEVICE_SETUP.md). Acceptance tests: [device checklist](docs/DEVICE_TEST_CHECKLIST.md).

## Features

- Bluetooth discovery, local security-code comparison, and independent approvals.
- P-256 ECDH, HKDF-SHA256, directional AES-256-GCM encryption and fresh challenges proving the peer's key on each connection.
- Durable encrypted outboxes. One data frame per direction awaits an authenticated application acknowledgement; duplicate messages receive an ACK without repeating effects. Partial streams reset on disconnect and pending data retries after reconnect.
- Selected Android notification forwarding, removals and supported text replies. Reply handles expire after ten minutes or phone-process loss; external reply attempts are at-most-once.
- Manual HealthKit → Health Connect import for steps, heart rate, active calories, individual sleep-stage samples and workout summaries. Stable source IDs avoid duplicate inserts; the health cursor advances only after the phone confirms every batch in the staged window.
- Connection state, pending count, last transfer, pause/resume, and offline local forget-pairing on either device.
- The Android package excludes the INTERNET permission. Neither mobile app uses relay HTTP endpoints.

## Limits to verify on devices

**Background Bluetooth is controlled by watchOS.** Keeping a CoreBluetooth central/subscription alive does not guarantee unlimited runtime or immediate wrist-down alerts. This version retains the Bluetooth delegate when the UI backgrounds but stops its foreground retry timer. Screen-off notification delivery, reconnects after suspension, battery use, actual replies and Health Connect writes need physical-device validation.

Out of range, transfers queue locally, up to 1,000 pending messages per app. Health expiry is seven days and other messages expire after one day. Expired events are discarded. No server forwards anything while devices are apart.

Health synchronization is manual. It imports additions, not source edits/deletions. Sleep samples remain separate sessions rather than full-night aggregation; source time-zone display and Samsung Health visibility are not guaranteed. Data available on the watch can differ from iPhone Health history. A HealthKit query returning no samples can mean permission denial. Health Connect records already imported remain after forgetting pairing.

Forgetting pairing clears local keys and pending app data. It does not remotely erase the other device; forget on both sides before pairing again. Legacy relay credentials are not reused for Bluetooth pairing. The cryptographic pairing protocol needs independent review before public distribution.

## Build and tests

Android: JDK 21, platform SDK 36, Gradle wrapper 8.13:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Use `gradlew.bat` on Windows. Configure `ANDROID_HOME` or a local SDK path. Mac shared crypto/framing tests:

```sh
cd watchos
swift test
```

Open `watchos/WatchBridge.xcodeproj`, select your signing team/unique bundle ID and physical watch. Build outside a cloud-synced DerivedData directory. `sh scripts/check-mac.sh` checks crypto and a simulator build; CI also compiles the unsigned watch device app.

Protocol: [Bluetooth v2 specification](protocol/BLUETOOTH_V2.md). [Build status](docs/BUILD_STATUS.md) distinguishes 0.1 Mac results from this update's checks. `server/` and the original relay specification remain for history; **0.2 does not require or contact them**. Public fixtures under `test-vectors/` are test keys only.
