# WatchBridge protocol v1 — prototype

Independent protocol. JSON/UTF-8 for this test version; protobuf migration requires a new negotiated version. No Merge identifiers or keys are reused.

## Pairing

Android creates a relay pairing session (`POST /v1/pair/create` with deviceId and publicKey). Watch joins with six-digit code (`POST /v1/pair/join`). Both poll `GET /v1/pair` using their own returned bearer token. Generate a fresh UUID and P-256 agreement keypair for every pairing attempt. Public keys are base64 SEC1 uncompressed 65-byte X9.63 points.

Transcript is UTF-8 `androidPublicKeyBase64|watchPublicKeyBase64`, Android first. Salt = SHA256(transcript). ECDH shared secret feeds HKDF-SHA256 (salt above), info `watchbridge.v1`, output 32 bytes. Display SHA256(sharedKey || salt)'s first four bytes as unsigned big-endian integer modulo 1,000,000, padded to six digits. User must compare both displays and approve separately. Relay pairing code and security comparison code are different. A short-code comparison provides probabilistic authentication; it is a prototype requiring security review before public distribution.

Only activate after both approvals. Each device must keep its own local confirmation; do not trust a server boolean as evidence of user approval. Never replace a confirmed peer key within a pairing session.

## Encrypted envelopes

`{v:1,id,sender,recipient,type,created,expires,nonce,ciphertext}`. IDs UUID strings. Timestamps integer Unix milliseconds. Nonce: fresh random 12 bytes for every encryption, base64. Ciphertext: AES-256-GCM output ciphertext || 16-byte tag, base64. Max envelope 350,000 UTF-8 bytes. AAD UTF-8 exactly `watchbridge.v1|id|sender|recipient|type|created|expires`. No whitespace or alternate timestamp formatting. Session key directional HKDF-SHA256 with common key as input, empty salt, info `watchbridge.v1.sender:<sender UUID>`, output32. Persist encrypted envelope before delivery; retries reuse the same bytes/ID, never re-encrypt with reused nonce. Reject wrong peer, expiry, unknown version/type, invalid tag. Deduplicate IDs persistently across BLE and relay.

Types: notification `{key,app,title,body,posted,actions:[{id,title,reply}]}`, remove `{key}`, reply `{requestId,key,actionId,text}`, reply_result `{requestId,ok,detail}`, health `{batchId,records:[...]}`, health_result `{batchId,ok,detail}`, ping `{text}`. No executable code, file paths, arbitrary intents or URLs are dispatched from payloads.

Health records `{id,type,start,end,value?,samples?,stages?,activity?}`. UTC ISO8601 with milliseconds and Z. Types steps, heartRate (samples `{time,bpm}`), activeEnergy (kcal), sleep (stages `{start,end,stage}` stage awake/light/deep/rem/unknown), workout (activity walking/running/cycling/other). IDs use source HealthKit UUID and type. Health Connect upserts use stable clientRecordId. This initial version imports additions, not source deletions or historical edits. Sync cursors advance after confirmed Android health import only.

## BLE

Service `7d7a0001-6f4b-4ef0-9a9d-a98551c2a601`; phone→watch notify characteristic `7d7a0002-6f4b-4ef0-9a9d-a98551c2a601`; watch→phone write-with-response `7d7a0003-6f4b-4ef0-9a9d-a98551c2a601`. Standard CCCD 2902 on notify characteristic. Each direction is one UTF-8 encrypted JSON envelope terminated by LF, chunked to MTU-safe blocks. Max buffered frame350000 bytes, no embedded unescaped LF. Android initially sends20-byte blocks and waits for notification-sent callback; watch uses maximumWriteValueLength(.withResponse) and waits for didWriteValueFor. One frame at a time; application relay acknowledgements and ID deduplication provide fallback. BLE is only accepted from authenticated envelope identities, not a trusted MAC address. BLE notification frames are also queued for relay, allowing deduplication and recovery from mid-frame disconnects.

## Relay

POST /v1/events queues authenticated opaque envelopes to only the paired recipient. GET /v1/events returns max50; POST /v1/events/ack `{ids}` acknowledges only the caller's queue. POST /v1/revoke removes pending events and rejects both tokens. Tokens protect transport access; they do not decrypt data. HTTPS required on devices except explicit debug local-network testing. App polling runs only while allowed by lifecycle; there is no push implementation in v0.1, and no claim of always-on wrist-down delivery.
