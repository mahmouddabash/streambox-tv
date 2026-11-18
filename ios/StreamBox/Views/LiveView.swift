import SwiftUI

struct LiveView: View {
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @State private var archiveOnly = false
    @State private var catId = Catalog.catAll
    @State private var query = ""
    @State private var guideFor: Channel?
    @State private var favs: Set<String> = []
    @State private var restored = false

    private var screenKey: String { archiveOnly ? "catchup" : "live" }

    private var categories: [Category] {
        var list = [Category(id: Catalog.catAll, name: "All channels"), Category(id: Catalog.catFav, name: "★ Favourites")]
        if !archiveOnly { list.append(Category(id: Catalog.catRecent, name: "Recent")) }
        if archiveOnly {
            let withArchive = Set(catalog.channels.filter(\.hasArchive).map(\.categoryId))
            list += catalog.liveCats.filter { withArchive.contains($0.id) }
        } else {
            list += catalog.liveCats
        }
        return list
    }

    private var list: [Channel] {
        let _ = catalog.revision
        let base = catalog.channels(in: catId, archiveOnly: archiveOnly)
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? base : base.filter { $0.name.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                header
                SearchField(placeholder: "Search channels", text: $query).padding(.horizontal, 16)
                chips
                channelList
            }
            .background(AppBackground())
            .toolbar(.hidden, for: .navigationBar)
            .sheet(item: $guideFor) { c in GuideSheet(channel: c) }
            .onAppear {
                favs = Prefs.shared.favourites(catalog.profileId, Prefs.favLive)
                if !restored {
                    restored = true
                    restoreCategory()
                }
            }
            .onChange(of: archiveOnly) { _ in restoreCategory() }
        }
    }

    private var header: some View {
        HStack {
            Text(archiveOnly ? "Catch-up" : "Live TV").font(.largeTitle.weight(.heavy)).foregroundColor(Theme.text)
            Spacer()
            Picker("", selection: $archiveOnly) {
                Text("Live").tag(false)
                Text("Catch-up").tag(true)
            }
            .pickerStyle(.segmented)
            .frame(width: 190)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var chips: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(categories) { c in
                        let locked = catalog.isLocked(.live, c)
                        Button {
                            app.unlock(locked) { select(c.id) }
                        } label: {
                            Chip(title: (locked ? "🔒 " : "") + c.name, selected: c.id == catId)
                        }
                        .id(c.id)
                        .contextMenu {
                            if Prefs.shared.parentalEnabled && !c.id.hasPrefix("__") {
                                Button(catalog.isUserLocked(.live, c) ? "Unlock category" : "Lock category") {
                                    app.unlock(true) {
                                        Prefs.shared.toggleLocked(catalog.profileId, "live:\(c.id)")
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

    private var channelList: some View {
        let channels = list
        let lastId = Prefs.shared.recent("recentCh_\(catalog.profileId)").first
        return ScrollViewReader { proxy in
            List {
                ForEach(Array(channels.enumerated()), id: \.element.id) { i, c in
                    Button { open(c, i, channels) } label: {
                        ChannelRow(channel: c, isFav: favs.contains(c.id), isLast: c.id == lastId)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                    .id(c.id)
                    .swipeActions(edge: .trailing) {
                        Button { toggleFav(c) } label: { Label("Favourite", systemImage: favs.contains(c.id) ? "star.slash" : "star") }
                            .tint(Theme.gold)
                        if catalog.hasEpg {
                            Button { guideFor = c } label: { Label("Guide", systemImage: "list.bullet.rectangle") }.tint(Theme.accent)
                        }
                    }
                    .contextMenu {
                        Button { toggleFav(c) } label: {
                            Label(favs.contains(c.id) ? "Remove from favourites" : "Add to favourites", systemImage: "star")
                        }
                        if catalog.hasEpg {
                            Button { guideFor = c } label: { Label(c.hasArchive ? "Guide & catch-up" : "Programme guide", systemImage: "list.bullet.rectangle") }
                        }
                    }
                }
                if channels.isEmpty {
                    Text("Nothing here").foregroundColor(Theme.dim).listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .onAppear {
                if let id = lastId, channels.contains(where: { $0.id == id }) { proxy.scrollTo(id, anchor: .center) }
            }
        }
    }

    private func select(_ id: String) {
        catId = id
        Prefs.shared.setLastCategory(catalog.profileId, screenKey, id)
    }

    private func restoreCategory() {
        let saved = Prefs.shared.lastCategory(catalog.profileId, screenKey)
        if let c = categories.first(where: { $0.id == saved }), !catalog.isLocked(.live, c) {
            catId = c.id
        } else {
            catId = Catalog.catAll
        }
    }

    private func toggleFav(_ c: Channel) {
        Prefs.shared.toggleFavourite(catalog.profileId, Prefs.favLive, c.id)
        favs = Prefs.shared.favourites(catalog.profileId, Prefs.favLive)
        catalog.revision += 1
    }

    private func open(_ c: Channel, _ index: Int, _ list: [Channel]) {
        if archiveOnly {
            guideFor = c
            return
        }
        let items = list.map { PlayItem(key: "", title: $0.name, url: catalog.url(for: $0), isLive: true, channelId: $0.id, poster: $0.logo) }
        app.play(items, index)
    }
}

private struct ChannelRow: View {
    let channel: Channel
    let isFav: Bool
    let isLast: Bool
    @EnvironmentObject var catalog: Catalog
    @State private var now: Programme?

    var body: some View {
        HStack(spacing: 12) {
            Text("\(channel.num)")
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundColor(Theme.dim)
                .frame(width: 34, alignment: .leading)
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.45))
                RemoteImage(url: channel.logo, contentMode: .fit).padding(5)
            }
            .frame(width: 64, height: 42)
            VStack(alignment: .leading, spacing: 3) {
                Text(channel.name).font(.body.weight(.semibold)).foregroundColor(Theme.text).lineLimit(1)
                if let p = now {
                    Text(p.title).font(.caption).foregroundColor(Theme.dim).lineLimit(1)
                    GeometryReader { g in
                        let total = p.end.timeIntervalSince(p.start)
                        let frac = total > 0 ? min(1, max(0, Date().timeIntervalSince(p.start) / total)) : 0
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.white.opacity(0.12))
                            Capsule().fill(Theme.gradient).frame(width: g.size.width * frac)
                        }
                    }
                    .frame(height: 3)
                    .frame(maxWidth: 160)
                }
            }
            Spacer(minLength: 4)
            if channel.hasArchive { Image(systemName: "clock.arrow.circlepath").foregroundColor(Theme.accent2).font(.caption) }
            if isFav { Image(systemName: "star.fill").foregroundColor(Theme.gold).font(.caption) }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 12)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(isLast ? AnyShapeStyle(Theme.accent.opacity(0.22)) : AnyShapeStyle(Theme.glass))
        )
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(isLast ? Theme.accent : Theme.stroke, lineWidth: isLast ? 1 : 0.5))
        .task(id: channel.id) {
            guard catalog.hasEpg else { return }
            try? await Task.sleep(nanoseconds: 350_000_000) // skip rows that just scroll past
            if Task.isCancelled { return }
            now = await catalog.upcoming(channel).first
        }
    }
}

/// Programme guide for one channel; past programmes with an archive play as catch-up.
struct GuideSheet: View {
    let channel: Channel
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var list: [Programme] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    if loading { ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear) }
                    if !loading && list.isEmpty {
                        Text("No guide data for this channel").foregroundColor(Theme.dim).listRowBackground(Color.clear)
                    }
                    ForEach(list) { p in
                        Button { play(p) } label: { row(p) }
                            .listRowBackground(p.isNow ? Theme.accent.opacity(0.2) : Color.clear)
                            .id(p.id)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .onChange(of: list.count) { _ in
                    if let n = list.first(where: { $0.isNow }) { proxy.scrollTo(n.id, anchor: .center) }
                }
            }
            .background(AppBackground())
            .navigationTitle(channel.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .cancellationAction) {
                    Button { playLive() } label: { Label("Watch live", systemImage: "play.fill") }
                }
            }
            .task {
                list = channel.hasArchive ? await catalog.fullGuide(channel) : await catalog.upcoming(channel)
                loading = false
            }
        }
    }

    private func row(_ p: Programme) -> some View {
        let canCatchup = channel.hasArchive && p.isPast
        return HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Fmt.time(p.start)).font(.subheadline.weight(.semibold).monospacedDigit()).foregroundColor(Theme.text)
                Text(Fmt.day(p.start)).font(.caption2).foregroundColor(Theme.dim)
            }
            .frame(width: 70, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    if p.isNow { Text("NOW").font(.caption2.weight(.heavy)).foregroundColor(Theme.live) }
                    Text(p.title).font(.body.weight(.semibold)).foregroundColor(p.isPast && !canCatchup ? Theme.dim : Theme.text)
                }
                if !p.desc.isEmpty { Text(p.desc).font(.caption).foregroundColor(Theme.dim).lineLimit(2) }
            }
            Spacer()
            if canCatchup { Image(systemName: "play.circle.fill").foregroundColor(Theme.accent2).font(.title3) }
        }
        .padding(.vertical, 4)
    }

    private func play(_ p: Programme) {
        if p.isNow || !p.isPast { playLive(); return }
        guard channel.hasArchive else { return }
        let item = PlayItem(key: "", title: "\(channel.name) – \(p.title)", url: catalog.catchupURL(channel, p), isLive: false, poster: channel.logo)
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { app.play([item], 0) }
    }

    private func playLive() {
        let item = PlayItem(key: "", title: channel.name, url: catalog.url(for: channel), isLive: true, channelId: channel.id, poster: channel.logo)
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { app.play([item], 0) }
    }
}
