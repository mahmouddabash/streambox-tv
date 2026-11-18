import SwiftUI
import UIKit

enum Theme {
    static let bg = Color(hex: 0x070A12)
    static let panel = Color(hex: 0x121828)
    static let accent = Color(hex: 0x7C5CFF)
    static let accent2 = Color(hex: 0x00D4FF)
    static let text = Color(hex: 0xF5F7FF)
    static let dim = Color(hex: 0x8E9AB8)
    static let gold = Color(hex: 0xFFC548)
    static let live = Color(hex: 0xFF3D6E)
    static let glass = Color.white.opacity(0.07)
    static let stroke = Color.white.opacity(0.12)

    static let gradient = LinearGradient(colors: [accent, accent2], startPoint: .leading, endPoint: .trailing)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

/// Midnight background with soft violet and cyan glows.
struct AppBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x0B1020), Color(hex: 0x05070D)], startPoint: .top, endPoint: .bottom)
            RadialGradient(colors: [Theme.accent.opacity(0.28), .clear], center: .topLeading, startRadius: 0, endRadius: 520)
            RadialGradient(colors: [Theme.accent2.opacity(0.16), .clear], center: .bottomTrailing, startRadius: 0, endRadius: 480)
        }
        .ignoresSafeArea()
    }
}

// MARK: - Image loading with an in-memory cache

final class ImageCache {
    static let shared = ImageCache()
    let cache = NSCache<NSString, UIImage>()
    private init() { cache.countLimit = 400 }
}

@MainActor
final class ImageLoader: ObservableObject {
    @Published var image: UIImage?
    private var url = ""
    private var task: Task<Void, Never>?

    func load(_ urlString: String) {
        guard urlString != url else { return }
        url = urlString
        task?.cancel()
        image = nil
        guard !urlString.isEmpty, let u = URL(string: urlString) else { return }
        if let img = ImageCache.shared.cache.object(forKey: urlString as NSString) {
            image = img
            return
        }
        task = Task {
            var req = URLRequest(url: u)
            req.setValue(Net.userAgent, forHTTPHeaderField: "User-Agent")
            guard let result = try? await Net.session.data(for: req) else { return }
            let data = result.0
            guard let img = await Task.detached(operation: { UIImage(data: data)?.preparingForDisplay() ?? UIImage(data: data) }).value,
                  !Task.isCancelled else { return }
            ImageCache.shared.cache.setObject(img, forKey: urlString as NSString)
            self.image = img
        }
    }
}

struct RemoteImage: View {
    let url: String
    var contentMode: ContentMode = .fill
    @StateObject private var loader = ImageLoader()

    var body: some View {
        ZStack {
            if let img = loader.image {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.25), value: loader.image != nil)
        .onAppear { loader.load(url) }
        .onChange(of: url) { loader.load($0) }
    }
}

// MARK: - Reusable pieces

struct PosterCard: View {
    let title: String
    let image: String
    var badge: String = ""
    /// 0...1, or nil for none.
    var progress: Double? = nil
    var width: CGFloat = 120

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    LinearGradient(colors: [Color(hex: 0x1C2438), Color(hex: 0x2A2350)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Text(title)
                        .font(.footnote.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .foregroundColor(Theme.text.opacity(0.85))
                        .padding(8)
                    RemoteImage(url: image)
                }
                .frame(width: width, height: width * 1.5)
                .clipped()

                if !badge.isEmpty {
                    Text(badge)
                        .font(.caption2.weight(.bold))
                        .foregroundColor(Theme.gold)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 6))
                        .padding(6)
                }
                if let p = progress {
                    VStack {
                        Spacer()
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(.white.opacity(0.35))
                                Capsule().fill(Theme.gradient).frame(width: g.size.width * min(max(p, 0.03), 1))
                            }
                        }
                        .frame(height: 4)
                        .padding(8)
                    }
                }
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.stroke, lineWidth: 0.5))
            .shadow(color: .black.opacity(0.4), radius: 8, y: 4)

            Text(title)
                .font(.caption.weight(.medium))
                .foregroundColor(Theme.text.opacity(0.9))
                .lineLimit(1)
                .frame(width: width, alignment: .leading)
        }
    }
}

struct ChannelCard: View {
    let name: String
    let logo: String
    var width: CGFloat = 170

    var body: some View {
        VStack(spacing: 6) {
            ZStack(alignment: .topLeading) {
                LinearGradient(colors: [Color(hex: 0x262E4A), Color(hex: 0x151B2E)], startPoint: .topLeading, endPoint: .bottomTrailing)
                RemoteImage(url: logo, contentMode: .fit)
                    .padding(18)
                Text("LIVE")
                    .font(.system(size: 9, weight: .heavy))
                    .foregroundColor(.white)
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(LinearGradient(colors: [Theme.live, Color(hex: 0xFF7A45)], startPoint: .leading, endPoint: .trailing),
                                in: RoundedRectangle(cornerRadius: 4))
                    .padding(8)
            }
            .frame(width: width, height: width * 0.58)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.stroke, lineWidth: 0.5))

            Text(name)
                .font(.caption.weight(.medium))
                .foregroundColor(Theme.text.opacity(0.9))
                .lineLimit(1)
                .frame(width: width, alignment: .leading)
        }
    }
}

struct Chip: View {
    let title: String
    let selected: Bool

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundColor(selected ? .white : Theme.text.opacity(0.85))
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background {
                if selected { Capsule().fill(Theme.gradient) } else { Capsule().fill(Theme.glass) }
            }
            .overlay(Capsule().stroke(selected ? .clear : Theme.stroke, lineWidth: 0.5))
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(Color(hex: 0x0B1020))
            .padding(.horizontal, 22).padding(.vertical, 12)
            .background(Capsule().fill(.white))
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

struct GlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(Theme.text)
            .padding(.horizontal, 20).padding(.vertical, 12)
            .background(Capsule().fill(.ultraThinMaterial))
            .overlay(Capsule().stroke(Theme.stroke, lineWidth: 0.5))
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

struct SearchField: View {
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundColor(Theme.dim)
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundColor(Theme.text)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundColor(Theme.dim) }
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(Capsule().fill(Theme.glass))
        .overlay(Capsule().stroke(Theme.stroke, lineWidth: 0.5))
    }
}

enum Fmt {
    static func time(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = Prefs.shared.use24h ? "HH:mm" : "h:mm a"
        return f.string(from: d)
    }

    static func day(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "EEE d MMM"
        return f.string(from: d)
    }

    static func date(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        return f.string(from: d)
    }

    static func duration(_ seconds: Double) -> String {
        let s = Int(seconds)
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}
