import Foundation

struct BluetoothFrames {
    private var partial = Data()
    let limit: Int
    init(limit: Int = 350_000) { self.limit = limit }
    mutating func reset() { partial.removeAll() }
    mutating func feed(_ data: Data) throws -> [Data] {
        var frames = [Data]()
        for byte in data {
            if byte == 10 { if !partial.isEmpty { frames.append(partial) }; partial.removeAll() }
            else { guard partial.count < limit else { partial.removeAll(); throw BridgeError.message("Bluetooth frame exceeds limit") }; partial.append(byte) }
        }
        return frames
    }
}

struct DeliveryWindow {
    private(set) var id: String?
    private var deadline: TimeInterval = 0
    mutating func begin(id: String, bytes: Int, now: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        guard self.id == nil else { return false }
        self.id = id; deadline = now + 30 + Double(min(bytes, 350_000)) / 100; return true
    }
    mutating func acknowledge(_ id: String) -> Bool { guard self.id == id else { return false }; reset(); return true }
    func timedOut(now: TimeInterval = Date().timeIntervalSince1970) -> Bool { id != nil && now > deadline }
    mutating func reset() { id = nil; deadline = 0 }
}

enum BluetoothProof {
    static func matches(own: String, peer: String, echo: String, challenge: String) -> Bool { !own.isEmpty && !peer.isEmpty && own == echo && peer == challenge }
}
