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
    var healthWindowEnd: Double? = nil
    var healthPendingIds: Set<String> = []
    var healthCursor: Double? = nil
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor final class BridgeModel: ObservableObject {
    @Published var relayURL = ""
    @Published var pairingCode = ""
    @Published var securityCode = ""
    @Published var status = "Pair with your Galaxy"
    @Published var active = false
    @Published var items = [PhoneItem]()
    @Published var healthStatus = "No health sync yet"
    @Published var backfillDays = 1
    @Published var healthBusy = false
    private var state = LinkState()
    private var saved = StoredData()
    private var loop: Task<Void, Never>?
    private var syncing = false
    private var receiving = Set<String>()
    private var backgrounded = false
    private let bluetooth = WatchBluetooth()
    private let health = WatchHealth()
    private var session = URLSession(configuration: .ephemeral, delegate: NoRedirects(), delegateQueue: nil)
    private var storeURL: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("bridge.enc") }
    init() {
        do {
            state = try SecureStore.load(); relayURL = state.relay; active = state.active && state.confirmed
            if state.key.count == 32, let data = try? Data(contentsOf: storeURL) {
                let box = try AES.GCM.SealedBox(combined: data)
                saved = try JSONDecoder().decode(StoredData.self, from: AES.GCM.open(box, using: SymmetricKey(data: state.key)))
                restoreInbox()
            }
        } catch { status = error.localizedDescription }
        bluetooth.onFrame = { [weak self] data in Task { @MainActor in
            guard let self else { return }
            do { try await self.receive(JSONDecoder().decode(Envelope.self, from: data)) } catch { self.status = "Message rejected: \(error.localizedDescription)" }
        } }
        bluetooth.onStatus = { [weak self] text in Task { @MainActor in self?.status = text } }
    }
    private func persist() throws {
        try SecureStore.save(state)
        guard state.key.count == 32 else { return }
        try FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let box = try AES.GCM.seal(JSONEncoder().encode(saved), using: SymmetricKey(data: state.key))
        guard let bytes = box.combined else { throw BridgeError.message("Cannot encrypt local state") }
        try bytes.write(to: storeURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    private func restoreInbox() {
        items = saved.inbox.compactMap { e in
            guard let data = try? BridgeCrypto.decrypt(state, envelope: e), let key = data["key"] as? String else { return nil }
            return PhoneItem(id: key, app: data["app"] as? String ?? "Phone", title: data["title"] as? String ?? "", body: data["body"] as? String ?? "", actions: data["actions"] as? [[String: Any]] ?? [])
        }.reversed()
    }
    private func request(_ path: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        guard let base = URL(string: state.relay), let scheme = base.scheme, scheme == "https" || (scheme == "http" && _isDebugAssertConfiguration()) else { throw BridgeError.message("Enter an HTTPS relay URL") }
        let url = base.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
        var req = URLRequest(url: url); req.timeoutInterval = 12
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !state.token.isEmpty { req.setValue("Bearer \(state.token)", forHTTPHeaderField: "Authorization") }
        if let body { req.httpMethod = "POST"; req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse, let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw BridgeError.message("Invalid relay response") }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 { active = false; state.active = false; bluetooth.stop(); try? persist() }
            throw BridgeError.message(result["error"] as? String ?? "Relay request failed")
        }
        return result
    }
    func join() async {
        do {
            guard !state.active else { throw BridgeError.message("Unpair before joining again") }
            let key = P256.KeyAgreement.PrivateKey()
            state = LinkState(); state.relay = relayURL.trimmingCharacters(in: .whitespacesAndNewlines); state.privateKey = key.rawRepresentation; state.publicKey = key.publicKey.x963Representation.base64EncodedString()
            let response = try await request("/v1/pair/join", body: ["deviceId": state.id, "publicKey": state.publicKey, "code": pairingCode])
            guard let token = response["token"] as? String else { throw BridgeError.message("No device token") }
            state.token = token; saved = StoredData(); try persist(); try await refreshPair(); start()
        } catch { status = error.localizedDescription }
    }
    func refreshPair() async throws {
        guard !state.token.isEmpty else { return }
        let p = try await request("/v1/pair")
        if let publicKey = p["peerPublicKey"] as? String, let id = p["peerId"] as? String {
            guard state.peerPublic.isEmpty || state.peerPublic == publicKey else { throw BridgeError.message("Peer key changed. Unpair and retry.") }
            guard state.peerId.isEmpty || state.peerId == id else { throw BridgeError.message("Peer identity changed. Unpair and retry.") }
            let (key, code) = try BridgeCrypto.derive(privateKey: state.privateKey, androidPublic: publicKey, watchPublic: state.publicKey)
            state.key = key; state.peerPublic = publicKey; state.peerId = id; securityCode = code
        }
        state.active = (p["active"] as? Bool ?? false) && state.confirmed; active = state.active; try persist()
        status = active ? "Paired with Galaxy" : "Compare security codes"
        if active && !backgrounded { bluetooth.start() }
    }
    func approve() async {
        do { guard !securityCode.isEmpty else { return }; _ = try await request("/v1/pair/approve", body: [:]); state.confirmed = true; try persist(); try await refreshPair() }
        catch { status = error.localizedDescription }
    }
    func start() {
        backgrounded = false
        guard loop == nil, !state.token.isEmpty else { return }
        if active { bluetooth.start() }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                do { if !self.active { try await self.refreshPair() } else { try await self.sync() } }
                catch { self.status = "Relay: \(error.localizedDescription)" }
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }
    func suspend() { backgrounded = true; loop?.cancel(); loop = nil }
    private func send(_ type: String, _ payload: [String: Any]) throws {
        let e = try BridgeCrypto.encrypt(state, type: type, payload: payload)
        saved.outbox.append(e); try persist(); bluetooth.send(try JSONEncoder().encode(e))
    }
    func ping() { do { try send("ping", ["text": "Hello from Apple Watch"]) } catch { status = error.localizedDescription } }
    private func sync() async throws {
        guard !syncing else { return }; syncing = true; defer { syncing = false }
        for e in saved.outbox.prefix(50) {
            if e.expires < Int64(Date().timeIntervalSince1970 * 1000) { saved.outbox.removeAll { $0.id == e.id }; try persist(); continue }
            let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(e)) as! [String: Any]
            _ = try await request("/v1/events", body: object)
            saved.outbox.removeAll { $0.id == e.id }; try persist()
        }
        let result = try await request("/v1/events")
        let raw = try JSONSerialization.data(withJSONObject: result["events"] ?? [])
        let events = try JSONDecoder().decode([Envelope].self, from: raw)
        var ack = [String]()
        for e in events { do { try await receive(e); ack.append(e.id) } catch { status = "Rejected message: \(error.localizedDescription)" } }
        if !ack.isEmpty { _ = try await request("/v1/events/ack", body: ["ids": ack]) }
    }
    private func receive(_ e: Envelope) async throws {
        let payload = try BridgeCrypto.decrypt(state, envelope: e)
        if saved.seen[e.id] != nil { return }
        guard !receiving.contains(e.id) else { throw BridgeError.message("Message processing") }
        receiving.insert(e.id); defer { receiving.remove(e.id) }
        switch e.type {
        case "notification":
            guard let key = payload["key"] as? String else { throw BridgeError.message("Missing notification identity") }
            saved.inbox.removeAll { old in (try? BridgeCrypto.decrypt(state, envelope: old)["key"] as? String) == key }
            saved.inbox.append(e); saved.inbox = Array(saved.inbox.suffix(100)); restoreInbox()
            let content = UNMutableNotificationContent()
            content.title = payload["app"] as? String ?? "Phone notification"
            content.body = "New encrypted phone notification. Open WatchBridge to read."
            content.sound = .default
            // Foreground/local best-effort alert, not APNs or guaranteed suspended delivery.
            try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: key, content: content, trigger: nil))
        case "remove":
            let key = payload["key"] as? String ?? ""
            saved.inbox.removeAll { old in (try? BridgeCrypto.decrypt(state, envelope: old)["key"] as? String) == key }; restoreInbox()
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [key])
        case "reply_result": status = payload["detail"] as? String ?? "Reply result received"
        case "health_result":
            guard let id = payload["batchId"] as? String else { throw BridgeError.message("Missing batch identity") }
            if payload["ok"] as? Bool == true {
                saved.healthAcknowledged.insert(id)
                if saved.healthPendingIds.isSubset(of: saved.healthAcknowledged), let end = saved.healthWindowEnd {
                    saved.healthCursor = end; saved.healthPendingIds.removeAll(); saved.healthWindowEnd = nil; saved.healthAcknowledged.removeAll(); healthStatus = "Health import confirmed by phone"
                }
            } else { healthStatus = "Import failed: \(payload["detail"] as? String ?? "Check phone permissions")" }
        case "ping": status = payload["text"] as? String ?? "Connection test received"
        default: throw BridgeError.message("Unsupported message type")
        }
        saved.seen[e.id] = e.expires
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        saved.seen = saved.seen.filter { $0.value > now - 86_400_000 }
        try persist()
    }
    func reply(item: PhoneItem, action: [String: Any], text: String) {
        do { guard let actionId = action["id"] as? String, !text.isEmpty else { return }; try send("reply", ["requestId": UUID().uuidString.lowercased(), "key": item.id, "actionId": actionId, "text": String(text.prefix(1000))]); status = "Reply queued" }
        catch { status = error.localizedDescription }
    }
    func requestNotifications() async { do { _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) } catch { status = error.localizedDescription } }
    func syncHealth() async {
        guard !healthBusy else { return }; healthBusy = true; defer { healthBusy = false }
        do {
            guard active else { throw BridgeError.message("Pair first") }
            try await health.authorize(); healthStatus = "Reading HealthKit…"
            let end = Date(), start = saved.healthCursor.map { Date(timeIntervalSince1970: $0 - 3600) } ?? end.addingTimeInterval(-Double(backfillDays) * 86400)
            let records = try await health.records(start: start, end: end)
            guard !records.isEmpty else { healthStatus = "No readable samples in this window. Check Health permissions; denied reads may appear empty."; return }
            // Keep individual envelopes below transport limits; source UUIDs deduplicate retries.
            saved.healthPendingIds.removeAll(); saved.healthAcknowledged.removeAll(); saved.healthWindowEnd = end.timeIntervalSince1970
            var count = 0
            for offset in stride(from: 0, to: records.count, by: 75) {
                let id = UUID().uuidString.lowercased(); saved.healthPendingIds.insert(id)
                try send("health", ["batchId": id, "records": Array(records[offset..<min(offset + 75, records.count)])]); count += 1
            }
            try persist(); healthStatus = "Queued \(records.count) records in \(count) batches"
            try await sync()
        } catch { healthStatus = error.localizedDescription }
    }
    func unpair() async {
        do {
            _ = try await request("/v1/revoke", body: [:]); suspend(); bluetooth.stop(); state = LinkState(); saved = StoredData(); active = false; securityCode = ""; items = []; try SecureStore.save(state)
            if FileManager.default.fileExists(atPath: storeURL.path) { try FileManager.default.removeItem(at: storeURL) }; status = "Unpaired"
        } catch { status = error.localizedDescription }
    }
}
