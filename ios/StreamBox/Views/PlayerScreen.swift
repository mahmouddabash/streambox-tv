import SwiftUI
import AVKit
import Combine

/// Owns the AVPlayer: queue, resume positions, auto-next, live reconnects and format fallbacks.
@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()
    @Published var index: Int
    @Published var error: String?
    @Published var buffering = true
    @Published var nowText = ""
    @Published var nextText = ""
    let items: [PlayItem]
    private let fromStart: Bool
    private weak var catalog: Catalog?
    private var cancellables = Set<AnyCancellable>()
    private var timeObserver: Any?
    private var retries = 0
    private var triedAlt = false
    private var currentURL = ""
    private var didSeek = false
    var onFinished: (() -> Void)?

    var current: PlayItem { items[index] }

    init(request: PlaybackRequest, catalog: Catalog) {
        items = request.items
        index = request.index
        fromStart = request.fromStart
        self.catalog = catalog
        player.allowsExternalPlayback = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.savePosition() }
        }
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] s in self?.buffering = (s == .waitingToPlayAtSpecifiedRate) }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)
            .receive(on: RunLoop.main)
            .sink { [weak self] n in
                guard let self, (n.object as? AVPlayerItem) === self.player.currentItem else { return }
                self.itemEnded()
            }
            .store(in: &cancellables)
        start(resume: !fromStart)
    }

    deinit {
        if let t = timeObserver { player.removeTimeObserver(t) }
    }

    func teardown() {
        savePosition()
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    // MARK: Playback

    func start(resume: Bool) {
        retries = 0
        triedAlt = false
        error = nil
        didSeek = !resume || current.isLive
        load(current.url)
        if current.isLive, let c = catalog?.channel(current.channelId) {
            catalog?.addRecentChannel(c)
            loadEpg(c)
        } else {
            nowText = ""
            nextText = ""
        }
    }

    private func load(_ url: String) {
        currentURL = url
        guard let u = URL(string: url) else { error = "Invalid stream address"; return }
        let asset = AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": Net.userAgent]])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = current.isLive ? 4 : 10
        item.publisher(for: \.status)
            .receive(on: RunLoop.main)
            .sink { [weak self, weak item] status in
                guard let self, let item, item === self.player.currentItem else { return }
                switch status {
                case .readyToPlay: self.ready()
                case .failed: self.failed(item.error)
                default: break
                }
            }
            .store(in: &cancellables)
        player.replaceCurrentItem(with: item)
        player.play()
    }

    private func ready() {
        retries = 0
        error = nil
        guard !didSeek else { return }
        didSeek = true
        let pos = Prefs.shared.position(current.key)
        if pos > 5 { player.seek(to: CMTime(seconds: pos, preferredTimescale: 600)) }
    }

    private func failed(_ err: Error?) {
        // 1) Apple's player can't open MKV/AVI/TS: ask Xtream for the same title as HLS.
        if !triedAlt, let alt = hlsVariant(currentURL) {
            triedAlt = true
            load(alt)
            return
        }
        // 2) Live streams drop now and then: reconnect a few times.
        if current.isLive && retries < 4 {
            retries += 1
            error = nil
            let url = currentURL
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                guard let self, self.currentURL == url else { return }
                self.load(url)
            }
            return
        }
        let ext = (current.url.split(separator: "?").first ?? "").split(separator: ".").last.map(String.init)?.lowercased() ?? ""
        if ["mkv", "avi", "ts", "flv", "wmv"].contains(ext) {
            error = "This title is in .\(ext) format, which iPhone's player can't play. Open it in VLC instead."
        } else {
            error = "Cannot play this stream.\n\(err?.localizedDescription ?? "")"
        }
    }

    /// Xtream URLs can usually be requested as .m3u8 instead of their file extension.
    private func hlsVariant(_ url: String) -> String? {
        guard url.range(of: "/(live|movie|series|timeshift)/", options: .regularExpression) != nil else { return nil }
        let parts = url.split(separator: "?", maxSplits: 1).map(String.init)
        let path = parts[0]
        guard let dot = path.lastIndex(of: "."), path[dot...].lowercased() != ".m3u8",
              path.lastIndex(of: "/").map({ $0 < dot }) == true else { return nil }
        return String(path[..<dot]) + ".m3u8" + (parts.count > 1 ? "?" + parts[1] : "")
    }

    private func itemEnded() {
        if current.isLive {
            load(currentURL) // a live stream that "ends" has dropped
            return
        }
        Prefs.shared.setPosition(current.key, 0)
        catalog?.revision += 1
        if index < items.count - 1 {
            index += 1
            start(resume: false)
        } else {
            onFinished?()
        }
    }

    func savePosition() {
        guard !current.isLive, !current.key.isEmpty, let item = player.currentItem else { return }
        let pos = player.currentTime().seconds
        let dur = item.duration.seconds
        guard pos.isFinite else { return }
        if dur.isFinite && dur > 0 && pos > dur * 0.95 {
            Prefs.shared.setPosition(current.key, 0)
        } else if pos > 10 {
            Prefs.shared.setPosition(current.key, pos, duration: dur.isFinite ? dur : 0)
        }
    }

    func zap(_ delta: Int) {
        guard items.count > 1 else { return }
        savePosition()
        index = (index + delta + items.count) % items.count
        start(resume: false)
    }

    func jump(to i: Int) {
        guard items.indices.contains(i) else { return }
        savePosition()
        index = i
        start(resume: false)
    }

    private func loadEpg(_ c: Channel) {
        nowText = ""
        nextText = ""
        guard let catalog, catalog.hasEpg else { return }
        let id = c.id
        Task {
            let list = await catalog.upcoming(c)
            guard self.current.channelId == id else { return }
            if let p = list.first { nowText = "\(Fmt.time(p.start)) – \(Fmt.time(p.end))  \(p.title)" }
            if list.count > 1 { nextText = "Next  \(Fmt.time(list[1].start))  \(list[1].title)" }
        }
    }

    /// Opens the stream in VLC if it's installed.
    func openInVLC() {
        let raw = current.url
        let enc = raw.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? raw
        if let u = URL(string: "vlc-x-callback://x-callback-url/stream?url=\(enc)"), UIApplication.shared.canOpenURL(u) {
            UIApplication.shared.open(u)
        } else if let store = URL(string: "https://apps.apple.com/app/vlc-media-player/id650377962") {
            UIApplication.shared.open(store)
        }
    }
}

