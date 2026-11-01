// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "WatchBridgeCryptoChecks", platforms: [.macOS(.v13)], products: [], targets: [
    .target(name: "WatchBridgeCore", path: "WatchBridge", sources: ["Crypto.swift"]),
    .testTarget(name: "WatchBridgeCoreTests", dependencies: ["WatchBridgeCore"], path: "WatchBridgeTests")
])
