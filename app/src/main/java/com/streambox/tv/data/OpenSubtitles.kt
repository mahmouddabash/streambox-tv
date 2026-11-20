package com.streambox.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Searches and downloads .srt subtitles from OpenSubtitles.com (free API key in Settings). */
object OpenSubtitles {
    data class Result(val fileId: Long, val language: String, val release: String, val downloads: Int)

    private const val API = "https://api.opensubtitles.com/api/v1"
    private const val UA = "StreamBox v1.0"

    val enabled: Boolean get() = Prefs.openSubtitlesKey.isNotBlank()

    private fun req(url: String) = Request.Builder().url(url)
        .header("Api-Key", Prefs.openSubtitlesKey)
        .header("User-Agent", UA)
        .header("Accept", "application/json")

    suspend fun search(title: String): List<Result> = withContext(Dispatchers.IO) {
        // "Show – S02E05 Name" → query "Show", season 2, episode 5
        val ep = Regex("S(\\d{1,2})E(\\d{1,3})", RegexOption.IGNORE_CASE).find(title)
        val base = if (ep != null) title.substring(0, ep.range.first) else title
        val (q, year) = Tmdb.cleanTitle(base.trim(' ', '–', '-'))
        val b = "$API/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", q)
            .addQueryParameter("languages", Prefs.subtitleLanguages.ifBlank { "en" })
            .addQueryParameter("order_by", "download_count")
        if (year.isNotEmpty() && ep == null) b.addQueryParameter("year", year)
        if (ep != null) {
            b.addQueryParameter("season_number", ep.groupValues[1].toInt().toString())
            b.addQueryParameter("episode_number", ep.groupValues[2].toInt().toString())
        }
        Net.client.newCall(req(b.build().toString()).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("OpenSubtitles rejected the API key")
            if (!r.isSuccessful) throw IOException("OpenSubtitles HTTP ${r.code}")
            val data = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: return@withContext emptyList()
            val out = ArrayList<Result>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val f = a.optJSONArray("files")?.optJSONObject(0) ?: continue
                out.add(Result(f.optLong("file_id"), a.optString("language"), a.optString("release"), a.optInt("download_count")))
            }
            out
        }
    }

    /** Downloads the subtitle and returns the local .srt file. */
    suspend fun download(context: Context, r: Result): File = withContext(Dispatchers.IO) {
        val body = JSONObject().put("file_id", r.fileId).toString().toRequestBody("application/json".toMediaType())
        val link = Net.client.newCall(req("$API/download").post(body).build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(text).optString("message") } catch (_: Exception) { "" }
                throw IOException(msg.ifBlank { "Download refused (HTTP ${resp.code})" })
            }
            JSONObject(text).optString("link")
        }
        if (link.isBlank()) throw IOException("No download link returned")
        val dir = File(context.cacheDir, "subs").apply { mkdirs() }
        val out = File(dir, "${r.fileId}.srt")
        Net.client.newCall(Request.Builder().url(link).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            out.writeBytes(resp.body?.bytes() ?: ByteArray(0))
        }
        out
    }
}
