# Bluetooth 0.2 physical-device checks

All physical tests below are pending for the new Bluetooth-only version. Earlier watch installation/build results apply to 0.1.

Record Android/One UI, watchOS, Xcode/macOS, both app versions, and test timestamps. Do not share notification contents, health samples, private keys or application database files in logs.

| Test | Expected |
|---|---|
| Wi-Fi/mobile data off, Bluetooth on | Pair and send test messages with no server |
| One-side approval only | No authenticated data transfer |
| Codes do not match | Cancel; do not confirm |
| Both approvals | Connected/authenticated state on both devices |
| Allowlisted fresh notification | Inbox entry on watch; alert subject to watch permissions/runtime |
| Disallowed app | No forwarded content |
| Supported text reply | Original action attempted once; result shown |
| Removed notification | Watch inbox entry removed |
| Move out of range | Pending data remains; no false delivery status |
| Return nearby | Fresh challenge proof; pending messages acknowledged/drained |
| Disconnect during a large health batch | Partial bytes discarded; data retries after reconnect |
| Duplicate delivery/lost ACK | No duplicate effect; receiver ACKs again |
| Relaunch apps | Pairing and encrypted queues persist; resume phone bridge manually |
| Sleep/wrist down/watch face | Measure delivered/inbox/alert latency; no assumed SLA |
| Repeated health import | Stable source IDs prevent duplicate inserts |
| Denied health permission | Failure shown; health cursor does not advance |
| Forget offline | Local keys/queue cleared; forget other device separately |
| Different phone/watch | Pinned identity rejected until explicit reset |
| 48-hour battery comparison | Compare baseline against enabled bridge |

Watch background limitations remain a real feasibility gate. A successful foreground transfer is not evidence of reliable screen-off delivery.
