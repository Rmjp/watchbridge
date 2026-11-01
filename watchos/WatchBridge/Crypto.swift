import Foundation
import CryptoKit
import Security

enum BridgeError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let s) = self { return s }; return nil }
}

struct LinkState: Codable {
    var relay = ""
    var id = UUID().uuidString.lowercased()
    var privateKey = Data()
    var publicKey = ""
    var token = ""
    var peerId = ""
    var peerPublic = ""
    var key = Data()
    var confirmed = false
    var active = false
}

enum SecureStore {
    static func save(_ state: LinkState) throws {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.watchbridge.link", kSecAttrAccount as String: "current"]
        let data = try JSONEncoder().encode(state)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = q
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw BridgeError.message("Cannot save device keys") }
        } else if status != errSecSuccess { throw BridgeError.message("Cannot update device keys") }
    }
    static func load() throws -> LinkState {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.watchbridge.link", kSecAttrAccount as String: "current", kSecReturnData as String: true]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return LinkState() }
        guard status == errSecSuccess, let data = result as? Data else { throw BridgeError.message("Unlock watch to access device keys") }
        return try JSONDecoder().decode(LinkState.self, from: data)
    }
}

struct Envelope: Codable {
    let v: Int
    let id: String
    let sender: String
    let recipient: String
    let type: String
    let created: Int64
    let expires: Int64
    let nonce: String
    let ciphertext: String
    var aad: Data { Data("watchbridge.v1|\(id)|\(sender)|\(recipient)|\(type)|\(created)|\(expires)".utf8) }
}

enum BridgeCrypto {
    static func derive(privateKey: Data, androidPublic: String, watchPublic: String) throws -> (Data, String) {
        guard let raw = Data(base64Encoded: androidPublic) else { throw BridgeError.message("Bad peer public key") }
        let key = try P256.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let peer = try P256.KeyAgreement.PublicKey(x963Representation: raw)
        let shared = try key.sharedSecretFromKeyAgreement(with: peer)
        let salt = Data(SHA256.hash(data: Data("\(androidPublic)|\(watchPublic)".utf8)))
        let result = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: Data("watchbridge.v1".utf8), outputByteCount: 32)
        let bytes = result.withUnsafeBytes { Data($0) }
        let hash = SHA256.hash(data: bytes + salt)
        let n = hash.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return (bytes, String(format: "%06u", n % 1_000_000))
    }
    static func directional(_ state: LinkState, sender: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: state.key), salt: Data(), info: Data("watchbridge.v1.sender:\(sender)".utf8), outputByteCount: 32)
    }
    static func encrypt(_ state: LinkState, type: String, payload: [String: Any]) throws -> Envelope {
        guard state.confirmed && state.active && state.key.count == 32 else { throw BridgeError.message("Confirm pairing on both devices first") }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let nonce = AES.GCM.Nonce()
        let shell = Envelope(v: 1, id: UUID().uuidString.lowercased(), sender: state.id, recipient: state.peerId, type: type, created: now, expires: now + (type == "health" ? 604_800_000 : 86_400_000), nonce: Data(nonce).base64EncodedString(), ciphertext: "")
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let sealed = try AES.GCM.seal(data, using: directional(state, sender: state.id), nonce: nonce, authenticating: shell.aad)
        return Envelope(v: shell.v, id: shell.id, sender: shell.sender, recipient: shell.recipient, type: shell.type, created: shell.created, expires: shell.expires, nonce: shell.nonce, ciphertext: (sealed.ciphertext + sealed.tag).base64EncodedString())
    }
    static func decrypt(_ state: LinkState, envelope e: Envelope) throws -> [String: Any] {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard state.active && state.confirmed, e.v == 1, e.sender == state.peerId, e.recipient == state.id, e.expires > now, e.created <= now + 300_000,
              let nonce = Data(base64Encoded: e.nonce), nonce.count == 12, let cipher = Data(base64Encoded: e.ciphertext), cipher.count >= 16 else { throw BridgeError.message("Invalid or expired envelope") }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: cipher.dropLast(16), tag: cipher.suffix(16))
        let plain = try AES.GCM.open(box, using: directional(state, sender: e.sender), authenticating: e.aad)
        guard let payload = try JSONSerialization.jsonObject(with: plain) as? [String: Any] else { throw BridgeError.message("Invalid message") }
        return payload
    }
}
