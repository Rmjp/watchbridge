# Bluetooth-only setup (0.2)

Update both apps. The 0.1 relay app is not compatible with this Bluetooth service. No relay URL or cloud deployment is needed.

## Galaxy

1. Transfer/install `dist/WatchBridge-0.2-debug.apk`. If signing differs from your installed build, Android will reject the update; uninstalling clears its app pairing, so pair again afterward.
2. Open WatchBridge, grant Bluetooth permissions, and turn Bluetooth on.
3. Tap **Start new Bluetooth pairing**. This forgets the previous local pairing and queued transfers, not Health Connect records.
4. Keep this screen open while finding the phone from the watch. Compare the security code on both displays and confirm only if identical.
5. Enable **notification access** in system settings and choose allowed apps. An empty allowlist forwards nothing. Group-summary and ongoing-service notifications are deliberately excluded.
6. The pairing action starts the foreground Bluetooth service. Use **Start bridge** to resume a saved pairing after pause/process restart. Keep the service enabled while testing.
7. For health, grant the corresponding Health Connect write permissions before starting a watch import.

## Watch / Mac

1. Check out `codex/bluetooth-only` or unpack its source bundle. Run `cd watchos && swift test` on your Mac.
2. Open the Xcode project. Keep your existing signing team and unique bundle ID, with HealthKit capability. Use Xcode's normal DerivedData location outside any cloud-synced folder to avoid Finder-metadata signing failures seen during 0.1 verification.
3. Install/run on the Series 8. Your existing Apple activation remains intact.
4. Tap **Find Galaxy** after phone pairing starts. This searches only for the new WatchBridge BLE service.
5. Compare both codes and tap **Codes match — confirm** on each device. No data flows until both approvals and a fresh encrypted connection proof succeed.
6. Tap **Allow watch alerts**. Keep the app open and send a test from the phone. Then trigger a fresh real notification in one selected phone app and inspect **Phone inbox**.
7. Text replies require the original Android app's free-form notification reply action. They expire after ten minutes or phone-process loss.
8. For health, choose a one-day initial window and tap **Sync health now**. Health queues in 25-record batches and advances its cursor only after Android reports all imports successful.

## Reconnect and reset

- No Wi-Fi or internet is required, including first pairing. You can turn both devices' Wi-Fi off for this test while leaving Bluetooth on.
- Out-of-range messages remain on the sender until acknowledged or expired. Return nearby with the phone bridge enabled and tap **Reconnect Bluetooth** on the watch if necessary.
- A watchOS background wake is not guaranteed. Test with the app open before trying the watch face/wrist down.
- A different pinned peer key/ID is rejected. Use **Forget pairing** on both devices before switching partners or starting fresh.
- Forget works offline and only affects that device. It clears local keys, app inbox/outbox, and sync state. Previously imported Health Connect records remain in the system store.
- If one-sided confirmation or pending discovery expires, start fresh pairing on both sides within five minutes.
- Connection status, pending count and last transfer are diagnostic hints. A data receipt does not prove watchOS showed an alert or that a messaging app delivered a reply to its remote recipient.

If compilation fails on the Mac, retain the first compiler error and Xcode/watchOS versions. Mac 0.1 success is not proof that the new 0.2 Swift changes compiled. Follow [BUILD_STATUS.md](BUILD_STATUS.md) and CI results for the current revision.
