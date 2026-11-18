import SwiftUI

struct SettingsView: View {
    let profile: Profile
    let onSwitchProfile: () -> Void
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @State private var resumeOnLaunch = Prefs.shared.resumeOnLaunch
    @State private var use24h = Prefs.shared.use24h
    @State private var cacheHours = Prefs.shared.cacheHours
    @State private var refreshing = false
    @State private var message = ""
    @State private var pinSheet = false
    @State private var hasPin = Prefs.shared.parentalEnabled

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 14) {
                        ZStack {
                            Circle().fill(LinearGradient(colors: [Theme.accent, Theme.accent2], startPoint: .topLeading, endPoint: .bottomTrailing))
                            Text(String(profile.name.prefix(1)).uppercased()).font(.title2.weight(.heavy)).foregroundColor(.white)
                        }
                        .frame(width: 52, height: 52)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(profile.name).font(.headline)
                            Text(accountLine).font(.caption).foregroundColor(Theme.dim)
                        }
                    }
                    .padding(.vertical, 4)
                    Button { onSwitchProfile() } label: { Label("Switch profile", systemImage: "person.2.fill") }
                }

                Section("Content") {
                    Button {
                        Task {
                            refreshing = true
                            do { try await catalog.load(profile, force: true); message = "Content refreshed" }
                            catch { message = "Refresh failed: \(Net.readable(error))" }
                            refreshing = false
                        }
                    } label: {
                        HStack {
                            Label("Refresh channels, movies & series", systemImage: "arrow.clockwise")
                            Spacer()
                            if refreshing { ProgressView() }
                        }
                    }
                    .disabled(refreshing)
                    Picker("Refresh automatically", selection: $cacheHours) {
                        ForEach([1, 6, 12, 24, 72], id: \.self) { Text("Every \($0) hours").tag($0) }
                    }
                    .onChange(of: cacheHours) { Prefs.shared.cacheHours = $0 }
                    if !message.isEmpty { Text(message).font(.footnote).foregroundColor(Theme.dim) }
                }

                Section("Startup & display") {
                    Toggle("Resume where I left off", isOn: $resumeOnLaunch)
                        .onChange(of: resumeOnLaunch) { Prefs.shared.resumeOnLaunch = $0 }
                    Toggle("24-hour clock", isOn: $use24h)
                        .onChange(of: use24h) { Prefs.shared.use24h = $0 }
                }

                Section {
                    if hasPin {
                        Button("Change PIN") { app.unlock(true) { pinSheet = true } }
                        Button("Turn off parental control", role: .destructive) {
                            app.unlock(true) {
                                Prefs.shared.pin = ""
                                hasPin = false
                                catalog.revision += 1
                            }
                        }
                    } else {
                        Button("Set a PIN") { pinSheet = true }
                    }
                } header: {
                    Text("Parental control")
                } footer: {
                    Text("With a PIN, adult categories lock automatically. Long-press any category to lock or unlock it.")
                }

                Section("Playback data") {
                    Button("Clear resume positions", role: .destructive) {
                        Prefs.shared.clearResumePositions()
                        catalog.revision += 1
                        message = "Resume positions cleared"
                    }
                    Button("Clear cached lists") {
                        catalog.clearCache()
                        message = "Cache cleared. Lists download again on next refresh."
                    }
                }

                Section {
                    HStack {
                        AppLogo(size: 30)
                        VStack(alignment: .leading) {
                            Text("StreamBox for iPhone & iPad").font(.subheadline.weight(.semibold))
                            Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")  •  Player only, contains no channels")
                                .font(.caption).foregroundColor(Theme.dim)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppBackground())
            .navigationTitle("Settings")
            .sheet(isPresented: $pinSheet) {
                PinSetupView { pin in
                    Prefs.shared.pin = pin
                    hasPin = true
                    catalog.revision += 1
                }
            }
        }
    }

    private var accountLine: String {
        let a = catalog.account
        var parts: [String] = []
        if !a.status.isEmpty { parts.append(a.status) }
        if let e = a.expiry { parts.append("Expires \(Fmt.date(e))") }
        if !a.maxConnections.isEmpty { parts.append("Connections \(a.activeConnections.isEmpty ? "0" : a.activeConnections)/\(a.maxConnections)") }
        return parts.joined(separator: "  •  ")
    }
}

private struct PinSetupView: View {
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var confirm = ""

    var body: some View {
        NavigationStack {
            Form {
                SecureField("New PIN (4+ digits)", text: $pin).keyboardType(.numberPad)
                SecureField("Repeat PIN", text: $confirm).keyboardType(.numberPad)
            }
            .scrollContentBackground(.hidden)
            .background(AppBackground())
            .navigationTitle("Parental PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(pin); dismiss() }
                        .disabled(pin.count < 4 || pin != confirm)
                }
            }
        }
    }
}
