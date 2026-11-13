package com.streambox.tv.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for the currently opened profile: catalogue, EPG, URLs,
 * parental locks and the playback queue.
 */
object Repo {
    const val CAT_ALL = "__all"
    const val CAT_FAV = "__fav"
    const val CAT_RECENT = "__recent"

    const val SECTION_LIVE = "live"
    const val SECTION_VOD = "vod"
    const val SECTION_SERIES = "series"

    var profile: Profile? = null
        private set
    var account: AccountInfo = AccountInfo()
        private set
    var loaded = false
        private set

    var liveCats: List<Category> = emptyList()
        private set
    var vodCats: List<Category> = emptyList()
        private set
    var seriesCats: List<Category> = emptyList()
        private set
    var channels: List<Channel> = emptyList()
        private set
    var movies: List<Movie> = emptyList()
        private set
    var series: List<Series> = emptyList()
        private set

    private var channelById: Map<String, Channel> = emptyMap()
    private var movieById: Map<String, Movie> = emptyMap()
    private var seriesById: Map<String, Series> = emptyMap()

    private var m3uEpgUrl = ""
    private var xmltv: Map<String, List<Programme>>? = null
    private val epgCache = ConcurrentHashMap<String, Pair<Long, List<Programme>>>()

    /** Playback queue shared with PlayerActivity (lists can be far too big for an Intent). */
    var queue: List<PlayItem> = emptyList()
    var queueIndex: Int = 0

    private val ADULT = Regex("(adult|xxx|18\\+|\\+18|porn|for adults)", RegexOption.IGNORE_CASE)

    val profileId: String get() = profile?.id ?: ""

    fun channel(id: String): Channel? = channelById[id]
    fun movie(id: String): Movie? = movieById[id]
    fun seriesItem(id: String): Series? = seriesById[id]

