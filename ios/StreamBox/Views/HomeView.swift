import SwiftUI

/// Streaming-service style home: auto-rotating hero of new titles, then content rows.
struct HomeView: View {
    let selectTab: (String) -> Void
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var path = NavigationPath()
    @State private var heroIndex = 0
    private let timer = Timer.publish(every: 7, on: .main, in: .common).autoconnect()

    private var heroMovies: [Movie] {
        Array(catalog.recentlyAddedMovies(12).filter { !$0.poster.isEmpty }.prefix(6))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    hero
                    rows
                }
                .padding(.bottom, 30)
            }
            .ignoresSafeArea(edges: .top)
            .background(AppBackground())
            .navigationDestination(for: Route.self) { r in
                switch r {
                case .movie(let id): MovieDetailView(movieId: id)
                case .series(let id): SeriesDetailView(seriesId: id)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    // MARK: Hero

    @ViewBuilder private var hero: some View {
        let list = heroMovies
        let height: CGFloat = hSize == .regular ? 560 : 520
        if list.isEmpty {
            WelcomeHero(selectTab: selectTab).frame(height: 360)
        } else {
            ZStack(alignment: .bottom) {
                TabView(selection: $heroIndex) {
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, m in
                        HeroPage(movie: m, path: $path).tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(height: height)

                HStack(spacing: 6) {
                    ForEach(0..<list.count, id: \.self) { i in
                        Capsule()
                            .fill(i == heroIndex ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.white.opacity(0.3)))
                            .frame(width: i == heroIndex ? 22 : 7, height: 7)
                    }
                }
                .animation(.easeOut, value: heroIndex)
                .padding(.bottom, 8)
            }
            .overlay(alignment: .top) { TopBar(selectTab: selectTab) }
            .onReceive(timer) { _ in
                withAnimation(.easeInOut(duration: 0.6)) { heroIndex = (heroIndex + 1) % max(list.count, 1) }
            }
        }
    }

    // MARK: Rows

    @ViewBuilder private var rows: some View {
        let _ = catalog.revision
        let cont = catalog.continueWatching()
        if !cont.isEmpty {
            Row(title: "Continue watching") {
                ForEach(cont) { m in
                    let key = catalog.movieKey(m)
                    let dur = Prefs.shared.duration(key)
                    let pos = Prefs.shared.position(key)
                    Button { path.append(Route.movie(m.id)) } label: {
                        PosterCard(title: m.name, image: m.poster, progress: dur > 0 ? pos / dur : min(pos / 6000, 0.9), width: 124)
                    }
                }
            }
        }
        let recentCh = catalog.channels(in: Catalog.catRecent)
        if !recentCh.isEmpty { channelRow("Recently watched channels", Array(recentCh.prefix(20))) }

        let newMovies = catalog.recentlyAddedMovies()
        if !newMovies.isEmpty {
            Row(title: "New movies", action: { selectTab("vod") }) {
                ForEach(newMovies) { m in
                    Button { path.append(Route.movie(m.id)) } label: {
                        PosterCard(title: m.name, image: m.poster, badge: ratingText(m.rating), width: 124)
                    }
                }
            }
        }
        let newSeries = catalog.recentlyAddedSeries()
        if !newSeries.isEmpty {
            Row(title: "New series", action: { selectTab("series") }) {
                ForEach(newSeries) { s in
                    Button { path.append(Route.series(s.id)) } label: {
                        PosterCard(title: s.name, image: s.poster, badge: ratingText(s.rating), width: 124)
                    }
                }
            }
        }
        let yourSeries = catalog.recentSeries()
        if !yourSeries.isEmpty {
            Row(title: "Your series") {
                ForEach(yourSeries) { s in
                    Button { path.append(Route.series(s.id)) } label: { PosterCard(title: s.name, image: s.poster, width: 124) }
                }
            }
        }
        let favCh = catalog.channels(in: Catalog.catFav)
        if !favCh.isEmpty { channelRow("Favourite channels", Array(favCh.prefix(30))) }

        let myList = catalog.movies(in: Catalog.catFav)
        if !myList.isEmpty {
            Row(title: "My list") {
                ForEach(myList.prefix(30)) { m in
                    Button { path.append(Route.movie(m.id)) } label: { PosterCard(title: m.name, image: m.poster, width: 124) }
                }
            }
        }
        if recentCh.isEmpty && favCh.isEmpty {
            let all = catalog.channels(in: Catalog.catAll)
            if !all.isEmpty { channelRow("Live channels", Array(all.prefix(30)), action: { selectTab("live") }) }
        }
    }

    private func channelRow(_ title: String, _ list: [Channel], action: (() -> Void)? = nil) -> some View {
        Row(title: title, action: action) {
            ForEach(Array(list.enumerated()), id: \.element.id) { i, c in
                Button {
                    let items = list.map { PlayItem(key: "", title: $0.name, url: catalog.url(for: $0), isLive: true, channelId: $0.id, poster: $0.logo) }
                    app.play(items, i)
                } label: { ChannelCard(name: c.name, logo: c.logo) }
            }
        }
    }
}

private struct Row<Content: View>: View {
    let title: String
    var action: (() -> Void)? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(.title3.weight(.bold)).foregroundColor(Theme.text)
                Spacer()
                if let a = action {
                    Button(action: a) {
                        HStack(spacing: 2) { Text("See all"); Image(systemName: "chevron.right") }
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Theme.accent2)
                    }
                }
            }
            .padding(.horizontal, 18)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) { content() }
                    .padding(.horizontal, 18)
                    .buttonStyle(CardPressStyle())
            }
        }
    }
}

