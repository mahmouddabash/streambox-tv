package com.streambox.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Streaming XMLTV reader. Keeps only programmes for the channel ids we actually have. */
object XmltvParser {

    fun parse(file: File, wanted: Set<String>, fromMs: Long, toMs: Long): Map<String, List<Programme>> {
        val out = HashMap<String, ArrayList<Programme>>()
        openStream(file).use { stream ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(stream, null)

            var channel = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var inProgramme = false
            var keep = false

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            inProgramme = true
                            channel = p.getAttributeValue(null, "channel") ?: ""
                            keep = wanted.contains(channel)
                            if (keep) {
                                start = time(p.getAttributeValue(null, "start"))
                                stop = time(p.getAttributeValue(null, "stop"))
                                keep = stop > fromMs && start < toMs
                            }
                            title = ""
                            desc = ""
                        }
                        "title" -> if (inProgramme && keep) title = safeText(p)
                        "desc" -> if (inProgramme && keep) desc = safeText(p)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    if (keep) out.getOrPut(channel) { ArrayList() }.add(Programme(title, desc, start, stop, "", false))
                    inProgramme = false
                    keep = false
                }
                ev = p.next()
            }
        }
        for (list in out.values) list.sortBy { it.startMs }
        return out
    }

    private fun safeText(p: XmlPullParser): String = try {
        p.nextText() ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun openStream(file: File): InputStream {
        val bis = BufferedInputStream(file.inputStream(), 64 * 1024)
        bis.mark(4)
        val b1 = bis.read()
        val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis
    }

    private val withZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val noZone = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val v = s.trim()
        return try {
            if (v.length > 14 && v.contains(' ')) withZone.parse(v)?.time ?: 0L
            else noZone.parse(v.take(14))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
