import SwiftUI

/// Movies or Series: category chips, search and a poster grid.
struct BrowseView: View {
    let section: ContentSection
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var catId = Catalog.catAll
    @State private var query = ""
    @State private var path = NavigationPath()
    @State private var restored = false

    private var isVod: Bool { section == .vod }
    private var favType: String { isVod ? Prefs.favVod : Prefs.favSeries }

    private var categories: [Category] {
        [Category(id: Catalog.catAll, name: isVod ? "All movies" : "All series"),
         Category(id: Catalog.catFav, name: "★ My list")] + (isVod ? catalog.vodCats : catalog.seriesCats)
    }

    private struct Card: Identifiable { let id: String; let name: String; let poster: String; let rating: String }

    private var cards: [Card] {
        let _ = catalog.revision
        let q = query.trimmingCharacters(in: .whitespaces)
        let all: [Card] = isVod
            ? catalog.movies(in: catId).map { Card(id: $0.id, name: $0.name, poster: $0.poster, rating: $0.rating) }
            : catalog.series(in: catId).map { Card(id: $0.id, name: $0.name, poster: $0.poster, rating: $0.rating) }
        return q.isEmpty ? all : all.filter { $0.name.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(isVod ? "Movies" : "Series")
                        .font(.largeTitle.weight(.heavy)).foregroundColor(Theme.text)
                        .padding(.horizontal, 16).padding(.top, 8)
                    SearchField(placeholder: isVod ? "Search movies" : "Search series", text: $query).padding(.horizontal, 16)
                    chips
                    grid
                }
                .padding(.bottom, 24)
            }
            .background(AppBackground())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Route.self) { r in
                switch r {
                case .movie(let id): MovieDetailView(movieId: id)
                case .series(let id): SeriesDetailView(seriesId: id)
                }
            }
            .onAppear {
                guard !restored else { return }
                restored = true
                let saved = Prefs.shared.lastCategory(catalog.profileId, section.rawValue)
                if let c = categories.first(where: { $0.id == saved }), !catalog.isLocked(section, c) { catId = c.id }
            }
        }
    }

    private var chips: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(categories) { c in
                        let locked = catalog.isLocked(section, c)
                        Button {
                            app.unlock(locked) {
                                catId = c.id
                                Prefs.shared.setLastCategory(catalog.profileId, section.rawValue, c.id)
                            }
                        } label: { Chip(title: (locked ? "🔒 " : "") + c.name, selected: c.id == catId) }
                        .id(c.id)
                        .contextMenu {
                            if Prefs.shared.parentalEnabled && !c.id.hasPrefix("__") {
                                Button(catalog.isUserLocked(section, c) ? "Unlock category" : "Lock category") {
                                    app.unlock(true) {
                                        Prefs.shared.toggleLocked(catalog.profileId, "\(section.rawValue):\(c.id)")
                                        catalog.revision += 1
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .frame(height: 44)
            .onAppear { proxy.scrollTo(catId, anchor: .center) }
        }
    }

    private var grid: some View {
        let width: CGFloat = hSize == .regular ? 150 : 108
        let list = cards
        return LazyVGrid(columns: [GridItem(.adaptive(minimum: width, maximum: width + 30), spacing: 12)], spacing: 18) {
            ForEach(list) { c in
                Button {
                    path.append(isVod ? Route.movie(c.id) : Route.series(c.id))
                } label: {
                    let fav = Prefs.shared.isFavourite(catalog.profileId, favType, c.id)
                    PosterCard(title: c.name, image: c.poster, badge: (fav ? "♥ " : "") + ratingText(c.rating), width: width)
                }
                .buttonStyle(CardPressStyle())
                .contextMenu {
                    Button {
                        Prefs.shared.toggleFavourite(catalog.profileId, favType, c.id)
                        catalog.revision += 1
                    } label: {
                        Label(Prefs.shared.isFavourite(catalog.profileId, favType, c.id) ? "Remove from My list" : "Add to My list", systemImage: "heart")
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .overlay {
            if list.isEmpty { Text("Nothing here").foregroundColor(Theme.dim).padding(.top, 60) }
        }
    }
}
