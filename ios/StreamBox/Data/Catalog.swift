import Foundation
import SwiftUI

/// The loaded catalogue of the active profile plus every query the screens need.
@MainActor
final class Catalog: ObservableObject {
    static let catAll = "__all"
    static let catFav = "__fav"
    static let catRecent = "__recent"

    @Published private(set) var profile: Profile?
    @Published private(set) var loaded = false
    @Published private(set) var loading = false
    @Published var status = ""
    @Published private(set) var account = AccountInfo()
    @Published private(set) var liveCats: [Category] = []
    @Published private(set) var channels: [Channel] = []
    @Published private(set) var vodCats: [Category] = []
    @Published private(set) var movies: [Movie] = []
    @Published private(set) var seriesCats: [Category] = []
    @Published private(set) var series: [Series] = []
    /// Bumped when favourites / resume positions change so rows re-read them.
    @Published var revision = 0

    private var channelById: [String: Channel] = [:]
    private var movieById: [String: Movie] = [:]
    private var seriesById: [String: Series] = [:]
    private var movieInfoCache: [String: MovieInfo] = [:]
    private var epgCache: [String: (Date, [Programme])] = [:]

    private let prefs = Prefs.shared
    var profileId: String { profile?.id ?? "" }

    func channel(_ id: String) -> Channel? { channelById[id] }
    func movie(_ id: String) -> Movie? { movieById[id] }
    func seriesItem(_ id: String) -> Series? { seriesById[id] }

    // MARK: URLs

