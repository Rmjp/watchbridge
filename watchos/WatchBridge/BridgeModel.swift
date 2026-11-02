import Foundation
import SwiftUI
import CryptoKit
import UserNotifications

struct PhoneItem: Identifiable {
    let id: String
    let app: String
    let title: String
    let body: String
    let actions: [[String: Any]]
}

private struct StoredData: Codable {
    var inbox: [Envelope] = []
    var outbox: [Envelope] = []
    var seen: [String: Int64] = [:]
    var healthAcknowledged: Set<String> = []
    var healthWindowEnd: Double?
    var healthPendingIds: Set<String> = []
    var healthCursor: Double?
}

private final class ForegroundAlerts: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { completionHandler([.banner, .sound]) }
}

@MainActor final class BridgeModel: ObservableObject {
    @Published var securityCode = ""
    @Published var status = "Bluetooth only · find your Galaxy"
    @Published var active = false
    @Published var connected = false
    @Published var pendingCount = 0
    @Published var lastTransfer = "None yet"
    @Published var items = [PhoneItem]()
    @Published var healthStatus = "No health sync yet"
    @Published var backfillDays = 1
    @Published var healthBusy = false
    private var state = LinkState()
    private var saved = StoredData()
    private var loop: Task<Void, Never>?
    private var window = DeliveryWindow()
    private var ownChallenge = ""
    private var peerChallenge = ""
    private var proofReceived = false
    private var peerApproved = false
    private var pairingUntil = Date.distantPast
    private var handshakeStarted = Date.distantPast
    private let bluetooth = WatchBluetooth()
    private let health = WatchHealth()
    private let alerts = ForegroundAlerts()
    private var storeURL: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("bridge.enc") }
    init() {
        do {
            state = try SecureStore.load()
            if state.transport != "bt2" { state = LinkState(); status = "Update both apps, then pair over Bluetooth" }
            active = state.active && state.confirmed
            if state.key.count == 32, let data = try? Data(contentsOf: storeURL) {
                saved = try JSONDecoder().decode(StoredData.self, from: AES.GCM.open(AES.GCM.SealedBox(combined: data), using: SymmetricKey(data: state.key)))
                restoreInbox(); pendingCount = saved.outbox.count
            }
        } catch { status = error.localizedDescription }
        UNUserNotificationCenter.current().delegate = alerts
        // CoreBluetooth invokes these on its explicitly selected main queue. No detached
        // tasks are used, so hello/proof/data retain their wire ordering.
        bluetooth.onFrame = { [weak self] data in DispatchQueue.main.async { self?.received(data) } }
        bluetooth.onStatus = { [weak self] text in DispatchQueue.main.async { self?.status = text } }
        bluetooth.onLink = { [weak self] up in DispatchQueue.main.async { self?.linkChanged(up) } }
    }
    private func persist() throws {
        try SecureStore.save(state); pendingCount = saved.outbox.count
        guard state.key.count == 32 else { return }
        try FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let box = try AES.GCM.seal(JSONEncoder().encode(saved), using: SymmetricKey(data: state.key))
        guard let bytes = box.combined else { throw BridgeError.message("Cannot encrypt local state") }
        try bytes.write(to: storeURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    private func restoreInbox() {
        items = saved.inbox.compactMap { e in
            guard let d = try? BridgeCrypto.decrypt(state, envelope: e), let key = d["key"] as? String else { return nil }
            return PhoneItem(id: key, app: d["app"] as? String ?? "Phone", title: d["title"] as? String ?? "", body: d["body"] as? String ?? "", actions: d["actions"] as? [[String: Any]] ?? [])
        }.reversed()
    }
    func findGalaxy() {
        do {
            if !active {
                bluetooth.stop()
                let key = P256.KeyAgreement.PrivateKey()
                state = LinkState(); state.transport = "bt2"; state.privateKey = key.rawRepresentation; state.publicKey = key.publicKey.x963Representation.base64EncodedString()
                saved = StoredData(); items = []; securityCode = ""; pairingUntil = Date().addingTimeInterval(300); try persist()
            }
            start(); bluetooth.start()
        } catch { status = error.localizedDescription }
    }
    private func resetSession() { connected = false; ownChallenge = ""; peerChallenge = ""; proofReceived = false; peerApproved = false; window.reset(); handshakeStarted = .distantPast }
    private func linkChanged(_ up: Bool) {
        resetSession()
        guard up else { return }
        do {
            guard state.transport == "bt2", !state.privateKey.isEmpty else { throw BridgeError.message("Tap Find Galaxy first") }
            ownChallenge = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0).base64EncodedString() }
            handshakeStarted = Date()
            try sendWire(["wire": "wb-bt2", "kind": "hello", "id": state.id, "publicKey": state.publicKey, "challenge": ownChallenge])
            status = "Authenticating nearby phone…"
        } catch { status = error.localizedDescription; bluetooth.reconnect(reject: true) }
    }
    private func sendWire(_ object: [String: Any]) throws {
        guard bluetooth.send(try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) else { throw BridgeError.message("Bluetooth disconnected; queued data will retry") }
    }
    private func transmit(_ e: Envelope) throws {
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(e))
        try sendWire(["wire": "wb-bt2", "kind": "encrypted", "envelope": object])
    }
    private func transient(_ type: String, _ payload: [String: Any]) throws { try transmit(BridgeCrypto.encrypt(state, type: type, payload: payload)) }
    private func proof() throws {
        guard !ownChallenge.isEmpty, !peerChallenge.isEmpty else { throw BridgeError.message("Phone handshake not ready") }
        try transient("session", ["echo": peerChallenge, "challenge": ownChallenge, "approved": state.confirmed])
    }
    private func activate() throws {
        state.active = state.confirmed && peerApproved; active = state.active; connected = active && proofReceived
        status = connected ? "Connected · encrypted Bluetooth" : "Confirm matching codes on BOTH devices"
        try persist()
    }
    func approve() {
        do {
            guard !securityCode.isEmpty, !peerChallenge.isEmpty, active || Date() < pairingUntil else { throw BridgeError.message("Pairing expired or disconnected; start again") }
            state.confirmed = true; try persist(); try proof(); try activate(); try flush()
        } catch { status = error.localizedDescription }
    }
    private func received(_ bytes: Data) {
        do {
            guard let root = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], root["wire"] as? String == "wb-bt2" else { throw BridgeError.message("Update both apps to Bluetooth 0.2") }
            if root["kind"] as? String == "hello" {
                guard let id = root["id"] as? String, UUID(uuidString: id) != nil, let pub = root["publicKey"] as? String, let challenge = root["challenge"] as? String, Data(base64Encoded: challenge)?.count == 32, peerChallenge.isEmpty, !ownChallenge.isEmpty else { throw BridgeError.message("Invalid Bluetooth handshake") }
                guard state.peerId.isEmpty || (state.peerId == id && state.peerPublic == pub) else { throw BridgeError.message("Different phone; forget pairing on both devices") }
                guard active || Date() < pairingUntil else { throw BridgeError.message("Pairing expired; start again") }
                let (key, code) = try BridgeCrypto.derive(privateKey: state.privateKey, androidPublic: pub, watchPublic: state.publicKey)
                state.peerId = id; state.peerPublic = pub; state.key = key; securityCode = code; peerChallenge = challenge
                try persist(); try proof(); status = active ? "Authenticating Bluetooth…" : "Compare security code with phone"; return
            }
            guard root["kind"] as? String == "encrypted", let raw = root["envelope"], !peerChallenge.isEmpty else { throw BridgeError.message("Handshake required") }
            let e = try JSONDecoder().decode(Envelope.self, from: JSONSerialization.data(withJSONObject: raw))
            let payload = try BridgeCrypto.decrypt(state, envelope: e)
            if e.type == "session" {
                guard BluetoothProof.matches(own: ownChallenge, peer: peerChallenge, echo: payload["echo"] as? String ?? "", challenge: payload["challenge"] as? String ?? ""), let approved = payload["approved"] as? Bool else { throw BridgeError.message("Stale Bluetooth connection proof") }
                proofReceived = true; peerApproved = approved; try activate(); try flush(); return
            }
            guard connected && active else { throw BridgeError.message("Confirm both security codes first") }
            if e.type == "ack" {
                if let id = payload["id"] as? String, window.acknowledge(id) {
                    saved.outbox.removeAll { $0.id == id }; lastTransfer = "Delivered \(Date().formatted(date: .omitted, time: .shortened))"; try persist(); try flush()
                }
                return
            }
            if saved.seen[e.id] == nil {
                try apply(e, payload)
                saved.seen[e.id] = e.expires
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                saved.seen = saved.seen.filter { $0.value > now - 86_400_000 }
                try persist(); lastTransfer = "Received \(Date().formatted(date: .omitted, time: .shortened))"
            }
            try transient("ack", ["id": e.id]); try flush()
        } catch { status = "Bluetooth: \(error.localizedDescription)"; bluetooth.reconnect(reject: true) }
    }
    private func apply(_ e: Envelope, _ payload: [String: Any]) throws {
        switch e.type {
        case "notification":
            guard let key = payload["key"] as? String else { throw BridgeError.message("Missing notification identity") }
            saved.inbox.removeAll { old in (try? BridgeCrypto.decrypt(state, envelope: old)["key"] as? String) == key }
            saved.inbox.append(e); saved.inbox = Array(saved.inbox.suffix(100)); restoreInbox()
            let content = UNMutableNotificationContent(); content.title = payload["app"] as? String ?? "Phone"
            content.body = payload["title"] as? String ?? "New phone notification"; content.sound = .default
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: key, content: content, trigger: nil), withCompletionHandler: nil)
        case "remove":
            let key = payload["key"] as? String ?? ""
            saved.inbox.removeAll { old in (try? BridgeCrypto.decrypt(state, envelope: old)["key"] as? String) == key }; restoreInbox(); UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [key])
        case "reply_result": status = payload["detail"] as? String ?? "Reply result received"
        case "health_result":
            guard let id = payload["batchId"] as? String else { throw BridgeError.message("Missing batch identity") }
            guard saved.healthPendingIds.contains(id) else { return }
            if payload["ok"] as? Bool == true {
                saved.healthAcknowledged.insert(id)
                if saved.healthPendingIds.isSubset(of: saved.healthAcknowledged), let end = saved.healthWindowEnd {
                    saved.healthCursor = end; saved.healthPendingIds.removeAll(); saved.healthWindowEnd = nil; saved.healthAcknowledged.removeAll(); healthStatus = "Health import confirmed by phone"
                }
            } else { healthStatus = "Import failed: \(payload["detail"] as? String ?? "Check phone permissions")" }
        case "ping": status = payload["text"] as? String ?? "Connection test received"
        default: throw BridgeError.message("Unsupported Bluetooth message")
        }
    }
    private func send(_ type: String, _ payload: [String: Any]) throws {
        guard active, saved.outbox.count < 1000 else { throw BridgeError.message("Pair first or reconnect to drain the queue") }
        let e = try BridgeCrypto.encrypt(state, type: type, payload: payload); saved.outbox.append(e); try persist(); try flush()
    }
    private func flush() throws {
        guard connected else { return }
        if window.timedOut() { resetSession(); bluetooth.reconnect(); status = "Transfer stalled · reconnecting to retry"; return }
        guard window.id == nil else { return }
        saved.outbox.removeAll { $0.expires <= Int64(Date().timeIntervalSince1970 * 1000) }
        pendingCount = saved.outbox.count
        guard let e = saved.outbox.first else { return }
        if window.begin(id: e.id, bytes: try JSONEncoder().encode(e).count) {
            do { try transmit(e) } catch { window.reset(); throw error }
        }
    }
    func start() {
        guard state.transport == "bt2", !state.privateKey.isEmpty else { return }
        bluetooth.start()
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                do {
                    if !self.active && Date() > self.pairingUntil { self.bluetooth.stop(); self.status = "Pairing expired · tap Find Galaxy to start again" }
                    else if !self.proofReceived && self.handshakeStarted != .distantPast && Date().timeIntervalSince(self.handshakeStarted) > 20 { self.bluetooth.reconnect(); self.handshakeStarted = Date() }
                    try self.flush()
                } catch { self.status = error.localizedDescription }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
    func suspend() { loop?.cancel(); loop = nil } // CoreBluetooth retains its own permitted background lifecycle.
    func ping() { do { try send("ping", ["text": "Hello from Apple Watch"]) } catch { status = error.localizedDescription } }
    func reply(item: PhoneItem, action: [String: Any], text: String) {
        do { guard let id = action["id"] as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }; try send("reply", ["requestId": UUID().uuidString.lowercased(), "key": item.id, "actionId": id, "text": String(text.prefix(1000))]); status = "Reply queued over Bluetooth" }
        catch { status = error.localizedDescription }
    }
    func requestNotifications() async { do { _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) } catch { status = error.localizedDescription } }
    func syncHealth() async {
        guard !healthBusy else { return }; healthBusy = true; defer { healthBusy = false }
        do {
            guard active else { throw BridgeError.message("Pair over Bluetooth first") }
            let pairedId = state.peerId
            try await health.authorize(); healthStatus = "Reading HealthKit…"
            let end = Date(), start = saved.healthCursor.map { Date(timeIntervalSince1970: $0 - 3600) } ?? end.addingTimeInterval(-Double(backfillDays) * 86400)
            let records = try await health.records(start: start, end: end)
            guard active && state.peerId == pairedId else { throw BridgeError.message("Pairing changed during health query") }
            guard !records.isEmpty else { healthStatus = "No readable samples; check Health permissions and selected window"; return }
            var batches = [Envelope]()
            var batchIds = Set<String>()
            for offset in stride(from: 0, to: records.count, by: 25) {
                let id = UUID().uuidString.lowercased(); batchIds.insert(id)
                batches.append(try BridgeCrypto.encrypt(state, type: "health", payload: ["batchId": id, "records": Array(records[offset..<min(offset + 25, records.count)])]))
            }
            guard saved.outbox.count + batches.count <= 1000 else { throw BridgeError.message("Health queue full; reconnect and let pending transfers finish") }
            // Stage the complete window before sending any batch. A disconnect must not
            // advance the cursor after only a partial subset of this window was staged.
            saved.healthPendingIds = batchIds; saved.healthAcknowledged.removeAll(); saved.healthWindowEnd = end.timeIntervalSince1970
            saved.outbox.append(contentsOf: batches)
            try persist(); healthStatus = "Queued \(records.count) records · keep devices nearby"; try flush()
        } catch { healthStatus = error.localizedDescription }
    }
    func unpair() {
        suspend(); bluetooth.stop(); resetSession(); state = LinkState(); saved = StoredData(); active = false; securityCode = ""; items = []; pendingCount = 0
        do { try SecureStore.save(state); if FileManager.default.fileExists(atPath: storeURL.path) { try FileManager.default.removeItem(at: storeURL) }; status = "Forgotten locally · forget on phone too" } catch { status = error.localizedDescription }
    }
}
