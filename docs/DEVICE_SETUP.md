# Install and run on your devices

## Relay

The devices need one reachable HTTPS relay for initial pairing and internet fallback. An unencrypted development HTTP server is included, but the watch app's App Transport Security requires HTTPS. Running Node on a Mac does not by itself make its address reachable through a valid HTTPS certificate.

On a machine with Node.js 24 or later:

```sh
cd server
npm test
npm start
```

Keep the default backend on `127.0.0.1:8787`. Put an HTTPS reverse proxy in front using your existing domain and a valid device-trusted certificate. Example Caddy configuration (replace the hostname):

```caddyfile
bridge.your-domain.example {
    reverse_proxy 127.0.0.1:8787
}
```

You must supply DNS, network reachability and certificate setup for that hostname. This project does not purchase a domain, start a tunnel, change your firewall, or deploy publicly. Test `https://YOUR-HOST/healthz` from a browser; it should return an `ok` response. No payload belongs in an access-log URL. Keep the SQLite database private and back it up securely if you want to retain pairing across server migration.

For a local-only test use a local reverse proxy/certificate already trusted by both devices. Do not disable certificate validation in code. Apple Watch simulator/macOS certificate trust is separate from physical watch trust. Debug Android allows `http://` for phone-only experiments; watch HTTPS is still required.

## Galaxy S25 Ultra

1. Transfer `dist/WatchBridge-0.1-debug.apk` to the phone and open it. Permit installation from the transfer/file app if Android asks. This is a debug-signed personal test build.
2. Open WatchBridge. Grant Bluetooth permissions. Android may also ask for the app's own notification permission.
3. Enter the reachable HTTPS relay URL, without `/v1` suffix. Tap **Create pairing code**.
4. Keep the phone screen available for code comparison.
5. After confirming on both devices, use **Check connection**, then **Start bridge**. Bluetooth must be on.
6. Open **Enable notification access**, enable WatchBridge in system settings, return, and choose specific apps using **Choose apps**. An empty allowlist forwards nothing.
7. Tap **Allow Health Connect import** and permit all five initial type families you intend to sync. A mixed batch fails if a represented type lacks permission.

Alternative installation with an authorized USB debugging connection:

```sh
adb install -r dist/WatchBridge-0.1-debug.apk
```

No device installation was performed automatically. Android's foreground-service notification indicates the bridge is active. Use **Pause bridge** to stop transfers. Do not assume the debug process survives force-stop, reboot, or all Samsung battery-management states.

## Mac and Apple Watch Series 8

1. Copy the source bundle to your Mac and unzip it. Keep `watchos/` and `test-vectors/` adjacent under the same repository root.
2. With a current compatible Xcode installed, run `cd watchos && swift test` to verify the Apple-side crypto implementation against the Android/Node fixture.
   Alternatively, from the extracted repository root run `sh scripts/check-mac.sh` to perform the crypto tests and an unsigned watch simulator build together. Results are written under `dist/mac-checks/`.
3. Open `watchos/WatchBridge.xcodeproj` in Xcode. Select the WatchBridge target, Signing & Capabilities, and your signing team. Change `dev.watchbridge.watch` to a unique bundle identifier associated with your team. Ensure HealthKit capability is supported by your provisioning. The project does not contain developer credentials.
4. Select your paired physical watch as run destination. Complete Xcode's device pairing/Developer Mode prompts if requested. Build and run. Apple provisioning limitations may require an appropriate developer-program membership; the health capability must be in the signed profile.
5. In the watch app, enter the same HTTPS relay URL and the six-digit phone pairing code, then **Join phone**.
6. Compare the **security code** shown on both devices. It is a separate code from the initial pairing code. Confirm only if both match; tap confirm on each device.
7. Keep the watch app visible. Use **Send test to phone** or the phone's test button to verify both directions.
8. Tap **Allow watch alerts**, then **Sync health now**. Grant selected HealthKit read permissions. Start with the default one-day backfill. Check the phone's status for import results and inspect Health Connect's app data.

If Xcode reports compile/signing errors, save the complete first error and the Xcode/watchOS version. This source was prepared on Windows and its Apple build has not yet been verified. Do not infer a watch build passed from the Android APK result.

## Troubleshooting

- **Codes do not match:** cancel, revoke the pairing if possible, and create a fresh session. Do not confirm.
- **Code expired/already used:** create a fresh phone pairing code; it lasts ten minutes and permits one watch join.
- **No notifications:** confirm the bridge is running, system notification access is enabled, the source app is allowed, and the notification is not an ongoing/group-summary event. Test with the watch app open first.
- **No Bluetooth:** grant all requested phone Bluetooth permissions and enable Bluetooth on both devices. Relay can still work while the watch app is open and online.
- **Network error:** verify the same HTTPS base URL, certificate trust, actual device connectivity, and `/healthz`. A localhost URL refers to each device itself and is unsuitable for device pairing.
- **Reply expired:** trigger a fresh notification exposing a free-form reply action. Actions expire after ten minutes, notification removal/update or phone process loss.
- **Health empty:** HealthKit can return empty data when reads are denied. Verify permissions and actual data on the watch. One-day backfill may not include an older workout.
- **Health import failure:** enable corresponding phone Health Connect write permissions, then sync again. Stable source IDs prevent normal retries from inserting duplicates.
- **No suspended alerts:** expected unresolved prototype limitation. No APNs delivery or scheduled background polling is included.
- **Revocation:** unpair while online so the relay revokes both tokens. The other app notices the invalid token on its next relay request. Offline revocation cannot instantly stop an offline peer.
- **Previously imported health data:** unpairing clears bridge keys and queues; it does not erase records already written to Health Connect. Manage those records through Health Connect's app-data controls.
