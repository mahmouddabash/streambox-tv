package com.streambox.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Optional enrichment from The Movie Database (free API key in Settings):
 * HD backdrops, cast with photos and trailers when the provider has none.
 */
object Tmdb {
    data class Person(val name: String, val role: String, val photo: String)
    data class Details(
        val backdrop: String = "",
        val overview: String = "",
        val cast: List<Person> = emptyList(),
        val trailer: String = "",
        val rating: String = ""
    )

    private const val IMG = "https://image.tmdb.org/t/p/"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Details>()

    val enabled: Boolean get() = Prefs.tmdbKey.isNotBlank()

    /** Removes provider decorations like "AR - ", "[4K]", "(2023)" so the title can be searched. */
    fun cleanTitle(name: String): Pair<String, String> {
        var t = name
        val year = Regex("\\b(19|20)\\d{2}\\b").findAll(t).lastOrNull()?.value ?: ""
        t = t.replace(Regex("^[A-Z]{2,3}\\s*[-|:]\\s*"), "")
            .replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
            .replace(Regex("\\b(4K|UHD|FHD|HD|SD|1080p|720p|HEVC|x265|MULTI|VOSTFR|Dubbed|Subbed)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
            .replace(Regex("\\s+"), " ").trim(' ', '-', '|', ':')
        return t to year
    }

    suspend fun details(isMovie: Boolean, tmdbId: String, title: String): Details? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val type = if (isMovie) "movie" else "tv"
        val key = "$type:${tmdbId.ifBlank { title }}"
        cache[key]?.let { return@withContext it }
        try {
            var id = tmdbId.filter { it.isDigit() }
            if (id.isEmpty()) {
                val (q, year) = cleanTitle(title)
                if (q.isBlank()) return@withContext null
                val b = "https://api.themoviedb.org/3/search/$type".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", Prefs.tmdbKey)
                    .addQueryParameter("query", q)
                if (year.isNotEmpty()) b.addQueryParameter(if (isMovie) "year" else "first_air_date_year", year)
                val res = JSONObject(get(b.build().toString())).optJSONArray("results")
                id = res?.optJSONObject(0)?.optString("id", "") ?: ""
                if (id.isEmpty()) return@withContext null
            }
            val url = "https://api.themoviedb.org/3/$type/$id".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", Prefs.tmdbKey)
                .addQueryParameter("append_to_response", "credits,videos")
                .build().toString()
            val o = JSONObject(get(url))
            val cast = ArrayList<Person>()
            o.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 15)) {
                    val c = arr.optJSONObject(i) ?: continue
                    val photo = c.optString("profile_path", "")
                    cast.add(Person(c.optString("name"), c.optString("character"),
                        if (photo.isNotBlank() && photo != "null") "${IMG}w185$photo" else ""))
                }
            }
            var trailer = ""
            o.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    if (v.optString("site") == "YouTube" && v.optString("type") in setOf("Trailer", "Teaser")) {
                        trailer = v.optString("key")
                        if (v.optString("type") == "Trailer") break
                    }
                }
            }
            val bd = o.optString("backdrop_path", "")
            val vote = o.optDouble("vote_average", 0.0)
            val d = Details(
                backdrop = if (bd.isNotBlank() && bd != "null") "${IMG}w1280$bd" else "",
                overview = o.optString("overview", ""),
                cast = cast,
                trailer = trailer,
                rating = if (vote > 0) String.format(java.util.Locale.US, "%.1f", vote) else ""
            )
            cache[key] = d
            d
        } catch (_: Exception) {
            null
        }
    }

    private fun get(url: String): String =
        Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("TMDB HTTP ${r.code}")
            r.body?.string() ?: ""
        }
}
