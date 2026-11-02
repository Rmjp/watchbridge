import XCTest
import Foundation
@testable import WatchBridgeCore

final class BluetoothProtocolTests: XCTestCase {
    func testStaleProofCannotAuthenticateAReconnect() {
        XCTAssertTrue(BluetoothProof.matches(own: "new-watch", peer: "new-phone", echo: "new-watch", challenge: "new-phone"))
        XCTAssertFalse(BluetoothProof.matches(own: "new-watch", peer: "new-phone", echo: "old-watch", challenge: "old-phone"))
        XCTAssertFalse(BluetoothProof.matches(own: "", peer: "", echo: "", challenge: ""))
    }
    func testFragmentedAndCoalescedFrames() throws {
        var frames = BluetoothFrames()
        XCTAssertTrue(try frames.feed(Data("{\"a\":".utf8)).isEmpty)
        XCTAssertEqual(try frames.feed(Data("1}\n{\"b\":2}\n".utf8)).map { String(decoding: $0, as: UTF8.self) }, ["{\"a\":1}", "{\"b\":2}"])
    }
    func testReconnectAndOversize() throws {
        var frames = BluetoothFrames(limit: 3)
        _ = try frames.feed(Data("old".utf8)); frames.reset()
        XCTAssertEqual(try frames.feed(Data("ok\n".utf8)), [Data("ok".utf8)])
        XCTAssertThrowsError(try frames.feed(Data("xxxx".utf8)))
        XCTAssertEqual(try frames.feed(Data("ok\n".utf8)), [Data("ok".utf8)])
    }
    func testAckCannotReleaseAnotherMessageAndReconnectRetries() {
        var window = DeliveryWindow()
        XCTAssertTrue(window.begin(id: "a", bytes: 100, now: 0))
        XCTAssertFalse(window.begin(id: "b", bytes: 100, now: 0))
        XCTAssertFalse(window.acknowledge("wrong"))
        XCTAssertTrue(window.timedOut(now: 32))
        window.reset()
        XCTAssertTrue(window.begin(id: "a", bytes: 100, now: 32))
        XCTAssertTrue(window.acknowledge("a"))
        XCTAssertNil(window.id)
    }
}
