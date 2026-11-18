import SwiftUI

/// Big backdrop header that fades into the page.
private struct Backdrop: View {
    let url: String
    var height: CGFloat = 420

    var body: some View {
        ZStack {
            RemoteImage(url: url)
                .frame(height: height)
                .frame(maxWidth: .infinity)
                .clipped()
            LinearGradient(colors: [Theme.bg.opacity(0.35), .clear, Theme.bg.opacity(0.8), Theme.bg],
                           startPoint: .top, endPoint: .bottom)
        }
        .frame(height: height)
    }
}

struct MovieDetailView: View {
    let movieId: String
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @State private var info = MovieInfo()
    @State private var fav = false
    @State private var resumeAt: Double = 0

    var body: some View {
        if let m = catalog.movie(movieId) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Backdrop(url: info.backdrop.isEmpty ? m.poster : info.backdrop)
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .bottom, spacing: 16) {
                            RemoteImage(url: m.poster)
                                .frame(width: 110, height: 165)
                                .background(Theme.panel)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .shadow(color: .black.opacity(0.6), radius: 14, y: 6)
                            VStack(alignment: .leading, spacing: 6) {
                                Text("MOVIE").font(.caption.weight(.bold)).tracking(2).foregroundColor(Theme.accent2)
                                Text(m.name).font(.system(size: 28, weight: .black)).foregroundColor(Theme.text).lineLimit(3)
                                MetaChips(items: [ratingText(info.rating.isEmpty ? m.rating : info.rating),
                                                  String(info.releaseDate.prefix(4)), info.duration])
                            }
                        }
                        .padding(.top, -120)

                        HStack(spacing: 12) {
                            Button { play(m, fromStart: true) } label: { Label("Play", systemImage: "play.fill") }
                                .buttonStyle(PrimaryButtonStyle())
                            if resumeAt > 0 {
                                Button { play(m, fromStart: false) } label: { Label("Resume \(Fmt.duration(resumeAt))", systemImage: "gobackward") }
                                    .buttonStyle(GlassButtonStyle())
                            }
                            Button {
                                fav = Prefs.shared.toggleFavourite(catalog.profileId, Prefs.favVod, m.id)
                                catalog.revision += 1
                            } label: { Image(systemName: fav ? "checkmark" : "plus") }
                                .buttonStyle(GlassButtonStyle())
                        }

                        if !info.genre.isEmpty { Text(info.genre).font(.subheadline.weight(.semibold)).foregroundColor(Theme.accent2) }
                        if !info.plot.isEmpty { Text(info.plot).font(.body).foregroundColor(Theme.text.opacity(0.9)).lineSpacing(3) }
                        if !info.director.isEmpty { labelled("Director", info.director) }
                        if !info.cast.isEmpty { labelled("Cast", info.cast) }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 40)
                    .frame(maxWidth: 820, alignment: .leading)
                }
            }
            .ignoresSafeArea(edges: .top)
            .background(AppBackground())
            .toolbar(.visible, for: .navigationBar)
            .navigationBarTitleDisplayMode(.inline)
            .task {
                fav = Prefs.shared.isFavourite(catalog.profileId, Prefs.favVod, m.id)
                if let c = catalog.cachedInfo(m) { info = c }
                info = await catalog.movieInfo(m)
            }
            .onAppear { resumeAt = Prefs.shared.position(catalog.movieKey(m)) }
            .onChange(of: app.playback == nil) { closed in
                if closed { resumeAt = Prefs.shared.position(catalog.movieKey(m)) }
            }
        } else {
            Text("Not available").foregroundColor(Theme.dim)
        }
    }

    private func labelled(_ title: String, _ value: String) -> some View {
        (Text(title + ": ").bold().foregroundColor(Theme.text) + Text(value).foregroundColor(Theme.dim))
            .font(.subheadline)
    }

    private func play(_ m: Movie, fromStart: Bool) {
        app.play([PlayItem(key: catalog.movieKey(m), title: m.name, url: catalog.url(for: m), isLive: false, poster: m.poster)], 0, fromStart: fromStart)
    }
}

struct MetaChips: View {
    let items: [String]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(items.filter { !$0.isEmpty }, id: \.self) { t in
                Text(t)
                    .font(.caption.weight(.bold))
                    .foregroundColor(t.hasPrefix("★") ? Theme.gold : Theme.text)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.14)))
            }
        }
    }
}

struct SeriesDetailView: View {
    let seriesId: String
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @State private var seasons: [Int: [Episode]] = [:]
    @State private var season = 0
    @State private var loading = true
    @State private var error = ""
    @State private var fav = false
    @State private var tick = 0

