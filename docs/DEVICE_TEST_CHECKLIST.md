# Physical-device acceptance record

Record phone model/Android/One UI, watch model/watchOS, Xcode/macOS, relay hostname, test time and network topology. Do not include bearer tokens, private keys, health samples or notification content in diagnostic uploads.

| Test | Expected | Observed |
|---|---|---|
| Mac `swift test` | Pairing key/SAS/AES-GCM vector matches | Passed, Xcode 16.2 / Swift 6.0.3, 1 November 2026; fixture encoding corrected |
| Xcode simulator build | Builds without signing | Passed, watchOS 11.2 simulator SDK/runtime, 1 November 2026 |
| Xcode device build | Compiles device architectures without signing | Passed, arm64 and arm64_32, Xcode 16.2, 1 November 2026 |
| Simulator installation and launch | Shows pairing controls | Passed on Series 8 (45mm), watchOS 11.2, with simulated entitlements and ad hoc signing; no real pairing performed |
| Physical-device provisioning | Signs and installs with HealthKit | Not run; paired Series 8 has Developer Mode disabled |
| Pairing | Both codes match; one-sided confirmation cannot transfer data | Not run |
| Two-way test | Phone and watch receive peer test | Not run |
| Allowed app notification | Watch inbox receives title/body when active | Not run |
| Disallowed app | No forwarded content | Not run |
| Reply | Reply action executes once or displays a specific failure | Not run |
| Reply after ten minutes | Explicit expired response | Not run |
| Notification removal | Removes watch inbox entry | Not run |
| Phone bridge paused | No new capture/transfers | Not run |
| BLE-only after pairing | Test message works while relay unavailable, both apps active | Not run |
| Bluetooth disconnected | Foreground relay delivers | Not run |
| Retry via both transports | No duplicate inbox effect or repeated reply | Not run |
| Watch suspended / wrist down | Measure received vs shown vs delayed, no assumed SLA | Not run |
| Health one-day backfill | Counts/times/types match readable source data | Not run |
| Repeat health sync | No duplicate source IDs | Not run |
| One health permission denied | Clear error and retry, cursor does not advance on failure | Not run |
| Sleep | Individual sample sessions are visible; aggregation deferred | Not run |
| Time zone / DST | UTC intervals remain correct; original zone display deferred | Not run |
| Reboot/process restart | Pairing persists; manually restart phone bridge/watch app | Not run |
| Online unpair | Relay refuses both tokens on next request | Not run |
| 48-hour use | Record baseline and enabled battery use and delivery gaps | Not run |

Do not mark a test passed solely because the relay accepted an envelope. Health requires successful Health Connect insertion; notifications require observing the inbox/alert; a source app accepting a PendingIntent is not proof of remote message delivery.