    // ------------------------------------------------------------------ URLs

    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        for (marker in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
            val i = s.indexOf(marker)
            if (i > 0) s = s.substring(0, i)
        }
        return s.trimEnd('/')
    }

    private fun api(p: Profile, action: String? = null, extra: String = ""): String {
        val sb = StringBuilder(p.server)
            .append("/player_api.php?username=").append(Uri.encode(p.username))
            .append("&password=").append(Uri.encode(p.password))
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    fun urlFor(c: Channel): String {
        if (c.url.isNotEmpty()) return c.url
        val p = profile ?: return ""
        return "${p.server}/live/${p.username}/${p.password}/${c.id}.${Prefs.streamFormat}"
    }

    fun urlFor(m: Movie): String {
        if (m.url.isNotEmpty()) return m.url
        val p = profile ?: return ""
        return "${p.server}/movie/${p.username}/${p.password}/${m.id}.${m.ext.ifEmpty { "mp4" }}"
    }

    /** Xtream timeshift URL: /timeshift/user/pass/{minutes}/{yyyy-MM-dd:HH-mm}/{streamId}.ts */
    fun catchupUrl(c: Channel, pr: Programme): String {
        val p = profile ?: return ""
        val minutes = ((pr.endMs - pr.startMs) / 60000L).coerceAtLeast(1L)
        val start = if (pr.startRaw.length >= 16) {
            pr.startRaw.substring(0, 10) + ":" + pr.startRaw.substring(11, 16).replace(':', '-')
        } else {
            SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(pr.startMs)
        }
        return "${p.server}/timeshift/${p.username}/${p.password}/$minutes/$start/${c.id}.ts"
    }

    // ------------------------------------------------------------------ Auth

    /** Checks the credentials. Throws IOException with a readable message on failure. */
    suspend fun authenticate(p: Profile): AccountInfo = withContext(Dispatchers.IO) {
        if (!p.isXtream) return@withContext AccountInfo(status = "Playlist")
        val body = Net.getString(api(p))
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("This server did not answer like an Xtream Codes panel")
        }
        val u = root.optJSONObject("user_info") ?: throw IOException("Login failed: no account info returned")
        val auth = u.optString("auth", "1")
        if (auth == "0" || auth.equals("false", true)) throw IOException("Wrong username or password")
        val status = u.optString("status", "")
        val exp = u.optString("exp_date", "").toLongOrNull() ?: 0L
        AccountInfo(
            status = status,
            expiryMs = exp * 1000L,
            maxConnections = u.optString("max_connections", ""),
            activeConnections = u.optString("active_cons", "")
        )
    }

    // ------------------------------------------------------------------ Loading

    fun close() {
        profile = null
        loaded = false
        liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
        channels = emptyList(); movies = emptyList(); series = emptyList()
        channelById = emptyMap(); movieById = emptyMap(); seriesById = emptyMap()
        xmltv = null
        m3uEpgUrl = ""
        epgCache.clear()
        queue = emptyList()
    }

    private fun cacheDir(context: Context, p: Profile): File =
        File(context.cacheDir, "data_${p.id}").apply { mkdirs() }

    fun clearCache(context: Context) {
        context.cacheDir.listFiles()?.forEach { if (it.name.startsWith("data_")) it.deleteRecursively() }
        epgCache.clear()
        xmltv = null
    }

    /** Returns a fresh-enough cached file, downloading when needed. Falls back to stale data offline. */
    private fun fetch(dir: File, name: String, url: String, force: Boolean): File {
        val f = File(dir, name)
        val maxAge = Prefs.cacheHours * 3600_000L
        val fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < maxAge
        if (fresh && !force) return f
        try {
            Net.download(url, f)
        } catch (e: Exception) {
            if (f.exists() && f.length() > 0) return f
            throw e
        }
        return f
    }

    suspend fun load(context: Context, p: Profile, force: Boolean, progress: (String) -> Unit) {
        val app = context.applicationContext
        suspend fun say(s: String) = withContext(Dispatchers.Main) { progress(s) }

        withContext(Dispatchers.IO) {
            val dir = cacheDir(app, p)
            if (p.isXtream) {
                say("Signing in…")
                val acc = try {
                    authenticate(p)
                } catch (e: IOException) {
                    // Allow offline start from cache, but surface real login errors.
                    if (File(dir, "live_streams.json").exists() && e.message?.contains("Wrong") != true) AccountInfo(status = "Offline")
                    else throw e
                }

                say("Loading live channels…")
                val lc = readCats(fetch(dir, "live_cats.json", api(p, "get_live_categories"), force))
                val ch = ArrayList<Channel>()
                fetch(dir, "live_streams.json", api(p, "get_live_streams"), force).inputStream().use { ins ->
                    JsonStream.readObjects(ins) { o ->
                        val id = o["stream_id"] ?: return@readObjects
                        ch.add(
                            Channel(
                                id = id,
                                num = o["num"]?.toIntOrNull() ?: (ch.size + 1),
                                name = o["name"] ?: "",
                                logo = o["stream_icon"] ?: "",
                                categoryId = o["category_id"] ?: "",
                                epgId = o["epg_channel_id"] ?: "",
                                url = "",
                                hasArchive = o["tv_archive"] == "1",
                                archiveDays = o["tv_archive_duration"]?.toIntOrNull() ?: 0
                            )
                        )
                    }
                }

                say("Loading movies…")
                var vc: List<Category> = emptyList()
                val mv = ArrayList<Movie>()
                try {
                    vc = readCats(fetch(dir, "vod_cats.json", api(p, "get_vod_categories"), force))
                    fetch(dir, "vod_streams.json", api(p, "get_vod_streams"), force).inputStream().use { ins ->
                        JsonStream.readObjects(ins) { o ->
                            val id = o["stream_id"] ?: return@readObjects
                            mv.add(
                                Movie(
                                    id = id,
                                    name = o["name"] ?: "",
                                    poster = o["stream_icon"] ?: "",
                                    categoryId = o["category_id"] ?: "",
                                    url = "",
                                    rating = o["rating"] ?: "",
                                    ext = o["container_extension"] ?: "mp4"
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                }

                say("Loading series…")
                var sc: List<Category> = emptyList()
                val sr = ArrayList<Series>()
                try {
                    sc = readCats(fetch(dir, "series_cats.json", api(p, "get_series_categories"), force))
                    fetch(dir, "series.json", api(p, "get_series"), force).inputStream().use { ins ->
                        JsonStream.readObjects(ins) { o ->
                            val id = o["series_id"] ?: return@readObjects
                            sr.add(
                                Series(
                                    id = id,
                                    name = o["name"] ?: "",
                                    poster = o["cover"] ?: "",
                                    categoryId = o["category_id"] ?: "",
                                    plot = o["plot"] ?: "",
                                    genre = o["genre"] ?: "",
                                    cast = o["cast"] ?: "",
                                    releaseDate = o["releaseDate"] ?: (o["release_date"] ?: ""),
                                    rating = o["rating"] ?: ""
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                }

                publish(p, acc, lc, ch, vc, mv, sc, sr, "")
            } else {
                say("Downloading playlist…")
                val f = fetch(dir, "playlist.m3u", p.m3uUrl, force)
                say("Reading playlist…")
                val r = f.inputStream().use { M3uParser.parse(it) }
                if (r.channels.isEmpty() && r.movies.isEmpty()) {
                    f.delete()
                    throw IOException("The playlist is empty or not a valid M3U file")
                }
                publish(
                    p, AccountInfo(status = "Playlist"),
                    r.liveCats.values.toList(), r.channels,
                    r.vodCats.values.toList(), r.movies,
                    emptyList(), emptyList(), r.epgUrl
                )
            }
        }
    }

    private fun publish(
        p: Profile, acc: AccountInfo,
        lc: List<Category>, ch: List<Channel>,
        vc: List<Category>, mv: List<Movie>,
        sc: List<Category>, sr: List<Series>,
        epgUrl: String
    ) {
        profile = p
        account = acc
        liveCats = lc; channels = ch
        vodCats = vc; movies = mv
        seriesCats = sc; series = sr
        channelById = ch.associateBy { it.id }
        movieById = mv.associateBy { it.id }
        seriesById = sr.associateBy { it.id }
        m3uEpgUrl = epgUrl
        xmltv = null
        epgCache.clear()
        loaded = true
    }

    private fun readCats(f: File): List<Category> {
        val out = ArrayList<Category>()
        f.inputStream().use { ins ->
            JsonStream.readObjects(ins) { o ->
                val id = o["category_id"] ?: return@readObjects
                out.add(Category(id, o["category_name"] ?: id))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Parental control

    fun isLocked(section: String, cat: Category): Boolean {
        if (!Prefs.parentalEnabled) return false
        if (cat.id.startsWith("__")) return false
        return ADULT.containsMatchIn(cat.name) || Prefs.lockedCategories(profileId).contains("$section:${cat.id}")
    }

    fun isUserLocked(section: String, cat: Category): Boolean =
        Prefs.lockedCategories(profileId).contains("$section:${cat.id}")

    private fun lockedIds(section: String, cats: List<Category>): Set<String> =
        cats.filter { isLocked(section, it) }.map { it.id }.toSet()

    // ------------------------------------------------------------------ Catalogue queries

    fun channelsIn(catId: String, archiveOnly: Boolean = false): List<Channel> {
        val locked = lockedIds(SECTION_LIVE, liveCats)
        val base: List<Channel> = when (catId) {
            CAT_ALL -> channels.filter { it.categoryId !in locked }
            CAT_FAV -> {
                val fav = Prefs.favourites(profileId, Prefs.FAV_LIVE)
                channels.filter { it.id in fav && it.categoryId !in locked }
            }
            CAT_RECENT -> Prefs.recentChannels(profileId).mapNotNull { channelById[it] }.filter { it.categoryId !in locked }
            else -> channels.filter { it.categoryId == catId }
        }
        return if (archiveOnly) base.filter { it.hasArchive } else base
    }

    fun moviesIn(catId: String): List<Movie> {
        val locked = lockedIds(SECTION_VOD, vodCats)
        return when (catId) {
            CAT_ALL -> movies.filter { it.categoryId !in locked }
            CAT_FAV -> {
                val fav = Prefs.favourites(profileId, Prefs.FAV_VOD)
                movies.filter { it.id in fav && it.categoryId !in locked }
            }
            else -> movies.filter { it.categoryId == catId }
        }
    }

    fun seriesIn(catId: String): List<Series> {
        val locked = lockedIds(SECTION_SERIES, seriesCats)
        return when (catId) {
            CAT_ALL -> series.filter { it.categoryId !in locked }
            CAT_FAV -> {
                val fav = Prefs.favourites(profileId, Prefs.FAV_SERIES)
                series.filter { it.id in fav && it.categoryId !in locked }
            }
            else -> series.filter { it.categoryId == catId }
        }
    }

    // ------------------------------------------------------------------ EPG

    val hasEpgSource: Boolean
        get() {
            val p = profile ?: return false
            return p.isXtream || p.epgUrl.isNotBlank() || m3uEpgUrl.isNotBlank()
        }

    /** Current and upcoming programmes for a channel (empty when no guide is available). */
    suspend fun upcoming(context: Context, c: Channel): List<Programme> {
        val now = System.currentTimeMillis()
        return guide(context, c, full = false).filter { it.endMs > now }
    }

    /** Whole guide incl. past programmes (needed for catch-up). */
    suspend fun fullGuide(context: Context, c: Channel): List<Programme> = guide(context, c, full = true)

    private suspend fun guide(context: Context, c: Channel, full: Boolean): List<Programme> = withContext(Dispatchers.IO) {
        val p = profile ?: return@withContext emptyList()
        val key = (if (full) "F" else "S") + c.id
        val cached = epgCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000L) return@withContext cached.second

        val list: List<Programme> = try {
            if (p.isXtream) {
                val url = if (full) api(p, "get_simple_data_table", "&stream_id=${c.id}")
                else api(p, "get_short_epg", "&stream_id=${c.id}&limit=12")
                parseListings(Net.getString(url))
            } else {
                ensureXmltv(context.applicationContext, p)
                xmltv?.get(c.epgId) ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        epgCache[key] = Pair(System.currentTimeMillis(), list)
        list
    }

    private fun parseListings(body: String): List<Programme> {
        val out = ArrayList<Programme>()
        val arr = JSONObject(body).optJSONArray("epg_listings") ?: return out
        val shift = Prefs.epgShiftHours * 3600_000L
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val startRaw = o.optString("start", "")
            var start = (o.optString("start_timestamp", "").toLongOrNull() ?: 0L) * 1000L
            var end = (o.optString("stop_timestamp", "").toLongOrNull() ?: 0L) * 1000L
            if (start == 0L) start = try { fmt.parse(startRaw)?.time ?: 0L } catch (_: Exception) { 0L }
            if (end == 0L) end = try { fmt.parse(o.optString("end", ""))?.time ?: 0L } catch (_: Exception) { 0L }
            if (start == 0L || end == 0L) continue
            out.add(
                Programme(
                    title = b64(o.optString("title", "")),
                    desc = b64(o.optString("description", "")),
                    startMs = start + shift,
                    endMs = end + shift,
                    startRaw = startRaw,
                    hasArchive = o.optString("has_archive", "0") == "1"
                )
            )
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun b64(s: String): String {
        if (s.isEmpty()) return s
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            s
        }
    }

    @Synchronized
    private fun ensureXmltv(context: Context, p: Profile) {
        if (xmltv != null) return
        val url = p.epgUrl.ifBlank { m3uEpgUrl }
        if (url.isBlank()) {
            xmltv = emptyMap()
            return
        }
        xmltv = try {
            val f = fetch(cacheDir(context, p), "epg.xml", url, false)
            val wanted = channels.map { it.epgId }.filter { it.isNotEmpty() }.toSet()
            val now = System.currentTimeMillis()
            val shift = Prefs.epgShiftHours * 3600_000L
            XmltvParser.parse(f, wanted, now - 2 * 3600_000L - shift, now + 36 * 3600_000L - shift)
                .mapValues { e -> e.value.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ------------------------------------------------------------------ VOD / series details

    suspend fun movieInfo(m: Movie): MovieInfo = withContext(Dispatchers.IO) {
        val p = profile
        if (p == null || !p.isXtream) return@withContext MovieInfo()
        try {
            val info = JSONObject(Net.getString(api(p, "get_vod_info", "&vod_id=${m.id}"))).optJSONObject("info")
                ?: return@withContext MovieInfo()
            MovieInfo(
                plot = str(info, "plot", "description"),
                cast = str(info, "cast", "actors"),
                director = str(info, "director"),
                genre = str(info, "genre"),
                releaseDate = str(info, "releasedate", "release_date"),
                duration = str(info, "duration"),
                rating = str(info, "rating"),
                backdrop = firstOf(info.opt("backdrop_path"))
            )
        } catch (_: Exception) {
            MovieInfo()
        }
    }

    /** Season number -> episodes, both sorted. */
    suspend fun episodes(seriesId: String): Map<Int, List<Episode>> = withContext(Dispatchers.IO) {
        val p = profile ?: return@withContext emptyMap()
        val root = JSONObject(Net.getString(api(p, "get_series_info", "&series_id=$seriesId")))
        val out = java.util.TreeMap<Int, MutableList<Episode>>()

        fun addAll(arr: JSONArray, seasonHint: Int) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                val season = o.optString("season", "").toIntOrNull() ?: seasonHint
                val num = o.optString("episode_num", "").toIntOrNull() ?: (i + 1)
                val ext = o.optString("container_extension", "mp4").ifEmpty { "mp4" }
                val info = o.optJSONObject("info")
                out.getOrPut(season) { ArrayList() }.add(
                    Episode(
                        id = id,
                        title = o.optString("title", "Episode $num"),
                        season = season,
                        num = num,
                        url = "${p.server}/series/${p.username}/${p.password}/$id.$ext",
                        plot = info?.optString("plot", "") ?: "",
                        duration = info?.optString("duration", "") ?: ""
                    )
                )
            }
        }

        val eps = root.opt("episodes")
        if (eps is JSONObject) {
            val keys = eps.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = eps.optJSONArray(k) ?: continue
                addAll(arr, k.toIntOrNull() ?: 1)
            }
        } else if (eps is JSONArray) {
            for (i in 0 until eps.length()) {
                val arr = eps.optJSONArray(i) ?: continue
                addAll(arr, i + 1)
            }
        }
        for (l in out.values) l.sortBy { it.num }
        out
    }

    private fun str(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (o.isNull(k)) continue
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun firstOf(v: Any?): String = when (v) {
        is JSONArray -> if (v.length() > 0) v.optString(0, "") else ""
        is String -> v
        else -> ""
    }
}
