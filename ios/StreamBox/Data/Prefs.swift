import Foundation

/// All persistent state: profiles, settings, favourites, locks, recent lists and resume positions.
final class Prefs {
    static let shared = Prefs()
    private let d = UserDefaults.standard

    static let favLive = "live"
    static let favVod = "vod"
    static let favSeries = "series"

    private init() {
        seedDefaultProfile()
    }

    // MARK: Profiles

    private static let defaultProfile = Profile(
        id: "default", name: "My IPTV", type: Profile.xtream,
        server: "http://mhav1.com:2095", username: "sa0602", password: "01069979654"
    )

    /// Adds the built-in login once per version; deleting it afterwards keeps it deleted.
    private func seedDefaultProfile() {
        let version = 2
        guard d.integer(forKey: "defaultSeedVersion") < version else { return }
        var list = profiles
        if let i = list.firstIndex(where: { $0.id == Prefs.defaultProfile.id }) {
            list[i].server = Prefs.defaultProfile.server
            list[i].username = Prefs.defaultProfile.username
            list[i].password = Prefs.defaultProfile.password
        } else {
            list.append(Prefs.defaultProfile)
        }
        profiles = list
        d.set(version, forKey: "defaultSeedVersion")
    }

    var profiles: [Profile] {
        get {
            guard let data = d.data(forKey: "profiles"),
                  let list = try? JSONDecoder().decode([Profile].self, from: data) else { return [] }
            return list
        }
        set { d.set(try? JSONEncoder().encode(newValue), forKey: "profiles") }
    }

    func save(_ p: Profile) {
        var list = profiles
        if let i = list.firstIndex(where: { $0.id == p.id }) { list[i] = p } else { list.append(p) }
        profiles = list
    }

    func delete(_ p: Profile) {
        profiles = profiles.filter { $0.id != p.id }
    }

    var lastProfileId: String {
        get { d.string(forKey: "lastProfile") ?? "" }
        set { d.set(newValue, forKey: "lastProfile") }
    }

    /// Reopen the last profile and tab on launch.
    var resumeOnLaunch: Bool {
        get { d.object(forKey: "resumeOnLaunch") as? Bool ?? true }
        set { d.set(newValue, forKey: "resumeOnLaunch") }
    }

    var lastTab: String {
        get { d.string(forKey: "lastTab") ?? "home" }
        set { d.set(newValue, forKey: "lastTab") }
    }

    var use24h: Bool {
        get { d.object(forKey: "use24h") as? Bool ?? true }
        set { d.set(newValue, forKey: "use24h") }
    }

    var cacheHours: Int {
        get { let v = d.integer(forKey: "cacheHours"); return v > 0 ? v : 12 }
        set { d.set(newValue, forKey: "cacheHours") }
    }

    var userAgent: String {
        get { d.string(forKey: "userAgent") ?? "" }
        set { d.set(newValue, forKey: "userAgent") }
    }

    // MARK: Parental control

    var pin: String {
        get { d.string(forKey: "pin") ?? "" }
        set { d.set(newValue, forKey: "pin") }
    }

    var parentalEnabled: Bool { !pin.isEmpty }

    func lockedCategories(_ profileId: String) -> Set<String> { set("lock_\(profileId)") }

    @discardableResult
    func toggleLocked(_ profileId: String, _ key: String) -> Bool {
        var s = lockedCategories(profileId)
        let now = !s.contains(key)
        if now { s.insert(key) } else { s.remove(key) }
        d.set(Array(s), forKey: "lock_\(profileId)")
        return now
    }

    // MARK: Favourites

    private func set(_ key: String) -> Set<String> { Set(d.stringArray(forKey: key) ?? []) }

    func favourites(_ profileId: String, _ type: String) -> Set<String> { set("fav_\(type)_\(profileId)") }

    func isFavourite(_ profileId: String, _ type: String, _ id: String) -> Bool {
        favourites(profileId, type).contains(id)
    }

    @discardableResult
    func toggleFavourite(_ profileId: String, _ type: String, _ id: String) -> Bool {
        var s = favourites(profileId, type)
        let now = !s.contains(id)
        if now { s.insert(id) } else { s.remove(id) }
        d.set(Array(s), forKey: "fav_\(type)_\(profileId)")
        return now
    }

    // MARK: Recent lists

    func recent(_ name: String) -> [String] { d.stringArray(forKey: name) ?? [] }

    func pushRecent(_ name: String, _ id: String, max: Int = 30) {
        var list = recent(name).filter { $0 != id }
        list.insert(id, at: 0)
        d.set(Array(list.prefix(max)), forKey: name)
    }

    // MARK: Resume positions

    func position(_ key: String) -> Double { key.isEmpty ? 0 : d.double(forKey: "pos_\(key)") }
    func duration(_ key: String) -> Double { key.isEmpty ? 0 : d.double(forKey: "dur_\(key)") }

    func setPosition(_ key: String, _ seconds: Double, duration: Double = 0) {
        guard !key.isEmpty else { return }
        if seconds <= 0 {
            d.removeObject(forKey: "pos_\(key)")
            d.removeObject(forKey: "posAt_\(key)")
            d.removeObject(forKey: "dur_\(key)")
        } else {
            d.set(seconds, forKey: "pos_\(key)")
            d.set(Date().timeIntervalSince1970, forKey: "posAt_\(key)")
            if duration > 0 { d.set(duration, forKey: "dur_\(key)") }
        }
    }

    /// IDs with a resume position under `prefix`, most recently watched first.
    func resumeIds(_ prefix: String) -> [String] {
        let all = d.dictionaryRepresentation()
        let full = "pos_" + prefix
        return all.keys.filter { $0.hasPrefix(full) }
            .sorted { (all["posAt_" + $0.dropFirst(4)] as? Double ?? 0) > (all["posAt_" + $1.dropFirst(4)] as? Double ?? 0) }
            .map { String($0.dropFirst(full.count)) }
    }

    func clearResumePositions() {
        for k in d.dictionaryRepresentation().keys where k.hasPrefix("pos_") || k.hasPrefix("posAt_") || k.hasPrefix("dur_") {
            d.removeObject(forKey: k)
        }
    }

    // MARK: Last category per screen

    func lastCategory(_ profileId: String, _ screen: String) -> String {
        d.string(forKey: "lastCat_\(screen)_\(profileId)") ?? ""
    }

    func setLastCategory(_ profileId: String, _ screen: String, _ id: String) {
        d.set(id, forKey: "lastCat_\(screen)_\(profileId)")
    }
}
