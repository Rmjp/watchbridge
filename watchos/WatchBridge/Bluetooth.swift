import Foundation
@preconcurrency import CoreBluetooth

final class WatchBluetooth: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "7d7a0001-6f4b-4ef0-9a9d-a98551c2a601")
    static let incoming = CBUUID(string: "7d7a0002-6f4b-4ef0-9a9d-a98551c2a601")
    static let outgoing = CBUUID(string: "7d7a0003-6f4b-4ef0-9a9d-a98551c2a601")
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var write: CBCharacteristic?
    private var buffer = Data()
    private var queue = [Data]()
    private var writing = false
    private var enabled = false
    var onFrame: ((Data) -> Void)?
    var onStatus: ((String) -> Void)?
    override init() { super.init(); central = CBCentralManager(delegate: self, queue: .main) }
    func start() { enabled = true; if central.state == .poweredOn { central.scanForPeripherals(withServices: [Self.service]) } }
    func stop() { enabled = false; central.stopScan(); if let p = peripheral { central.cancelPeripheralConnection(p) }; peripheral = nil; write = nil; queue.removeAll(); buffer.removeAll(); writing = false }
    func centralManagerDidUpdateState(_ central: CBCentralManager) { if enabled && central.state == .poweredOn { start() } }
    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard peripheral == nil else { return }; peripheral = p; p.delegate = self; central.stopScan(); central.connect(p)
    }
    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) { p.discoverServices([Self.service]); onStatus?("Bluetooth connected") }
    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { peripheral = nil; if enabled { start() } }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) { peripheral = nil; write = nil; buffer.removeAll(); queue.removeAll(); writing = false; onStatus?("Bluetooth disconnected"); if enabled { start() } }
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) { for service in p.services ?? [] { p.discoverCharacteristics([Self.incoming, Self.outgoing], for: service) } }
    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) { for c in service.characteristics ?? [] { if c.uuid == Self.incoming { p.setNotifyValue(true, for: c) }; if c.uuid == Self.outgoing { write = c; next() } } }
    func peripheral(_ p: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.incoming, error == nil, let data = characteristic.value else { return }
        for byte in data {
            if byte == 10 { let frame = buffer; buffer.removeAll(); if !frame.isEmpty { onFrame?(frame) } }
            else if buffer.count < 350_000 { buffer.append(byte) }
            else { buffer.removeAll(); central.cancelPeripheralConnection(p); return }
        }
    }
    func send(_ data: Data) {
        guard let p = peripheral, write != nil, queue.count < 20_000 else { return }
        let framed = data + Data([10]); guard framed.count <= 350_000 else { return }
        let size = max(1, min(p.maximumWriteValueLength(for: .withResponse), 180))
        for start in stride(from: 0, to: framed.count, by: size) { queue.append(framed.subdata(in: start..<min(start + size, framed.count))) }
        next()
    }
    private func next() { guard !writing, !queue.isEmpty, let p = peripheral, let c = write else { return }; writing = true; p.writeValue(queue.removeFirst(), for: c, type: .withResponse) }
    func peripheral(_ p: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) { writing = false; if error != nil { queue.removeAll() } else { next() } }
}
