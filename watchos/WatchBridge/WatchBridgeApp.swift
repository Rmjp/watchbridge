import SwiftUI

@MainActor @main struct WatchBridgeApp: App {
    @StateObject private var model = BridgeModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(model)
                .onChange(of: phase) { _, newValue in
                    if newValue == .active { model.start() }
                    else if newValue == .background { model.suspend() }
                }
        }
    }
}

@MainActor struct ContentView: View {
    @EnvironmentObject var model: BridgeModel
    @State private var showForget = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("WatchBridge", systemImage: "link").font(.headline).foregroundStyle(.mint)
                    Text("Bluetooth only · 0.2").font(.caption2)
                    Text(model.status).font(.caption)
                    Text("Pending: \(model.pendingCount) · Last: \(model.lastTransfer)").font(.caption2)
                }
                if !model.active {
                    Section("Pair with Galaxy") {
                        Text("Start new Bluetooth pairing on the phone, then search here. No Wi-Fi, account or server is required.").font(.caption2)
                        Button(model.securityCode.isEmpty ? "Find Galaxy" : "Start over and find Galaxy") { model.findGalaxy() }
                        if !model.securityCode.isEmpty {
                            Text(model.securityCode).font(.title2.monospacedDigit()).foregroundStyle(.mint)
                            Text("Compare this security code with the phone. Confirm only if both match.").font(.caption2)
                            Button("Codes match — confirm") { model.approve() }
                        }
                    }
                } else {
                    Section("Phone inbox") {
                        if model.items.isEmpty { Text("No forwarded notifications yet").font(.caption) }
                        ForEach(model.items) { item in
                            NavigationLink { NotificationDetail(item: item) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.app).font(.caption2).foregroundStyle(.mint)
                                    Text(item.title).font(.headline).lineLimit(2)
                                    Text(item.body).font(.caption).lineLimit(2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    Section("Health → Galaxy") {
                        Text(model.healthStatus).font(.caption)
                        Picker("First backfill", selection: $model.backfillDays) { Text("1 day").tag(1); Text("7 days").tag(7) }
                        Button(model.healthBusy ? "Reading health…" : "Sync health now") { Task { await model.syncHealth() } }.disabled(model.healthBusy)
                        Text("First sync reads the selected window, then later syncs are incremental. Reads depend on permission and what is stored on this watch. Sleep stages are imported separately in this prototype.").font(.caption2)
                    }
                    Section("Connection") {
                        Text(model.connected ? "Connected and authenticated" : "Out of range · messages stay queued").font(.caption)
                        Button("Reconnect Bluetooth") { model.findGalaxy() }
                        Button("Allow watch alerts") { Task { await model.requestNotifications() } }
                        Button("Send test to phone") { model.ping() }
                        Text("Keep the apps nearby and open for the first test. watchOS controls background Bluetooth runtime; screen-off delivery still needs testing.").font(.caption2)
                        Button("Forget pairing", role: .destructive) { showForget = true }
                    }
                }
            }.navigationTitle("WatchBridge")
                .confirmationDialog("Forget local keys and pending transfers? Forget pairing on the phone too.", isPresented: $showForget) {
                    Button("Forget pairing", role: .destructive) { model.unpair() }
                    Button("Cancel", role: .cancel) { }
                }
        }
    }
}

@MainActor struct NotificationDetail: View {
    @EnvironmentObject var model: BridgeModel
    let item: PhoneItem
    @State private var reply = ""
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(item.app).font(.caption).foregroundStyle(.mint)
                Text(item.title).font(.headline)
                Text(item.body)
                if let action = item.actions.first {
                    TextField("Reply…", text: $reply)
                    Button("Send reply") { model.reply(item: item, action: action, text: reply); reply = "" }.disabled(reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button("OK") { model.reply(item: item, action: action, text: "OK") }
                    Button("On my way") { model.reply(item: item, action: action, text: "On my way") }
                }
                Text(model.status).font(.caption2).foregroundStyle(.secondary)
            }.padding(.horizontal, 4)
        }
    }
}
