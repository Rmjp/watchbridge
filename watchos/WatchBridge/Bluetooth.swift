import Foundation
@preconcurrency import CoreBluetooth

final class WatchBluetooth: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "7d7a1001-6f4b-4ef0-9a9d-a98551c2a601")
    static let incoming = CBUUID(string: "7d7a1002-6f4b-4ef0-9a9d-a98551c2a601")
    static let outgoing = CBUUID(string: "7d7a1003-6f4b-4ef0-9a9d-a98551c2a601")
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var write: CBCharacteristic?
    private var notifying = false
    private var ready = false
    private var buffer = BluetoothFrames()
    private var queue = [Data]()
    private var writing = false
    private var enabled = false
    private var rejected = [UUID: Date]()
    var onFrame: ((Data) -> Void)?
    var onStatus: ((String) -> Void)?
    var onLink: ((Bool) -> Void)?
    override init() { super.init(); central = CBCentralManager(delegate: self, queue: .main) }
    func start() {
        enabled = true
        if central.state == .poweredOn && peripheral == nil { central.scanForPeripherals(withServices: [Self.service]); onStatus?("Looking for nearby Galaxy…") }
        else if central.state != .poweredOn { onStatus?("Enable Bluetooth on this watch") }
    }
    private func clear() { write = nil; notifying = false; ready = false; queue.removeAll(); buffer.reset(); writing = false }
    func stop() { enabled = false; central.stopScan(); if let p = peripheral { central.cancelPeripheralConnection(p) }; peripheral = nil; clear(); onLink?(false) }
    func reconnect(reject: Bool = false) {
        if let p = peripheral { if reject { rejected[p.identifier] = Date().addingTimeInterval(10) }; central.cancelPeripheralConnection(p) }
        else if enabled { start() }
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) { if enabled && central.state == .poweredOn { start() } else if central.state != .poweredOn { peripheral = nil; clear(); onLink?(false); onStatus?("Bluetooth unavailable") } }
    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard peripheral == nil, rejected[p.identifier, default: .distantPast] < Date() else { return }
        peripheral = p; p.delegate = self; central.stopScan(); central.connect(p)
    }
    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) { clear(); p.discoverServices([Self.service]); onStatus?("Phone found · preparing Bluetooth") }
    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { peripheral = nil; clear(); onLink?(false); if enabled { start() } }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) { peripheral = nil; clear(); onLink?(false); onStatus?("Disconnected · waiting for nearby phone"); if enabled { start() } }
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) { if error != nil { reconnect(); return }; for service in p.services ?? [] { p.discoverCharacteristics([Self.incoming, Self.outgoing], for: service) } }
    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if error != nil { reconnect(); return }
        for c in service.characteristics ?? [] { if c.uuid == Self.incoming { p.setNotifyValue(true, for: c) }; if c.uuid == Self.outgoing { write = c } }
        checkReady()
    }
    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.isNotifying else { onStatus?("Could not subscribe to phone"); reconnect(); return }
        notifying = true; checkReady()
    }
    private func checkReady() { if write != nil && notifying && !ready { ready = true; onLink?(true); next() } }
    func peripheral(_ p: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.incoming, error == nil, let data = characteristic.value else { return }
        do { for frame in try buffer.feed(data) { onFrame?(frame) } } catch { onStatus?(error.localizedDescription); reconnect(reject: true) }
    }
    @discardableResult func send(_ data: Data) -> Bool {
        guard ready, let p = peripheral else { return false }
        let framed = data + Data([10]); guard framed.count <= 350_000 else { return false }
        let size = max(1, min(p.maximumWriteValueLength(for: .withResponse), 180))
        guard queue.count + (framed.count + size - 1) / size <= 20_000 else { return false }
        for start in stride(from: 0, to: framed.count, by: size) { queue.append(framed.subdata(in: start..<min(start + size, framed.count))) }
        next(); return true
    }
    private func next() { guard ready, !writing, !queue.isEmpty, let p = peripheral, let c = write else { return }; writing = true; p.writeValue(queue.removeFirst(), for: c, type: .withResponse) }
    func peripheral(_ p: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) { writing = false; if error != nil { reconnect() } else { next() } }
}
