package com.streambox.tv.data

/** Pure text helpers (no Android), kept here so they can be checked by the automatic tests. */
object Names {
    private val qualityTokens = Regex(
        "(?i)(\\b(4k|uhd|2160p?|fhd|1080[pi]?|hd|720p?|sd|576p?|480p?|hevc|h\\.?26[45]|x26[45]|50fps|60fps|hq|lq|raw|backup|multi)\\b|ᵁᴴᴰ|ᶠᴴᴰ|ᴴᴰ|ˢᴰ|⁴ᴷ|[◉●★✪♛™®]|\\(\\s*\\)|\\[\\s*\\])"
    )

    /** 4 = 4K, 3 = Full HD, 2 = HD (or unknown), 1 = SD. */
    fun rank(name: String): Int {
        val n = name.lowercase()
        return when {
            Regex("\\b(4k|uhd|2160p?)\\b").containsMatchIn(n) || "ᵁᴴᴰ" in name || "⁴ᴷ" in name -> 4
            Regex("\\b(fhd|1080[pi]?)\\b").containsMatchIn(n) || "ᶠᴴᴰ" in name -> 3
            Regex("\\b(sd|576p?|480p?|lq)\\b").containsMatchIn(n) || "ˢᴰ" in name -> 1
            else -> 2
        }
    }

    /** Same channel in different qualities share this key. */
    fun group(name: String): String =
        qualityTokens.replace(name.lowercase(), " ").replace(Regex("[|:_\\-]+\\s*$"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Sound-alike key that works across Arabic and Latin spelling: consonants only, with similar
     * sounds merged ("Saqr" and "صقر" both give "skr", "Mohamed" and "محمد" give "mhmd").
     */
    fun skeleton(s: String): String {
        val ar = mapOf(
            'ا' to "", 'أ' to "", 'إ' to "", 'آ' to "", 'ء' to "", 'ئ' to "", 'ؤ' to "", 'ى' to "", 'ي' to "y", 'و' to "w",
            'ب' to "b", 'ت' to "t", 'ث' to "t", 'ج' to "j", 'ح' to "h", 'خ' to "k", 'د' to "d", 'ذ' to "d", 'ر' to "r",
            'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "", 'غ' to "g",
            'ف' to "f", 'ق' to "k", 'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'ة' to "", 'پ' to "b", 'گ' to "g"
        )
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            val a = ar[ch]
            if (a != null) sb.append(a) else if (ch in 'a'..'z' || ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
            .replace("sh", "s").replace("kh", "k").replace("gh", "g").replace("th", "t").replace("dh", "d").replace("ph", "f")
            .replace('q', 'k').replace('c', 'k').replace('x', 'k').replace('v', 'f').replace('p', 'b').replace('j', 'g').replace('e', 'a')
            .replace(Regex("[aeiouyw]"), "")
            .replace(Regex("(.)\\1+"), "$1")
    }

    /** Every subtitle time in an .srt text moved by [ms] (never below zero). */
    fun shiftSrt(text: String, ms: Long): String {
        val re = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")
        return text.replace(re) { m ->
            val (h, mi, s, f) = m.destructured
            val t = (((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + f.toLong() + ms).coerceAtLeast(0)
            String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
        }
    }
}