// MARK: - Screen

struct PlayerScreen: View {
    let request: PlaybackRequest
    @EnvironmentObject var catalog: Catalog
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PlayerHost(request: request, catalog: catalog, close: { dismiss() })
    }
}

private struct PlayerHost: View {
    @StateObject private var model: PlayerModel
    let close: () -> Void

    init(request: PlaybackRequest, catalog: Catalog, close: @escaping () -> Void) {
        _model = StateObject(wrappedValue: PlayerModel(request: request, catalog: catalog))
        self.close = close
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.current.isLive {
                LivePlayerView(model: model, close: finish)
            } else {
                SystemPlayer(player: model.player)
                    .ignoresSafeArea()
                VStack {
                    HStack {
                        Spacer()
                        Button(action: finish) {
                            Image(systemName: "xmark").font(.headline.weight(.bold)).foregroundColor(.white)
                                .frame(width: 38, height: 38)
                                .background(Circle().fill(.ultraThinMaterial))
                        }
                    }
                    Spacer()
                }
                .padding()
            }
            if let err = model.error {
                ErrorCard(message: err, vlc: model.openInVLC, close: finish)
            }
        }
        .statusBarHidden()
        .onAppear { model.onFinished = finish }
        .onDisappear { model.teardown() }
    }

    private func finish() {
        model.teardown()
        close()
    }
}

private struct ErrorCard: View {
    let message: String
    let vlc: () -> Void
    let close: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundColor(Theme.gold)
            Text(message).multilineTextAlignment(.center).foregroundColor(Theme.text)
            HStack(spacing: 12) {
                Button(action: vlc) { Label("Open in VLC", systemImage: "arrow.up.forward.app") }.buttonStyle(PrimaryButtonStyle())
                Button("Close", action: close).buttonStyle(GlassButtonStyle())
            }
        }
        .padding(26)
        .frame(maxWidth: 420)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(.ultraThinMaterial))
        .padding()
    }
}

/// Apple's full player UI for movies and episodes: scrubbing, AirPlay, Picture-in-Picture, audio and subtitles.
private struct SystemPlayer: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.entersFullScreenWhenPlaybackBegins = false
        vc.videoGravity = .resizeAspect
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}

// MARK: - Live TV player

private final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

private struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    @Binding var fill: Bool

    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.playerLayer.player = player
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ v: PlayerLayerView, context: Context) {
        v.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }
}

private struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = UIColor(Theme.accent2)
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// Custom live TV UI: swipe or tap arrows to change channel, channel list drawer, now/next info.
private struct LivePlayerView: View {
    @ObservedObject var model: PlayerModel
    let close: () -> Void
    @EnvironmentObject var catalog: Catalog
    @State private var showUI = true
    @State private var fill = false
    @State private var showList = false
    @State private var hideTask: Task<Void, Never>?
    @State private var paused = false