struct CardPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

private struct TopBar: View {
    let selectTab: (String) -> Void

    var body: some View {
        HStack(spacing: 10) {
            AppLogo(size: 30)
            Text("StreamBox").font(.title3.weight(.black)).foregroundColor(Theme.text)
            Spacer()
            ForEach([("Live", "live"), ("Movies", "vod"), ("Series", "series")], id: \.1) { label, tag in
                Button(label) { selectTab(tag) }
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Theme.text)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Capsule().fill(.ultraThinMaterial))
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 54)
    }
}

private struct HeroPage: View {
    let movie: Movie
    @Binding var path: NavigationPath
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @State private var info: MovieInfo?
    @State private var zoom = false

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: (info?.backdrop.isEmpty == false ? info!.backdrop : movie.poster))
                    .frame(width: g.size.width, height: g.size.height)
                    .scaleEffect(zoom ? 1.08 : 1.0)
                    .animation(.linear(duration: 14), value: zoom)
                    .clipped()
                LinearGradient(colors: [.clear, Theme.bg.opacity(0.55), Theme.bg], startPoint: .center, endPoint: .bottom)
                LinearGradient(colors: [Theme.bg.opacity(0.7), .clear], startPoint: .top, endPoint: .init(x: 0.5, y: 0.25))

                VStack(alignment: .leading, spacing: 10) {
                    Text("NEW ON STREAMBOX").font(.caption.weight(.bold)).tracking(2).foregroundColor(Theme.accent2)
                    Text(movie.name)
                        .font(.system(size: 34, weight: .black))
                        .foregroundColor(Theme.text)
                        .lineLimit(2)
                        .shadow(color: .black.opacity(0.5), radius: 10)
                    Text(meta).font(.subheadline.weight(.semibold)).foregroundColor(Theme.text.opacity(0.9))
                    if let plot = info?.plot, !plot.isEmpty {
                        Text(plot).font(.subheadline).foregroundColor(Theme.text.opacity(0.8)).lineLimit(2)
                    }
                    HStack(spacing: 12) {
                        Button {
                            app.play([PlayItem(key: catalog.movieKey(movie), title: movie.name, url: catalog.url(for: movie), isLive: false, poster: movie.poster)], 0)
                        } label: { Label("Play", systemImage: "play.fill") }
                            .buttonStyle(PrimaryButtonStyle())
                        Button { path.append(Route.movie(movie.id)) } label: { Label("More info", systemImage: "info.circle") }
                            .buttonStyle(GlassButtonStyle())
                    }
                    .padding(.top, 4)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 34)
                .frame(maxWidth: 620, alignment: .leading)
            }
        }
        .task {
            zoom = true
            info = catalog.cachedInfo(movie)
            if info == nil { info = await catalog.movieInfo(movie) }
        }
    }

    private var meta: String {
        let r = ratingText(info?.rating.isEmpty == false ? info!.rating : movie.rating)
        let parts = [r, String((info?.releaseDate ?? "").prefix(4)), info?.duration ?? "", info?.genre ?? ""]
        return parts.filter { !$0.isEmpty }.joined(separator: "   ")
    }
}

private struct WelcomeHero: View {
    let selectTab: (String) -> Void
    @EnvironmentObject var catalog: Catalog

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [Theme.accent.opacity(0.45), Theme.bg], startPoint: .topLeading, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 10) {
                Text((catalog.profile?.name ?? "").uppercased()).font(.caption.weight(.bold)).tracking(2).foregroundColor(Theme.accent2)
                Text("Ready to watch").font(.system(size: 34, weight: .black)).foregroundColor(Theme.text)
                Text("\(catalog.channels.count) channels  •  \(catalog.movies.count) movies  •  \(catalog.series.count) series")
                    .foregroundColor(Theme.text.opacity(0.85))
                Button { selectTab("live") } label: { Label("Watch Live TV", systemImage: "play.fill") }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.top, 6)
            }
            .padding(20)
            .padding(.bottom, 10)
        }
        .overlay(alignment: .top) { TopBar(selectTab: selectTab) }
    }
}
