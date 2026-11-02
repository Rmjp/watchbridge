// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "WatchBridgeCryptoChecks", platforms: [.macOS(.v13)], products: [], targets: [
    .target(name: "WatchBridgeCore", path: "WatchBridge", exclude: ["Assets.xcassets", "Bluetooth.swift", "BridgeModel.swift", "HealthSync.swift", "Info.plist", "WatchBridge.entitlements", "WatchBridgeApp.swift"], sources: ["Crypto.swift", "BluetoothProtocol.swift"]),
    .testTarget(name: "WatchBridgeCoreTests", dependencies: ["WatchBridgeCore"], path: "WatchBridgeTests")
])