    var body: some View {
        if let s = catalog.seriesItem(seriesId) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Backdrop(url: s.poster, height: 380)
                    VStack(alignment: .leading, spacing: 14) {
                        Text("SERIES").font(.caption.weight(.bold)).tracking(2).foregroundColor(Theme.accent2)
                        Text(s.name).font(.system(size: 30, weight: .black)).foregroundColor(Theme.text)
                        MetaChips(items: [ratingText(s.rating), String(s.releaseDate.prefix(4)),
                                          seasons.isEmpty ? "" : "\(seasons.count) season\(seasons.count == 1 ? "" : "s")"])
                        HStack(spacing: 12) {
                            if let next = nextEpisode {
                                Button { play(next) } label: {
                                    Label(next.1 > 0 ? "Resume S\(next.0.season) E\(next.0.num)" : "Play S\(next.0.season) E\(next.0.num)", systemImage: "play.fill")
                                }
                                .buttonStyle(PrimaryButtonStyle())
                            }
                            Button {
                                fav = Prefs.shared.toggleFavourite(catalog.profileId, Prefs.favSeries, s.id)
                                catalog.revision += 1
                            } label: { Label(fav ? "In my list" : "My list", systemImage: fav ? "checkmark" : "plus") }
                                .buttonStyle(GlassButtonStyle())
                        }
                        if !s.genre.isEmpty { Text(s.genre).font(.subheadline.weight(.semibold)).foregroundColor(Theme.accent2) }
                        if !s.plot.isEmpty { Text(s.plot).foregroundColor(Theme.text.opacity(0.9)).lineSpacing(3) }

                        if loading { ProgressView().frame(maxWidth: .infinity).padding(30) }
                        if !error.isEmpty { Text(error).foregroundColor(Theme.gold) }

                        if !seasons.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(seasons.keys.sorted(), id: \.self) { n in
                                        Button { withAnimation { season = n } } label: { Chip(title: "Season \(n)", selected: n == season) }
                                    }
                                }
                            }
                            .padding(.top, 6)
                            let _ = tick
                            ForEach(Array((seasons[season] ?? []).enumerated()), id: \.element.id) { i, e in
                                Button { playSeason(from: i) } label: { EpisodeRow(episode: e, poster: s.poster) }
                                    .buttonStyle(CardPressStyle())
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, -90)
                    .padding(.bottom, 40)
                    .frame(maxWidth: 820, alignment: .leading)
                }
            }
            .ignoresSafeArea(edges: .top)
            .background(AppBackground())
            .toolbar(.visible, for: .navigationBar)
            .navigationBarTitleDisplayMode(.inline)
            .task {
                fav = Prefs.shared.isFavourite(catalog.profileId, Prefs.favSeries, s.id)
                catalog.addRecentSeries(s)
                do {
                    seasons = try await catalog.episodes(s.id)
                    season = nextEpisode?.0.season ?? seasons.keys.sorted().first ?? 0
                    if seasons.isEmpty { error = "No episodes available" }
                } catch {
                    self.error = "Could not load episodes: \(Net.readable(error))"
                }
                loading = false
            }
            .onChange(of: app.playback == nil) { _ in tick += 1 }
        } else {
            Text("Not available").foregroundColor(Theme.dim)
        }
    }

    /// The episode in progress, else the first unwatched one (with its saved position).
    private var nextEpisode: (Episode, Double)? {
        let _ = tick
        let all = seasons.keys.sorted().flatMap { seasons[$0] ?? [] }
        guard !all.isEmpty else { return nil }
        let keys = Set(Prefs.shared.resumeIds("ep_\(catalog.profileId)_"))
        if let e = all.first(where: { keys.contains($0.id) }) {
            return (e, Prefs.shared.position(catalog.episodeKey(e)))
        }
        return (all[0], 0)
    }

    private func items(_ list: [Episode]) -> [PlayItem] {
        let name = catalog.seriesItem(seriesId)?.name ?? ""
        return list.map { PlayItem(key: catalog.episodeKey($0), title: "\(name) – S\($0.season)E\($0.num) \($0.title)", url: $0.url, isLive: false) }
    }

    private func play(_ next: (Episode, Double)) {
        let list = seasons[next.0.season] ?? [next.0]
        let i = list.firstIndex(where: { $0.id == next.0.id }) ?? 0
        app.play(items(list), i)
    }

    private func playSeason(from i: Int) {
        app.play(items(seasons[season] ?? []), i)
    }
}

private struct EpisodeRow: View {
    let episode: Episode
    let poster: String
    @EnvironmentObject var catalog: Catalog

    var body: some View {
        let key = catalog.episodeKey(episode)
        let pos = Prefs.shared.position(key)
        let dur = Prefs.shared.duration(key)
        HStack(spacing: 12) {
            ZStack(alignment: .bottom) {
                RemoteImage(url: episode.image.isEmpty ? poster : episode.image)
                    .frame(width: 130, height: 74)
                    .background(Theme.panel)
                    .clipped()
                Image(systemName: "play.circle.fill").font(.title).foregroundColor(.white.opacity(0.9))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if pos > 0 {
                    GeometryReader { g in
                        Capsule().fill(Theme.gradient)
                            .frame(width: g.size.width * (dur > 0 ? min(1, pos / dur) : 0.3), height: 3)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
            }
            .frame(width: 130, height: 74)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text("\(episode.num). \(episode.title)").font(.subheadline.weight(.semibold)).foregroundColor(Theme.text).lineLimit(2)
                if !episode.duration.isEmpty { Text(episode.duration).font(.caption).foregroundColor(Theme.dim) }
                if !episode.plot.isEmpty { Text(episode.plot).font(.caption).foregroundColor(Theme.dim).lineLimit(2) }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.glass))
    }
}
