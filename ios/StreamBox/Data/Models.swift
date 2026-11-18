import Foundation

struct Profile: Codable, Identifiable, Hashable {
    static let xtream = "xtream"
    static let m3u = "m3u"

    var id: String
    var name: String
    var type: String
    var server: String = ""
    var username: String = ""
    var password: String = ""
    var m3uUrl: String = ""
    var epgUrl: String = ""

    var isXtream: Bool { type == Profile.xtream }
}

struct AccountInfo {
    var status = ""
    var expiry: Date?
    var maxConnections = ""
    var activeConnections = ""
}

struct Category: Identifiable, Hashable {
    let id: String
    let name: String
}

struct Channel: Identifiable, Hashable {
    let id: String
    let num: Int
    let name: String
    let logo: String
    let categoryId: String
    let epgId: String
    /// Direct URL for M3U playlists; empty for Xtream (built on demand).
    let url: String
    let hasArchive: Bool
    let archiveDays: Int
}

struct Movie: Identifiable, Hashable {
    let id: String
    let name: String
    let poster: String
    let categoryId: String
    let url: String
    let rating: String
    let ext: String
    let added: Double
}

struct Series: Identifiable, Hashable {
    let id: String
    let name: String
    let poster: String
    let categoryId: String
    let plot: String
    let genre: String
    let cast: String
    let releaseDate: String
    let rating: String
    let added: Double
}

struct Episode: Identifiable, Hashable {
    let id: String
    let title: String
    let season: Int
    let num: Int
    let url: String
    let plot: String
    let duration: String
    let image: String
}

struct Programme: Identifiable, Hashable {
    var id: String { "\(start.timeIntervalSince1970)-\(title)" }
    let title: String
    let desc: String
    let start: Date
    let end: Date
    /// "yyyy-MM-dd HH:mm:ss" exactly as the server sent it (used for catch-up URLs).
    let startRaw: String
    let hasArchive: Bool

    var isNow: Bool { let n = Date(); return start <= n && n < end }
    var isPast: Bool { end <= Date() }
}

struct MovieInfo {
    var plot = ""
    var cast = ""
    var director = ""
    var genre = ""
    var releaseDate = ""
    var duration = ""
    var rating = ""
    var backdrop = ""
}

/// One entry of the playback queue.
struct PlayItem: Identifiable, Hashable {
    var id: String { url }
    /// Key used to store the resume position; empty = don't remember.
    let key: String
    let title: String
    let url: String
    let isLive: Bool
    var channelId: String = ""
    var poster: String = ""
}

/// What the full-screen player is showing.
struct PlaybackRequest: Identifiable {
    let id = UUID()
    let items: [PlayItem]
    let index: Int
    var fromStart = false
}

enum ContentSection: String {
    case live, vod, series
}

/// Rating text like "★ 7.4", or empty.
func ratingText(_ r: String) -> String {
    guard let v = Double(r), v > 0 else { return "" }
    return "★ " + (v >= 10 ? String(Int(v)) : String(format: "%.1f", v))
}
