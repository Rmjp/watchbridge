import XCTest
import Foundation
import CryptoKit
@testable import WatchBridgeCore

final class CryptoTests: XCTestCase {
    func testNodeAndAndroidInteroperability() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let raw = try Data(contentsOf: root.appendingPathComponent("test-vectors/crypto-v1.json"))
        let v = try XCTUnwrap(JSONSerialization.jsonObject(with: raw) as? [String: Any])
        let privateKey = try XCTUnwrap(Data(base64Encoded: v["watchPrivateRaw"] as! String))
        XCTAssertEqual(privateKey.count, 32, "P-256 raw private keys must preserve leading zeroes")
        let (key, code) = try BridgeCrypto.derive(privateKey: privateKey, androidPublic: v["androidPublic"] as! String, watchPublic: v["watchPublic"] as! String)
        XCTAssertEqual(key.base64EncodedString(), v["key"] as? String); XCTAssertEqual(code, v["securityCode"] as? String)
        var state = LinkState(); state.key = key
        let envelope = try JSONDecoder().decode(Envelope.self, from: JSONSerialization.data(withJSONObject: v["envelope"]!))
        XCTAssertEqual(String(data: envelope.aad, encoding: .utf8), v["aad"] as? String)
        let cipher = Data(base64Encoded: envelope.ciphertext)!
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Data(base64Encoded: envelope.nonce)!), ciphertext: cipher.dropLast(16), tag: cipher.suffix(16))
        let plain = try AES.GCM.open(box, using: BridgeCrypto.directional(state, sender: envelope.sender), authenticating: envelope.aad)
        XCTAssertEqual(String(data: plain, encoding: .utf8), "{\"text\":\"hello\"}")
        XCTAssertThrowsError(try AES.GCM.open(box, using: BridgeCrypto.directional(state, sender: envelope.sender), authenticating: Data("tampered".utf8)))
    }
}
