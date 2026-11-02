# Bluetooth wire v2 / encrypted envelope v1

Version 0.2 uses a new service UUID `7d7a1001-6f4b-4ef0-9a9d-a98551c2a601`. Phone→watch characteristic `7d7a1002-...` uses GATT indications; watch→phone `7d7a1003-...` uses write-with-response. The UUID suffix is `6f4b-4ef0-9a9d-a98551c2a601` for all three. Standard CCCD 2902 enables indications. The watch waits for both its write characteristic and successful indication subscription before its first hello.

Each direction is one bounded LF-delimited UTF-8 JSON stream. Fragments respect negotiated ATT payload sizes, initially 20 bytes on Android, capped at 180. GATT completion callbacks pace one fragment at a time. Frame and queue limits are checked before buffering. A disconnect drops all partial bytes and transport fragments, while durable encrypted application messages remain queued.

## Pairing and reconnect

Phone opens an explicit five-minute pairing window, generates a new UUID/P-256 keypair, and advertises. Watch's Find Galaxy generates its own new UUID/keypair and scans. No server or account exists in this flow. Existing pairings pin peer public key and device ID; replacing them requires explicit local forget/new pairing.

Watch sends `{"wire":"wb-bt2","kind":"hello","id":UUID,"publicKey":base64X963,"challenge":base64Random32}`. Phone replies with the same fields and its own fresh 32-byte connection challenge. Uncompressed P-256 public points use 65-byte SEC1/X9.63 format.

Key derivation and security code are unchanged from the original crypto test vectors: transcript Android public key first, then `|`, then watch public key; salt SHA256(transcript); HKDF-SHA256(shared secret, salt, `watchbridge.v1`) yields 32-byte pair key. Display six-digit SAS from SHA256(pairKey||salt), first four bytes big-endian modulo1,000,000. User compares displays and approves independently. MITM resistance depends on correctly comparing codes and not accepting mismatches; this is a prototype short-authentication-string protocol requiring review.

Next each peer sends an encrypted `session` envelope with payload `{echo:otherChallenge,challenge:ownChallenge,approved:localUserApproval}`. A connection becomes authenticated only when both fresh challenges match and both user approvals are true. Cached pairing alone is insufficient for a fresh connection. Encrypted data and ACKs are rejected before this gate. Twenty seconds without a valid proof disconnects the candidate; pairing can continue within the five-minute window.

Encrypted wire wrapper: `{"wire":"wb-bt2","kind":"encrypted","envelope":{...}}`. Envelope version1, AES-GCM, directional HKDF, nonce/tag encoding and associated data are as defined in [SPEC.md](SPEC.md). The old HTTP transport in that document is legacy only. Control types `session` and `ack` use the same authenticated encryption and are not themselves acknowledged.

## Delivery

Regular events (`notification`, `remove`, `reply`, `reply_result`, `health`, `health_result`, `ping`) are encrypted and saved durably before transmission. One event at a time per direction waits for an encrypted `ack` payload `{id:eventID}`. A matching ACK removes only that pending event. Processing and dedup state are saved before acknowledging; duplicates repeat the ACK rather than the effect. Transport-layer GATT acknowledgements are distinct from these application receipts.

An adaptive timeout (30 seconds plus 10ms per encoded envelope byte) forces disconnect/reset before retransmitting. Retrying directly into an unfinished byte stream is forbidden. Timers run when their host has runtime; watch background suspension can postpone retry until foreground/reconnection.

Notification actions are at-most-once attempts: the phone persists their event receipt before invoking an external PendingIntent. A crash during that gap can lose an action rather than repeat a reply. Health inserts are idempotent by source record ID; successful `health_result` confirms the import window, while a data ACK alone does not. The watch stages every health batch for a window before sending any, preventing cursor advance after a partially staged window.

This protocol does not promise notifications outside Bluetooth range or unrestricted watchOS background operation. No workout/audio-session workaround is used to keep the app alive.
