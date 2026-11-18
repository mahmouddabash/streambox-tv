import Foundation

struct HTTPError: LocalizedError {
    let code: Int
    var errorDescription: String? { "Server returned HTTP \(code)" }
}

enum Net {
    static let defaultUA = "StreamBox/1.0 (iOS)"

    /// Player identities that IPTV panels commonly accept; tried in order when the default is refused.
    static let fallbackUAs = [
        "VLC/3.0.20 LibVLC/3.0.20",
        "okhttp/4.12.0",
        "IPTVSmartersPro",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    ]

    static var userAgent: String {
        let ua = Prefs.shared.userAgent
        return ua.isEmpty ? defaultUA : ua
    }

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 25
        c.timeoutIntervalForResource = 180
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    static func data(_ urlString: String) async throws -> Data {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw HTTPError(code: http.statusCode)
        }
        return data
    }

    /// GET that retries with common player User-Agents when the server refuses ours, remembering the one that works.
    static func dataAcceptingUA(_ url: String) async throws -> Data {
        let refused: Set<Int> = [401, 403, 406, 451]
        do {
            return try await data(url)
        } catch let e as HTTPError where refused.contains(e.code) {
            let saved = Prefs.shared.userAgent
            for ua in fallbackUAs where ua != saved {
                Prefs.shared.userAgent = ua
                do {
                    return try await data(url)
                } catch let e2 as HTTPError where refused.contains(e2.code) {
                    continue
                } catch {
                    Prefs.shared.userAgent = saved
                    throw error
                }
            }
            Prefs.shared.userAgent = saved
            throw NSError(domain: "StreamBox", code: 403, userInfo: [NSLocalizedDescriptionKey:
                "The server refused this app (HTTP 403). Check the server URL and port, or ask your provider whether this player is blocked."])
        }
    }

    static func readable(_ error: Error) -> String {
        if let u = error as? URLError {
            switch u.code {
            case .notConnectedToInternet: return "No internet connection"
            case .timedOut: return "The server did not answer in time"
            case .cannotFindHost, .dnsLookupFailed: return "Server address not found. Check the URL"
            case .cannotConnectToHost: return "Could not connect to the server. Check the URL and port"
            case .secureConnectionFailed, .serverCertificateUntrusted: return "Secure connection failed. Try http:// instead of https://"
            default: break
            }
        }
        return error.localizedDescription
    }
}

/// Loose JSON helpers: Xtream panels mix strings, numbers and nulls freely.
func jstr(_ v: Any?) -> String {
    switch v {
    case let s as String: return s
    case let n as NSNumber: return n.stringValue
    case let a as [Any]: return a.first.map { jstr($0) } ?? ""
    default: return ""
    }
}

func jstr(_ o: [String: Any], _ keys: String...) -> String {
    for k in keys {
        let s = jstr(o[k])
        if !s.isEmpty { return s }
    }
    return ""
}

func jdouble(_ v: Any?) -> Double { Double(jstr(v)) ?? 0 }
func jint(_ v: Any?) -> Int { Int(jstr(v)) ?? Int(jdouble(v)) }
func jbool(_ v: Any?) -> Bool {
    let s = jstr(v).lowercased()
    return s == "1" || s == "true"
}
