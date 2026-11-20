import Foundation
import CryptoKit

/// Keeps the built-in login out of plain text and checks the profile password without storing it.
enum Secret {
    private static let key = Array("StreamBox#2026~tv".utf8)
    static let defaultServer = "OwAGFVtCbQIQQkQBHFURGUxhREtQ"
    static let defaultUser = "IBVCU1Ff"
    static let defaultPass = "Y0VCU1hUdVZOFgY="
    private static let passwordHash = "a4f78b341e985db84d1dba705c6cfba390e141d17bb9605dbd34696713377cd1"

    static func reveal(_ s: String) -> String {
        guard let d = Data(base64Encoded: s) else { return "" }
        let bytes = d.enumerated().map { $0.element ^ key[$0.offset % key.count] }
        return String(decoding: bytes, as: UTF8.self)
    }

    static func checkPassword(_ input: String) -> Bool {
        let digest = SHA256.hash(data: Data("streambox:\(input.trimmingCharacters(in: .whitespaces))".utf8))
        return digest.map { String(format: "%02x", $0) }.joined() == passwordHash
    }
}
