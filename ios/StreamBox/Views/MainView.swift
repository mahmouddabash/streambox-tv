import SwiftUI

enum Route: Hashable {
    case movie(String)
    case series(String)
}

struct MainView: View {
    let profile: Profile
    let onSwitchProfile: () -> Void
    @EnvironmentObject var catalog: Catalog
    @State private var tab = Prefs.shared.lastTab
    @State private var loadError = ""

    var body: some View {
        Group {
            if catalog.loaded && catalog.profileId == profile.id {
                TabView(selection: $tab) {
                    HomeView(selectTab: { tab = $0 })
                        .tabItem { Label("Home", systemImage: "house.fill") }.tag("home")
                    LiveView()
                        .tabItem { Label("Live TV", systemImage: "dot.radiowaves.left.and.right") }.tag("live")
                    BrowseView(section: .vod)
                        .tabItem { Label("Movies", systemImage: "film.fill") }.tag("vod")
                    BrowseView(section: .series)
                        .tabItem { Label("Series", systemImage: "play.rectangle.on.rectangle.fill") }.tag("series")
                    SettingsView(profile: profile, onSwitchProfile: onSwitchProfile)
                        .tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag("settings")
                }
                .onChange(of: tab) { Prefs.shared.lastTab = $0 }
            } else {
                LoadingView(profile: profile, error: loadError, retry: { Task { await load(force: true) } }, back: onSwitchProfile)
            }
        }
        .task { await load(force: false) }
    }

    private func load(force: Bool) async {
        if catalog.loaded && catalog.profileId == profile.id && !force { return }
        loadError = ""
        do {
            try await catalog.load(profile, force: force)
        } catch {
            loadError = Net.readable(error)
        }
    }
}

private struct LoadingView: View {
    let profile: Profile
    let error: String
    let retry: () -> Void
    let back: () -> Void
    @EnvironmentObject var catalog: Catalog
    @State private var pulse = false

    var body: some View {
        ZStack {
            AppBackground()
            VStack(spacing: 20) {
                AppLogo(size: 84)
                    .scaleEffect(pulse ? 1.06 : 0.94)
                    .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: pulse)
                Text(profile.name).font(.title.weight(.heavy)).foregroundColor(Theme.text)
                if error.isEmpty {
                    ProgressView().tint(Theme.accent2)
                    Text(catalog.status.isEmpty ? "Connecting…" : catalog.status).foregroundColor(Theme.dim)
                } else {
                    Text(error).multilineTextAlignment(.center).foregroundColor(Theme.gold).padding(.horizontal, 30)
                    HStack(spacing: 12) {
                        Button("Try again", action: retry).buttonStyle(PrimaryButtonStyle())
                        Button("Profiles", action: back).buttonStyle(GlassButtonStyle())
                    }
                }
            }
        }
        .onAppear { pulse = true }
    }
}
