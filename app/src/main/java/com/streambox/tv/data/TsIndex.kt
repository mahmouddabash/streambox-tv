package com.streambox.tv.data

import java.io.File

/**
 * Builds a seek index for a downloaded MPEG-TS file: finds every full picture (keyframe) and writes
 * "<file>.m3u8", an HLS playlist of byte ranges that each start on one. Playing that playlist instead
 * of the raw file makes every jump land exactly on a picture, so it never freezes.
 */
object TsIndex {
    private const val PKT = 188
    /** Marks playlists made by this keyframe scan (older builds wrote less precise ones). */
    const val MARK = "#STREAMBOX-KEYFRAMES"
    private const val MIN_GAP = 2L * 90_000 // at least 2 s between index points

    fun indexFile(f: File) = File(f.path + ".m3u8")

    /** True when [f] has an up-to-date keyframe index. */
    fun hasIndex(f: File): Boolean = try {
        indexFile(f).let { it.exists() && it.bufferedReader().use { r -> r.readLine(); r.readLine() } == MARK }
    } catch (_: Exception) {
        false
    }

    /** True when [f] is MPEG-TS (starts with the 0x47 sync byte twice, 188 bytes apart). */
    fun isTs(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(PKT + 1); it.read(b) == b.size && b[0] == 0x47.toByte() && b[PKT] == 0x47.toByte() }
    } catch (_: Exception) {
        false
    }

    /** Returns true when an index was written. */
    fun build(f: File, stillWanted: () -> Boolean = { true }): Boolean {
        if (!isTs(f)) return false
        val starts = ArrayList<Long>()
        val times = ArrayList<Long>()
        var lastPat = -1L
        var videoPid = -1
        var lastPts = -1L
        var codec = 0 // 1 = H.264, 2 = HEVC
        var lastChosen = Long.MIN_VALUE
        var prev = Long.MIN_VALUE
        var wrap = 0L
        var off = 0L
        val buf = ByteArray(PKT * 4096)
        f.inputStream().buffered(1 shl 20).use { input ->
            while (true) {
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n < PKT) break
                if (!stillWanted()) return false
                var base = 0
                while (base + PKT <= n) {
                    if (buf[base] != 0x47.toByte()) return false
                    val b1 = u(buf[base + 1]); val b2 = u(buf[base + 2]); val b3 = u(buf[base + 3])
                    val pid = ((b1 and 0x1f) shl 8) or b2
                    val pusi = (b1 and 0x40) != 0
                    val afc = (b3 shr 4) and 3
                    var q = 4
                    var rai = false
                    if (afc == 2 || afc == 3) {
                        val al = u(buf[base + 4])
                        if (al > 0) rai = (u(buf[base + 5]) and 0x40) != 0
                        q = 5 + al
                    }
                    if (afc != 2 && q < PKT) {
                        if (pid == 0) lastPat = off
                        val p = base + q
                        if (pusi && q + 9 < PKT && buf[p] == 0.toByte() && buf[p + 1] == 0.toByte() && buf[p + 2] == 1.toByte()) {
                            val sid = u(buf[p + 3])
                            if (sid in 0xE0..0xEF) {
                                if (videoPid == -1) videoPid = pid
                                if (pid == videoPid) {
                                    val flags = u(buf[p + 7])
                                    val hl = u(buf[p + 8])
                                    var pts = -1L
                                    if ((flags and 0x80) != 0 && q + 13 < PKT) {
                                        pts = (((u(buf[p + 9]) shr 1) and 7).toLong() shl 30) or
                                            (u(buf[p + 10]).toLong() shl 22) or
                                            ((u(buf[p + 11]) shr 1).toLong() shl 15) or
                                            (u(buf[p + 12]).toLong() shl 7) or
                                            (u(buf[p + 13]) shr 1).toLong()
                                        pts += wrap
                                        if (prev != Long.MIN_VALUE && pts < prev - (1L shl 32)) {
                                            wrap += 1L shl 33
                                            pts += 1L shl 33
                                        }
                                        prev = pts
                                        if (pts > lastPts) lastPts = pts
                                    }
                                    var key = rai
                                    var i = p + 9 + hl
                                    val end = base + PKT
                                    while (!key && i + 3 < end) {
                                        if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                                            val h = u(buf[i + 3])
                                            if (codec == 0) {
                                                if (h == 0x40 && i + 4 < end && buf[i + 4] == 1.toByte()) codec = 2
                                                else if ((h and 0x80) == 0 && (h and 0x1f) == 7) codec = 1
                                            }
                                            if ((h and 0x80) == 0) {
                                                if (codec == 1 && ((h and 0x1f) == 5 || (h and 0x1f) == 7)) key = true
                                                val t = (h shr 1) and 0x3f
                                                if (codec == 2 && (t in 16..21 || t == 32 || t == 33)) key = true
                                            }
                                            i += 3
                                        } else i++
                                    }
                                    if (key && pts >= 0 && (lastChosen == Long.MIN_VALUE || pts - lastChosen >= MIN_GAP)) {
                                        val st = if (starts.isEmpty()) 0L
                                        else if (lastPat > starts.last() && off - lastPat < 2_000_000) lastPat else off
                                        starts.add(st)
                                        times.add(pts)
                                        lastChosen = pts
                                    }
                                }
                            }
                        }
                    }
                    off += PKT
                    base += PKT
                }
                if (n < buf.size) break
            }
        }
        if (starts.size < 2) return false
        val len = f.length()
        val sb = StringBuilder()
        var maxDur = 1.0
        for (k in starts.indices) {
            val dur = if (k < starts.size - 1) (times[k + 1] - times[k]) / 90_000.0 else (lastPts - times[k]) / 90_000.0 + 0.04
            val bytes = if (k < starts.size - 1) starts[k + 1] - starts[k] else len - starts[k]
            if (dur <= 0 || bytes <= 0) return false
            if (dur > maxDur) maxDur = dur
            sb.append(String.format(java.util.Locale.US, "#EXTINF:%.3f,\n", dur))
            sb.append("#EXT-X-BYTERANGE:$bytes@${starts[k]}\n").append(f.name).append('\n')
        }
        val head = "#EXTM3U\n$MARK\n#EXT-X-VERSION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
            "#EXT-X-TARGETDURATION:${kotlin.math.ceil(maxDur).toInt()}\n#EXT-X-MEDIA-SEQUENCE:0\n"
        val tmp = File(f.path + ".m3u8.tmp")
        tmp.writeText(head + sb + "#EXT-X-ENDLIST\n")
        return tmp.renameTo(indexFile(f))
    }

    private fun u(b: Byte) = b.toInt() and 0xff
}
