package com.streambox.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Looks at the first few KB a stream URL really returns, so the player can recover when the server
 * sends something other than what the URL's extension promises (an HLS playlist behind a .ts/.mp4 link,
 * a redirect, a text/HTML error page, an unsupported container…).
 */
object StreamProbe {

    enum class Kind(val label: String) {
        HLS("HLS playlist"), DASH("DASH manifest"), TS("MPEG-TS"), MP4("MP4"), MKV("MKV/WebM"),
        AVI("AVI"), FLV("FLV"), ASF("WMV/ASF"), RM("RealMedia"), MPEG_PS("MPEG-PS"),
        TEXT("text page"), EMPTY("empty response"), UNKNOWN("unknown format")
    }

    data class Result(
        val kind: Kind,
        val finalUrl: String,
        val httpCode: Int,
        val contentType: String,
        /** Readable start of the body when the server answered with text instead of video. */
        val message: String = ""
    )

    suspend fun probe(url: String): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Range", "bytes=0-8191").build()
        Net.client.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            val head = ByteArray(8192)
            var n = 0
            resp.body?.byteStream()?.use { s ->
                while (n < head.size) {
                    val r = try { s.read(head, n, head.size - n) } catch (_: IOException) { -1 }
                    if (r <= 0) break
                    n += r
                }
            }
            val bytes = head.copyOf(n)
            val kind = classify(bytes, ct, finalUrl)
            val msg = if (kind == Kind.TEXT) readable(bytes) else ""
            Result(kind, finalUrl, resp.code, ct, msg)
        }
    }

    private fun classify(b: ByteArray, ct: String, url: String): Kind {
        if (b.isEmpty()) return Kind.EMPTY
        val text = String(b, 0, minOf(b.size, 512), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\r', '\n', '\t', 'ï', '»', '¿')
        fun at(i: Int, vararg sig: Int) = b.size >= i + sig.size && sig.indices.all { (b[i + it].toInt() and 0xFF) == sig[it] }
        fun ascii(i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it].toInt().toChar() == s[it] }

        return when {
            text.startsWith("#EXTM3U") || ct.contains("mpegurl") -> Kind.HLS
            text.startsWith("<?xml") && text.contains("<MPD", true) || ct.contains("dash+xml") -> Kind.DASH
            ascii(4, "ftyp") || ascii(4, "moov") || ascii(4, "mdat") || ascii(4, "free") || ascii(4, "styp") -> Kind.MP4
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> Kind.MKV
            ascii(0, "RIFF") && ascii(8, "AVI") -> Kind.AVI
            ascii(0, "FLV") -> Kind.FLV
            at(0, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11) -> Kind.ASF
            ascii(0, ".RMF") -> Kind.RM
            at(0, 0x00, 0x00, 0x01, 0xBA) -> Kind.MPEG_PS
            looksLikeTs(b) -> Kind.TS
            ct.startsWith("text/") || ct.contains("json") || ct.contains("html") ||
                text.startsWith("<") || text.startsWith("{") || isMostlyText(b) -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
    }

    /** MPEG-TS: 0x47 sync byte every 188 (or 192/204) bytes, possibly after some leading junk. */
    private fun looksLikeTs(b: ByteArray): Boolean {
        for (size in intArrayOf(188, 192, 204)) {
            for (start in 0 until minOf(size, b.size)) {
                var hits = 0
                var i = start
                while (i < b.size && b[i].toInt() == 0x47) {
                    hits++
                    i += size
                }
                if (hits >= 3) return true
            }
        }
        return false
    }

    private fun isMostlyText(b: ByteArray): Boolean {
        val sample = b.copyOf(minOf(b.size, 512))
        val printable = sample.count { val c = it.toInt() and 0xFF; c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0xC0 }
        return printable >= sample.size * 0.9
    }

    private fun readable(b: ByteArray): String {
        val s = String(b, Charsets.UTF_8)
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (s.length > 160) s.take(160) + "…" else s
    }
}