    static func normalizeServer(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "http://" + s }
        for suffix in ["/player_api.php", "/get.php", "/c"] where s.lowercased().hasSuffix(suffix) {
            s = String(s.dropLast(suffix.count))
        }
        return s
    }

    private func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+?"))) ?? s
    }

    private func api(_ p: Profile, _ action: String? = nil, _ extra: String = "") -> String {
        var s = "\(p.server)/player_api.php?username=\(enc(p.username))&password=\(enc(p.password))"
        if let a = action { s += "&action=\(a)" }
        return s + extra
    }

    /// Apple's player needs HLS for live TV, so Xtream channels are always requested as .m3u8.
    func url(for c: Channel) -> String {
        if !c.url.isEmpty { return c.url }
        guard let p = profile else { return "" }
        return "\(p.server)/live/\(p.username)/\(p.password)/\(c.id).m3u8"
    }

    func url(for m: Movie) -> String {
        if !m.url.isEmpty { return m.url }
        guard let p = profile else { return "" }
        return "\(p.server)/movie/\(p.username)/\(p.password)/\(m.id).\(m.ext.isEmpty ? "mp4" : m.ext)"
    }

    /// Xtream timeshift as HLS: /timeshift/user/pass/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.m3u8
    func catchupURL(_ c: Channel, _ pr: Programme) -> String {
        guard let p = profile else { return "" }
        let minutes = max(1, Int(pr.end.timeIntervalSince(pr.start) / 60))
        let start: String
        if pr.startRaw.count >= 16 {
            let r = pr.startRaw
            let date = r.prefix(10)
            let time = r.dropFirst(11).prefix(5).replacingOccurrences(of: ":", with: "-")
            start = "\(date):\(time)"
        } else {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd:HH-mm"
            start = f.string(from: pr.start)
        }
        return "\(p.server)/timeshift/\(p.username)/\(p.password)/\(minutes)/\(start)/\(c.id).m3u8"
    }

    // MARK: Login

    static func authenticate(_ p: Profile) async throws -> AccountInfo {
        guard p.isXtream else { return AccountInfo(status: "Playlist") }
        let url = "\(p.server)/player_api.php?username=\(p.username.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? p.username)&password=\(p.password.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? p.password)"
        let data = try await Net.dataAcceptingUA(url)
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "StreamBox", code: 1, userInfo: [NSLocalizedDescriptionKey: "This server did not answer like an Xtream Codes panel"])
        }
        guard let u = root["user_info"] as? [String: Any] else {
            throw NSError(domain: "StreamBox", code: 2, userInfo: [NSLocalizedDescriptionKey: "Login failed: no account info returned"])
        }
        let auth = jstr(u["auth"])
        if auth == "0" || auth.lowercased() == "false" {
            throw NSError(domain: "StreamBox", code: 3, userInfo: [NSLocalizedDescriptionKey: "Wrong username or password"])
        }
        let exp = jdouble(u["exp_date"])
        return AccountInfo(
            status: jstr(u["status"]),
            expiry: exp > 0 ? Date(timeIntervalSince1970: exp) : nil,
            maxConnections: jstr(u["max_connections"]),
            activeConnections: jstr(u["active_cons"])
        )
    }

    // MARK: Loading

    private func cacheDir(_ p: Profile) -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("data_\(p.id)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func clearCache() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        if let items = try? FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil) {
            for u in items where u.lastPathComponent.hasPrefix("data_") { try? FileManager.default.removeItem(at: u) }
        }
    }

    /// Returns the cached file when fresh enough, otherwise downloads it (falling back to an old copy offline).
    private nonisolated func fetch(_ dir: URL, _ name: String, _ url: String, force: Bool, maxAgeHours: Int) async throws -> Data {
        let file = dir.appendingPathComponent(name)
        let attrs = try? FileManager.default.attributesOfItem(atPath: file.path)
        let modified = attrs?[.modificationDate] as? Date
        if !force, let m = modified, Date().timeIntervalSince(m) < Double(maxAgeHours) * 3600,
           let d = try? Data(contentsOf: file) {
            return d
        }
        do {
            let d = try await Net.data(url)
            try? d.write(to: file, options: .atomic)
            return d
        } catch {
            if let d = try? Data(contentsOf: file) { return d }
            throw error
        }
    }

    private nonisolated func jsonArray(_ d: Data) -> [[String: Any]] {
        (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] ?? []
    }

    private nonisolated func cats(_ d: Data) -> [Category] {
        jsonArray(d).compactMap { o in
            let id = jstr(o["category_id"])
            return id.isEmpty ? nil : Category(id: id, name: jstr(o["category_name"]))
        }
    }

    func load(_ p: Profile, force: Bool = false) async throws {
        if loading { return }
        if profile?.id != p.id { close() }
        loading = true
        defer { loading = false }
        let dir = cacheDir(p)
        let hours = prefs.cacheHours

        if p.isXtream {
            status = "Signing in…"
            let acc: AccountInfo
            do {
                acc = try await Catalog.authenticate(p)
            } catch {
                let cached = FileManager.default.fileExists(atPath: dir.appendingPathComponent("live_streams.json").path)
                if cached && !Net.readable(error).contains("Wrong") { acc = AccountInfo(status: "Offline") } else { throw error }
            }

            status = "Loading live channels…"
            let lc = cats(try await fetch(dir, "live_cats.json", api(p, "get_live_categories"), force: force, maxAgeHours: hours))
            let liveData = try await fetch(dir, "live_streams.json", api(p, "get_live_streams"), force: force, maxAgeHours: hours)
            let ch: [Channel] = await Task.detached { [self] in
                var out: [Channel] = []
                for o in self.jsonArray(liveData) {
                    let id = jstr(o["stream_id"])
                    if id.isEmpty { continue }
                    out.append(Channel(
                        id: id, num: jint(o["num"]) > 0 ? jint(o["num"]) : out.count + 1,
                        name: jstr(o["name"]), logo: jstr(o["stream_icon"]),
                        categoryId: jstr(o["category_id"]), epgId: jstr(o["epg_channel_id"]), url: "",
                        hasArchive: jbool(o["tv_archive"]), archiveDays: jint(o["tv_archive_duration"])))
                }
                return out
            }.value

            status = "Loading movies…"
            var vc: [Category] = []
            var mv: [Movie] = []
            if let d = try? await fetch(dir, "vod_streams.json", api(p, "get_vod_streams"), force: force, maxAgeHours: hours) {
                vc = cats((try? await fetch(dir, "vod_cats.json", api(p, "get_vod_categories"), force: force, maxAgeHours: hours)) ?? Data())
                mv = await Task.detached { [self] in
                    self.jsonArray(d).compactMap { o -> Movie? in
                        let id = jstr(o["stream_id"])
                        if id.isEmpty { return nil }
                        let ext = jstr(o["container_extension"])
                        return Movie(id: id, name: jstr(o["name"]), poster: jstr(o["stream_icon"]),
                                     categoryId: jstr(o["category_id"]), url: "", rating: jstr(o["rating"]),
                                     ext: ext.isEmpty ? "mp4" : ext, added: jdouble(o["added"]))
                    }
                }.value
            }

            status = "Loading series…"
            var sc: [Category] = []
            var sr: [Series] = []
            if let d = try? await fetch(dir, "series.json", api(p, "get_series"), force: force, maxAgeHours: hours) {
                sc = cats((try? await fetch(dir, "series_cats.json", api(p, "get_series_categories"), force: force, maxAgeHours: hours)) ?? Data())
                sr = await Task.detached { [self] in
                    self.jsonArray(d).compactMap { o -> Series? in
                        let id = jstr(o["series_id"])
                        if id.isEmpty { return nil }
                        return Series(id: id, name: jstr(o["name"]), poster: jstr(o["cover"]),
                                      categoryId: jstr(o["category_id"]), plot: jstr(o["plot"]), genre: jstr(o["genre"]),
                                      cast: jstr(o["cast"]), releaseDate: jstr(o, "releaseDate", "release_date"),
                                      rating: jstr(o["rating"]), added: jdouble(o["last_modified"]))
                    }
                }.value
            }
            publish(p, acc, lc, ch, vc, mv, sc, sr)
        } else {
            status = "Downloading playlist…"
            let d = try await fetch(dir, "playlist.m3u", p.m3uUrl, force: force, maxAgeHours: hours)
            status = "Reading playlist…"
            let r = await Task.detached { M3UParser.parse(d) }.value
            if r.channels.isEmpty && r.movies.isEmpty {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent("playlist.m3u"))
                throw NSError(domain: "StreamBox", code: 4, userInfo: [NSLocalizedDescriptionKey: "The playlist is empty or not a valid M3U file"])
            }
            publish(p, AccountInfo(status: "Playlist"), r.liveCats, r.channels, r.vodCats, r.movies, [], [])
        }
        status = ""
    }

    private func publish(_ p: Profile, _ acc: AccountInfo, _ lc: [Category], _ ch: [Channel],
                         _ vc: [Category], _ mv: [Movie], _ sc: [Category], _ sr: [Series]) {
        profile = p
        account = acc
        liveCats = lc; channels = ch
        vodCats = vc; movies = mv
        seriesCats = sc; series = sr
        channelById = Dictionary(ch.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        movieById = Dictionary(mv.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        seriesById = Dictionary(sr.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        movieInfoCache = [:]
        epgCache = [:]
        loaded = true
        prefs.lastProfileId = p.id
    }

    func close() {
        profile = nil
        loaded = false
        account = AccountInfo()
        liveCats = []; channels = []; vodCats = []; movies = []; seriesCats = []; series = []
        channelById = [:]; movieById = [:]; seriesById = [:]
        movieInfoCache = [:]; epgCache = [:]
    }

    // MARK: Parental lock

    private static let adult = try! NSRegularExpression(pattern: "(adult|xxx|18\\+|\\+18|porn|for adults)", options: [.caseInsensitive])

    func isLocked(_ section: ContentSection, _ cat: Category) -> Bool {
        guard prefs.parentalEnabled, !cat.id.hasPrefix("__") else { return false }
        let range = NSRange(cat.name.startIndex..., in: cat.name)
        return Catalog.adult.firstMatch(in: cat.name, range: range) != nil
            || prefs.lockedCategories(profileId).contains("\(section.rawValue):\(cat.id)")
    }

    func isUserLocked(_ section: ContentSection, _ cat: Category) -> Bool {
        prefs.lockedCategories(profileId).contains("\(section.rawValue):\(cat.id)")
    }

    private func lockedIds(_ section: ContentSection, _ cats: [Category]) -> Set<String> {
        Set(cats.filter { isLocked(section, $0) }.map(\.id))
    }

    // MARK: Queries

    func channels(in catId: String, archiveOnly: Bool = false) -> [Channel] {
        let locked = lockedIds(.live, liveCats)
        var base: [Channel]
        switch catId {
        case Catalog.catAll: base = channels.filter { !locked.contains($0.categoryId) }
        case Catalog.catFav:
            let fav = prefs.favourites(profileId, Prefs.favLive)
            base = channels.filter { fav.contains($0.id) && !locked.contains($0.categoryId) }
        case Catalog.catRecent:
            base = prefs.recent("recentCh_\(profileId)").compactMap { channelById[$0] }.filter { !locked.contains($0.categoryId) }
        default: base = channels.filter { $0.categoryId == catId }
        }
        return archiveOnly ? base.filter(\.hasArchive) : base
    }

    func movies(in catId: String) -> [Movie] {
        let locked = lockedIds(.vod, vodCats)
        switch catId {
        case Catalog.catAll: return movies.filter { !locked.contains($0.categoryId) }
        case Catalog.catFav:
            let fav = prefs.favourites(profileId, Prefs.favVod)
            return movies.filter { fav.contains($0.id) && !locked.contains($0.categoryId) }
        default: return movies.filter { $0.categoryId == catId }
        }
    }

    func series(in catId: String) -> [Series] {
        let locked = lockedIds(.series, seriesCats)
        switch catId {
        case Catalog.catAll: return series.filter { !locked.contains($0.categoryId) }
        case Catalog.catFav:
            let fav = prefs.favourites(profileId, Prefs.favSeries)
            return series.filter { fav.contains($0.id) && !locked.contains($0.categoryId) }
        default: return series.filter { $0.categoryId == catId }
        }
    }

    func recentlyAddedMovies(_ limit: Int = 30) -> [Movie] {
        let all = movies(in: Catalog.catAll)
        return all.contains { $0.added > 0 } ? Array(all.sorted { $0.added > $1.added }.prefix(limit)) : Array(all.suffix(limit).reversed())
    }

    func recentlyAddedSeries(_ limit: Int = 30) -> [Series] {
        let all = series(in: Catalog.catAll)
        return all.contains { $0.added > 0 } ? Array(all.sorted { $0.added > $1.added }.prefix(limit)) : Array(all.suffix(limit).reversed())
    }

    func movieKey(_ m: Movie) -> String { "vod_\(profileId)_\(m.id)" }
    func episodeKey(_ e: Episode) -> String { "ep_\(profileId)_\(e.id)" }

    func continueWatching(_ limit: Int = 20) -> [Movie] {
        let locked = lockedIds(.vod, vodCats)
        return Array(prefs.resumeIds("vod_\(profileId)_").compactMap { movieById[$0] }
            .filter { !locked.contains($0.categoryId) }.prefix(limit))
    }

    func recentSeries(_ limit: Int = 20) -> [Series] {
        let locked = lockedIds(.series, seriesCats)
        return Array(prefs.recent("recentSeries_\(profileId)").compactMap { seriesById[$0] }
            .filter { !locked.contains($0.categoryId) }.prefix(limit))
    }

    func addRecentChannel(_ c: Channel) {
        prefs.pushRecent("recentCh_\(profileId)", c.id)
        revision += 1
    }

    func addRecentSeries(_ s: Series) {
        prefs.pushRecent("recentSeries_\(profileId)", s.id)
    }

    // MARK: Details

    func cachedInfo(_ m: Movie) -> MovieInfo? { movieInfoCache[m.id] }

    func movieInfo(_ m: Movie) async -> MovieInfo {
        if let c = movieInfoCache[m.id] { return c }
        guard let p = profile, p.isXtream else { return MovieInfo() }
        guard let d = try? await Net.data(api(p, "get_vod_info", "&vod_id=\(m.id)")),
              let root = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let info = root["info"] as? [String: Any] else { return MovieInfo() }
        let r = MovieInfo(
            plot: jstr(info, "plot", "description"), cast: jstr(info, "cast", "actors"),
            director: jstr(info, "director"), genre: jstr(info, "genre"),
            releaseDate: jstr(info, "releasedate", "release_date"), duration: jstr(info, "duration"),
            rating: jstr(info, "rating"), backdrop: jstr(info["backdrop_path"]))
        movieInfoCache[m.id] = r
        return r
    }

    /// Season number -> episodes, both sorted.
    func episodes(_ seriesId: String) async throws -> [Int: [Episode]] {
        guard let p = profile else { return [:] }
        let d = try await Net.data(api(p, "get_series_info", "&series_id=\(seriesId)"))
        guard let root = try JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [:] }
        var out: [Int: [Episode]] = [:]

        func addAll(_ arr: [Any], _ seasonHint: Int) {
            for (i, any) in arr.enumerated() {
                guard let o = any as? [String: Any] else { continue }
                let id = jstr(o["id"])
                if id.isEmpty { continue }
                let season = Int(jstr(o["season"])) ?? seasonHint
                let num = Int(jstr(o["episode_num"])) ?? (i + 1)
                var ext = jstr(o["container_extension"])
                if ext.isEmpty { ext = "mp4" }
                let info = o["info"] as? [String: Any] ?? [:]
                let title = jstr(o["title"])
                out[season, default: []].append(Episode(
                    id: id, title: title.isEmpty ? "Episode \(num)" : title, season: season, num: num,
                    url: "\(p.server)/series/\(p.username)/\(p.password)/\(id).\(ext)",
                    plot: jstr(info["plot"]), duration: jstr(info["duration"]), image: jstr(info["movie_image"])))
            }
        }

        if let eps = root["episodes"] as? [String: Any] {
            for (k, v) in eps { if let arr = v as? [Any] { addAll(arr, Int(k) ?? 1) } }
        } else if let eps = root["episodes"] as? [Any] {
            for (i, v) in eps.enumerated() { if let arr = v as? [Any] { addAll(arr, i + 1) } }
        }
        for k in out.keys { out[k]?.sort { $0.num < $1.num } }
        return out
    }

    // MARK: Programme guide (Xtream)

    var hasEpg: Bool { profile?.isXtream ?? false }

    func upcoming(_ c: Channel) async -> [Programme] {
        let now = Date()
        return await guide(c, full: false).filter { $0.end > now }
    }

    func fullGuide(_ c: Channel) async -> [Programme] { await guide(c, full: true) }

    private func guide(_ c: Channel, full: Bool) async -> [Programme] {
        guard let p = profile, p.isXtream else { return [] }
        let key = (full ? "F" : "S") + c.id
        if let cached = epgCache[key], Date().timeIntervalSince(cached.0) < 600 { return cached.1 }
        let url = full ? api(p, "get_simple_data_table", "&stream_id=\(c.id)") : api(p, "get_short_epg", "&stream_id=\(c.id)&limit=12")
        guard let d = try? await Net.data(url) else { return [] }
        let list = Catalog.parseListings(d)
        epgCache[key] = (Date(), list)
        return list
    }

    private static func parseListings(_ d: Data) -> [Programme] {
        guard let root = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let arr = root["epg_listings"] as? [[String: Any]] else { return [] }
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
        func b64(_ s: String) -> String {
            guard let data = Data(base64Encoded: s, options: .ignoreUnknownCharacters),
                  let t = String(data: data, encoding: .utf8) else { return s }
            return t.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        var out: [Programme] = []
        for o in arr {
            let raw = jstr(o["start"])
            var start = jdouble(o["start_timestamp"])
            var end = jdouble(o["stop_timestamp"])
            if start == 0 { start = fmt.date(from: raw)?.timeIntervalSince1970 ?? 0 }
            if end == 0 { end = fmt.date(from: jstr(o["end"]))?.timeIntervalSince1970 ?? 0 }
            if start == 0 || end == 0 { continue }
            out.append(Programme(title: b64(jstr(o["title"])), desc: b64(jstr(o["description"])),
                                 start: Date(timeIntervalSince1970: start), end: Date(timeIntervalSince1970: end),
                                 startRaw: raw, hasArchive: jbool(o["has_archive"])))
        }
        return out.sorted { $0.start < $1.start }
    }
}

// MARK: - M3U

struct M3UResult {
    var liveCats: [Category] = []
    var channels: [Channel] = []
    var vodCats: [Category] = []
    var movies: [Movie] = []
}

enum M3UParser {
    private static let vodExt = [".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm"]
    private static let attr = try! NSRegularExpression(pattern: "([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    static func parse(_ data: Data) -> M3UResult {
        var res = M3UResult()
        let text = String(decoding: data, as: UTF8.self)
        var attrs: [String: String] = [:]
        var name = ""
        var group = ""
        var pending = false
        var n = 0
        var liveSeen = Set<String>()
        var vodSeen = Set<String>()

        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\u{FEFF}", with: "")
            if line.isEmpty || line.hasPrefix("#EXTM3U") { continue }
            if line.hasPrefix("#EXTINF") {
                attrs = attributes(line)
                name = displayName(line) ?? attrs["tvg-name"] ?? ""
                if name.isEmpty { name = attrs["tvg-name"] ?? "Unnamed" }
                group = attrs["group-title"] ?? ""
                pending = true
            } else if line.hasPrefix("#EXTGRP:") {
                if group.isEmpty { group = String(line.dropFirst(8)).trimmingCharacters(in: .whitespaces) }
            } else if line.hasPrefix("#") {
                continue
            } else {
                if !pending {
                    attrs = [:]
                    name = String(line.split(separator: "/").last ?? "")
                    group = ""
                }
                n += 1
                let g = group.isEmpty ? "Uncategorized" : group
                let logo = attrs["tvg-logo"] ?? ""
                let path = (line.split(separator: "?").first.map(String.init) ?? line).lowercased()
                let isVod = path.contains("/movie/") || path.contains("/series/") || vodExt.contains { path.hasSuffix($0) }
                if isVod {
                    if vodSeen.insert(g).inserted { res.vodCats.append(Category(id: g, name: g)) }
                    let ext = path.components(separatedBy: ".").last ?? "mp4"
                    res.movies.append(Movie(id: "m\(n)", name: name, poster: logo, categoryId: g, url: line,
                                            rating: "", ext: ext, added: 0))
                } else {
                    if liveSeen.insert(g).inserted { res.liveCats.append(Category(id: g, name: g)) }
                    res.channels.append(Channel(id: "c\(n)", num: res.channels.count + 1, name: name, logo: logo,
                                                categoryId: g, epgId: attrs["tvg-id"] ?? "", url: line,
                                                hasArchive: false, archiveDays: 0))
                }
                pending = false
                group = ""
            }
        }
        return res
    }

    private static func attributes(_ line: String) -> [String: String] {
        var m: [String: String] = [:]
        let ns = line as NSString
        for r in attr.matches(in: line, range: NSRange(location: 0, length: ns.length)) {
            m[ns.substring(with: r.range(at: 1)).lowercased()] = ns.substring(with: r.range(at: 2))
        }
        return m
    }

    /// Text after the first comma that is outside quotes.
    private static func displayName(_ line: String) -> String? {
        var inQuotes = false
        for (i, ch) in line.enumerated() {
            if ch == "\"" { inQuotes.toggle() } else if ch == "," && !inQuotes {
                let s = String(line.dropFirst(i + 1)).trimmingCharacters(in: .whitespaces)
                return s.isEmpty ? nil : s
            }
        }
        return nil
    }
}
