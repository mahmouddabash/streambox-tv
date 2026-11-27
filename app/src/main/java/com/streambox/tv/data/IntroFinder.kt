package com.streambox.tv.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Finds a series' opening titles by comparing the first minutes of two downloaded episodes: the
 * stretch where both show the same pictures at the same time is the intro. Only files on this device
 * can be compared (reading pictures from a stream would cost the data of watching it).
 */
object IntroFinder {
    const val STEP_MS = 2_000L
    private const val SCAN_MS = 5 * 60_000L

    data class Found(val startMs: Long, val endMs: Long)

    /** Compares two episodes; null when no shared opening was found. */
    fun find(a: File, b: File, stillWanted: () -> Boolean = { true }): Found? {
        val ha = hashes(a, stillWanted) ?: return null
        val hb = hashes(b, stillWanted) ?: return null
        return match(ha, hb, STEP_MS)
    }

    /**
     * [a] and [b] are picture fingerprints taken every [stepMs]. Looks for the longest run of matching
     * pictures that sits at (nearly) the same time in both, at least 15 s long, made of real pictures
     * (plain black or one-colour frames match anything, so they don't count towards the length).
     */
    fun match(a: List<Long?>, b: List<Long?>, stepMs: Long): Found? {
        val maxShift = (4_000L / stepMs).toInt()
        var best: Pair<Int, Int>? = null // first, last index in a
        var bestLen = 0
        for (d in -maxShift..maxShift) {
            var start = -1
            var real = 0
            var misses = 0
            var i = 0
            while (i <= a.size) {
                val j = i + d
                val ha = a.getOrNull(i)
                val hb = if (j in b.indices) b[j] else null
                val same = ha != null && hb != null && java.lang.Long.bitCount(ha xor hb) <= 10
                if (same && i < a.size) {
                    if (start < 0) { start = i; real = 0 }
                    if (informative(ha!!)) real++
                    misses = 0
                } else if (start >= 0 && misses < 1 && i < a.size) {
                    misses++ // one odd frame (a logo, a fade) doesn't end the run
                } else {
                    if (start >= 0) {
                        val last = i - 1 - misses
                        val len = last - start + 1
                        if (real >= 5 && len * stepMs >= 15_000L && len > bestLen) { bestLen = len; best = start to last }
                    }
                    start = -1; misses = 0
                }
                i++
            }
        }
        val (s, e) = best ?: return null
        return Found(s * stepMs, (e + 1) * stepMs)
    }

    /** Frames with almost no detail (black, one colour). */
    fun informative(h: Long): Boolean = java.lang.Long.bitCount(h) in 6..58

    /** 64-bit "difference hash" of a picture: each bit says whether a pixel is brighter than its neighbour. */
    fun dHash(gray: IntArray): Long {
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = h shl 1
            if (gray[y * 9 + x] > gray[y * 9 + x + 1]) h = h or 1L
        }
        return h
    }

    private fun hashes(f: File, stillWanted: () -> Boolean): List<Long?>? {
        if (!f.exists()) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val until = minOf(SCAN_MS, dur / 2)
            val out = ArrayList<Long?>()
            var t = 0L
            while (t < until) {
                if (!stillWanted()) return null
                val bm = try { r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                out.add(bm?.let { hashOf(it) })
                bm?.recycle()
                t += STEP_MS
            }
            if (out.count { it != null } < out.size / 2) null else out
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun hashOf(bm: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(bm, 9, 8, true)
        val px = IntArray(72)
        s.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (s !== bm) s.recycle()
        val gray = IntArray(72) { val c = px[it]; ((c shr 16 and 0xff) * 3 + (c shr 8 and 0xff) * 6 + (c and 0xff)) / 10 }
        return dHash(gray)
    }
}
