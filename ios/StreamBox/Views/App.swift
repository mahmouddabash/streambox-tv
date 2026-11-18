import SwiftUI
import AVFoundation

@main
struct StreamBoxApp: App {
    @StateObject private var catalog = Catalog()
    @StateObject private var app = AppState()

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        let tab = UITabBarAppearance()
        tab.configureWithTransparentBackground()
        tab.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterialDark)
        tab.backgroundColor = UIColor(red: 0.03, green: 0.04, blue: 0.07, alpha: 0.6)
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        nav.largeTitleTextAttributes = [.foregroundColor: UIColor.white]
        nav.titleTextAttributes = [.foregroundColor: UIColor.white]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(catalog)
                .environmentObject(app)
                .preferredColorScheme(.dark)
                .tint(Theme.accent2)
        }
    }
}

/// App-wide UI state: what is playing and the parental PIN prompt.
@MainActor
final class AppState: ObservableObject {
    @Published var playback: PlaybackRequest?
    @Published var pinPromptVisible = false
    @Published var pinText = ""
    private var pinAction: (() -> Void)?

    func play(_ items: [PlayItem], _ index: Int, fromStart: Bool = false) {
        guard items.indices.contains(index) else { return }
        playback = PlaybackRequest(items: items, index: index, fromStart: fromStart)
    }

    /// Runs `action` right away, or after the parental PIN when `locked`.
    func unlock(_ locked: Bool, _ action: @escaping () -> Void) {
        guard locked else { action(); return }
        pinAction = action
        pinText = ""
        pinPromptVisible = true
    }

    func submitPin() -> Bool {
        if pinText == Prefs.shared.pin {
            pinAction?()
            pinAction = nil
            return true
        }
        return false
    }
}

struct RootView: View {
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @State private var showSplash = true
    @State private var active: Profile?
    @State private var wrongPin = false

    var body: some View {
        ZStack {
            AppBackground()
            if let p = active {
                MainView(profile: p, onSwitchProfile: { withAnimation { active = nil } })
                .id(p.id)
                .transition(.opacity)
            } else if !showSplash {
                ProfilesView { p in withAnimation { active = p } }
                    .transition(.opacity)
            }
            if showSplash {
                SplashView()
                    .transition(.opacity)
            }
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                let prefs = Prefs.shared
                if prefs.resumeOnLaunch, let last = prefs.profiles.first(where: { $0.id == prefs.lastProfileId }) {
                    active = last
                }
                withAnimation(.easeOut(duration: 0.4)) { showSplash = false }
            }
        }
        .fullScreenCover(item: $app.playback) { req in
            PlayerScreen(request: req)
                .environmentObject(catalog)
                .environmentObject(app)
        }
        .alert("Enter PIN", isPresented: $app.pinPromptVisible) {
            SecureField("PIN", text: $app.pinText).keyboardType(.numberPad)
            Button("Unlock") { if !app.submitPin() { wrongPin = true } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This category is protected by parental control.")
        }
        .alert("Wrong PIN", isPresented: $wrongPin) { Button("OK", role: .cancel) {} }
    }
}

struct SplashView: View {
    @State private var logoIn = false
    @State private var textIn = false
    @State private var sweep = false

    var body: some View {
        ZStack {
            AppBackground()
            VStack(spacing: 18) {
                AppLogo(size: 110)
                    .scaleEffect(logoIn ? 1 : 0.6)
                    .opacity(logoIn ? 1 : 0)
                VStack(spacing: 4) {
                    Text("StreamBox")
                        .font(.system(size: 42, weight: .black))
                        .foregroundColor(Theme.text)
                    Text("T V")
                        .font(.caption.weight(.bold))
                        .tracking(6)
                        .foregroundColor(Theme.accent2)
                }
                .offset(y: textIn ? 0 : 20)
                .opacity(textIn ? 1 : 0)
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.12)).frame(width: 160, height: 3)
                    Capsule().fill(Theme.gradient).frame(width: 56, height: 3)
                        .offset(x: sweep ? 104 : 0)
                }
                .opacity(textIn ? 1 : 0)
                .padding(.top, 18)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.6)) { logoIn = true }
            withAnimation(.easeOut(duration: 0.5).delay(0.25)) { textIn = true }
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true).delay(0.4)) { sweep = true }
        }
    }
}

struct AppLogo: View {
    var size: CGFloat = 40

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
                .fill(LinearGradient(colors: [Theme.accent, Theme.accent2], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: size, height: size * 0.78)
                .shadow(color: Theme.accent.opacity(0.6), radius: size * 0.25)
            Image(systemName: "play.fill")
                .font(.system(size: size * 0.34, weight: .bold))
                .foregroundColor(.white)
                .offset(x: size * 0.03)
        }
        .frame(width: size, height: size)
    }
}