    var body: some View {
        ZStack {
            VideoSurface(player: model.player, fill: $fill)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { toggleUI() }
                .gesture(DragGesture(minimumDistance: 40).onEnded { v in
                    if abs(v.translation.height) > abs(v.translation.width) {
                        model.zap(v.translation.height < 0 ? 1 : -1)
                    } else if v.translation.width < 0 {
                        withAnimation { showList = true }
                    }
                    poke()
                })

            if model.buffering && model.error == nil {
                ProgressView().scaleEffect(1.4).tint(.white)
            }

            if showUI { controls.transition(.opacity) }

            if showList {
                HStack {
                    Spacer()
                    channelDrawer
                        .transition(.move(edge: .trailing))
                }
                .background(Color.black.opacity(0.35).ignoresSafeArea().onTapGesture { withAnimation { showList = false } })
            }
        }
        .onAppear { poke() }
        .onChange(of: model.index) { _ in poke() }
    }

    private var controls: some View {
        VStack(spacing: 0) {
            // Top: channel info
            HStack(alignment: .top, spacing: 12) {
                Button(action: close) {
                    Image(systemName: "chevron.down").font(.headline.weight(.bold)).foregroundColor(.white)
                        .frame(width: 40, height: 40).background(Circle().fill(.ultraThinMaterial))
                }
                RemoteImage(url: model.current.poster, contentMode: .fit)
                    .frame(width: 54, height: 38)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text("LIVE").font(.system(size: 10, weight: .heavy)).foregroundColor(.white)
                            .padding(.horizontal, 5).padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: 4).fill(Theme.live))
                        Text(model.current.title).font(.headline).foregroundColor(.white).lineLimit(1)
                    }
                    if !model.nowText.isEmpty { Text(model.nowText).font(.subheadline).foregroundColor(.white.opacity(0.9)).lineLimit(1) }
                    if !model.nextText.isEmpty { Text(model.nextText).font(.caption).foregroundColor(.white.opacity(0.65)).lineLimit(1) }
                }
                Spacer()
                AirPlayButton().frame(width: 40, height: 40)
            }
            .padding(.horizontal, 18)
            .padding(.top, 14)
            .padding(.bottom, 40)
            .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea())

            Spacer()

            // Center: zapping + play/pause
            HStack(spacing: 44) {
                roundButton("chevron.left", size: 54) { model.zap(-1); poke() }
                roundButton(paused ? "play.fill" : "pause.fill", size: 72) {
                    if paused { model.player.play() } else { model.player.pause() }
                    paused.toggle()
                    poke()
                }
                roundButton("chevron.right", size: 54) { model.zap(1); poke() }
            }
            .opacity(model.items.count > 1 ? 1 : 0.999)

            Spacer()

            // Bottom: tools
            HStack(spacing: 14) {
                Text("\(model.index + 1) / \(model.items.count)").font(.caption.monospacedDigit()).foregroundColor(.white.opacity(0.7))
                Spacer()
                toolButton(fill ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", fill ? "Fit" : "Fill") {
                    fill.toggle(); poke()
                }
                if model.items.count > 1 {
                    toolButton("list.bullet", "Channels") { withAnimation { showList = true } }
                }
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 14)
            .padding(.top, 40)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        }
    }

    private var channelDrawer: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(Array(model.items.enumerated()), id: \.offset) { i, item in
                        Button {
                            model.jump(to: i)
                            withAnimation { showList = false }
                        } label: {
                            HStack(spacing: 10) {
                                RemoteImage(url: item.poster, contentMode: .fit).frame(width: 46, height: 30)
                                Text(item.title).font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                                Spacer()
                                if i == model.index { Image(systemName: "waveform").foregroundColor(Theme.accent2) }
                            }
                            .padding(10)
                            .background(RoundedRectangle(cornerRadius: 12).fill(i == model.index ? Theme.accent.opacity(0.35) : Color.white.opacity(0.06)))
                        }
                        .id(i)
                    }
                }
                .padding(12)
            }
            .frame(width: 320)
            .background(.ultraThinMaterial)
            .onAppear { proxy.scrollTo(model.index, anchor: .center) }
        }
        .ignoresSafeArea(edges: .vertical)
    }

    private func roundButton(_ icon: String, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size * 0.38, weight: .bold))
                .foregroundColor(.white)
                .frame(width: size, height: size)
                .background(Circle().fill(.ultraThinMaterial))
        }
    }

    private func toolButton(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(label, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Capsule().fill(.ultraThinMaterial))
        }
    }

    private func toggleUI() {
        withAnimation(.easeOut(duration: 0.2)) { showUI.toggle() }
        if showUI { poke() }
    }

    /// Shows the controls and hides them again after a few seconds of no interaction.
    private func poke() {
        withAnimation(.easeOut(duration: 0.2)) { showUI = true }
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_500_000_000)
            if Task.isCancelled || paused { return }
            withAnimation(.easeOut(duration: 0.3)) { showUI = false }
        }
    }
}
