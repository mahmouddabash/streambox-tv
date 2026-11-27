package com.streambox.tv.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    const val CAT_CONTINUE = "__continue"

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
        val ext = Prefs.channelFormat(c.id).ifEmpty { Prefs.streamFormat }
        return "${p.server}/live/${p.username}/${p.password}/${c.id}.$ext"
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

    /** Server local time minus UTC, learned from the EPG; null until known. */
    @Volatile
    var serverOffsetMs: Long? = null

    /**
     * Timeshift from an arbitrary moment (live pause / rewind):
     * /timeshift/user/pass/{minutes}/{yyyy-MM-dd:HH-mm in server time}/{id}.ts
     */
    fun timeshiftUrl(c: Channel, startMs: Long, minutes: Int): String {
        val p = profile ?: return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        val offset = serverOffsetMs
        val start = if (offset != null) {
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.format(startMs + offset)
        } else {
            fmt.format(startMs)
        }
        return "${p.server}/timeshift/${p.username}/${p.password}/${minutes.coerceAtLeast(1)}/$start/${c.id}.ts"
    }

    /** Whether a live channel can be paused and rewound through the provider's archive. */
    fun canTimeshift(c: Channel?): Boolean = c != null && c.hasArchive && profile?.isXtream == true

    // ------------------------------------------------------------------ Auth

    /** Checks the credentials. Throws IOException with a readable message on failure. */
    suspend fun authenticate(p: Profile): AccountInfo = withContext(Dispatchers.IO) {
        if (!p.isXtream) return@withContext AccountInfo(status = "Playlist")
        val body = getWithAcceptedUserAgent(api(p))
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

    /**
     * Many providers refuse players they do not recognise (HTTP 403). When that happens, retry with
     * common player User-Agents and remember the first one the server accepts.
     */
    private fun getWithAcceptedUserAgent(url: String): String {
        val refused = setOf(401, 403, 406, 451)
        try {
            return Net.getString(url)
        } catch (e: HttpException) {
            if (e.code !in refused) throw e
        }
        val saved = Prefs.userAgent
        for (ua in Net.FALLBACK_UAS) {
            if (ua == saved) continue
            Prefs.userAgent = ua
            try {
                return Net.getString(url)
            } catch (e: HttpException) {
                if (e.code !in refused) {
                    Prefs.userAgent = saved
                    throw e
                }
            } catch (e: IOException) {
                Prefs.userAgent = saved
                throw e
            }
        }
        Prefs.userAgent = saved
        throw IOException(
            "the server refused this app (HTTP 403). Check the server URL and port, or ask your provider " +
                "whether your network or this player is blocked"
        )
    }

    // ------------------------------------------------------------------ Loading

    fun close() {
        profile = null
        loaded = false
        liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
        rawLiveCats = emptyList(); rawVodCats = emptyList(); rawSeriesCats = emptyList(); rawChannels = emptyList()
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

    /** Fast start: use the saved lists as they are (any age); a background refresh updates them. */
    @Volatile
    private var quickStart = false

    /** Returns a fresh-enough cached file, downloading when needed. Falls back to stale data offline. */
    private fun fetch(dir: File, name: String, url: String, force: Boolean): File {
        val f = File(dir, name)
        if (quickStart && !force && f.exists() && f.length() > 0) return f
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
            // Fast start: everything saved from last time is used straight away; sign-in and
            // fresh lists are fetched in the background afterwards.
            quickStart = !force && Prefs.fastStart &&
                File(dir, if (p.isXtream) "live_streams.json" else "playlist.m3u").let { it.exists() && it.length() > 0 }
            if (p.isXtream) {
                say("Signing in…")
                val acc = if (quickStart) Prefs.cachedAccount(p.id) ?: AccountInfo() else try {
                    authenticate(p).also { Prefs.saveAccount(p.id, it) }
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
                                    ext = o["container_extension"] ?: "mp4",
                                    added = (o["added"]?.toLongOrNull() ?: 0L) * 1000L
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
                                    rating = o["rating"] ?: "",
                                    added = (o["last_modified"]?.toLongOrNull() ?: 0L) * 1000L
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                }

                publish(p, acc, lc, ch, vc, mv, sc, sr, "")
                if (quickStart) refreshInBackground(app, p)
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
                if (quickStart) refreshInBackground(app, p)
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
        rawLiveCats = lc; rawChannels = ch
        rawVodCats = vc; movies = mv
        rawSeriesCats = sc; series = sr
        applyCustomisations()
        movieById = mv.associateBy { it.id }
        seriesById = sr.associateBy { it.id }
        m3uEpgUrl = epgUrl
        xmltv = null
        epgCache.clear()
        movieInfoCache.clear()
        serverOffsetMs = null
        loaded = true
        // Prepare film/channel grouping in the background so the first screens open without delay.
        bg.launch {
            try {
                mv.forEach { movieGroup(it.name); qualityRank(it.name) }
                groups()
                ch.firstOrNull()?.let { variantsOf(it) }
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ Channel manager

    private var rawLiveCats: List<Category> = emptyList()
    private var rawVodCats: List<Category> = emptyList()
    private var rawSeriesCats: List<Category> = emptyList()
    private var rawChannels: List<Channel> = emptyList()

    /** Every category of a section as the provider sends it (including hidden ones). */
    fun allCategories(section: String): List<Category> = when (section) {
        SECTION_VOD -> rawVodCats
        SECTION_SERIES -> rawSeriesCats
        else -> rawLiveCats
    }

    // ------------------------------------------------------------------ Viewers

    /** The person watching; null = main viewer. */
    @Volatile
    var viewer: Viewer? = null
        private set

    /** Key for personal data (favourites, Continue watching, recent): separate for each viewer. */
    val userKey: String get() = viewer?.let { "$profileId~${it.id}" } ?: profileId

    val kidsMode: Boolean get() = viewer?.kids == true

    val viewerName: String get() = viewer?.name ?: Prefs.mainViewerName

    fun selectViewer(id: String) {
        Prefs.setCurrentViewer(profileId, id)
        applyCustomisations()
    }

    private val KIDS = Regex("(kid|child|cartoon|anim|family|junior|disney|nick|baby|toon|أطفال|اطفال|كرتون|رسوم|عائل)", RegexOption.IGNORE_CASE)

    /** Kids profiles see the categories allowed for them (or child-friendly names when none are chosen), never adult ones. */
    private fun kidsAllowed(section: String, cat: Category): Boolean {
        if (ADULT.containsMatchIn(cat.name)) return false
        val chosen = Prefs.kidsCategories(profileId)
        if (chosen.isNotEmpty()) return "$section:${cat.id}" in chosen
        // No choices made: child-friendly names, or (if the provider has none) everything except adult.
        val anyKids = allCategories(section).any { KIDS.containsMatchIn(it.name) }
        return !anyKids || KIDS.containsMatchIn(cat.name)
    }

    fun isKidsCategory(section: String, cat: Category) = "$section:${cat.id}" in Prefs.kidsCategories(profileId)

    /** Applies the user's channel names, hidden categories and category order. Call after any change. */
    fun applyCustomisations() {
        val pid = profileId
        val vid = Prefs.currentViewer(pid)
        viewer = if (vid.isEmpty()) null else Prefs.viewers(pid).firstOrNull { it.id == vid }
        val hidden = Prefs.hiddenCategories(pid)
        fun arrange(section: String, list: List<Category>): List<Category> {
            val order = Prefs.categoryOrder(pid, section)
            val byId = list.associateBy { it.id }
            val ordered = order.mapNotNull { byId[it] } + list.filter { it.id !in order }
            val shown = ordered.filter { "$section:${it.id}" !in hidden }
            return if (kidsMode) shown.filter { kidsAllowed(section, it) } else shown
        }
        liveCats = arrange(SECTION_LIVE, rawLiveCats)
        vodCats = arrange(SECTION_VOD, rawVodCats)
        seriesCats = arrange(SECTION_SERIES, rawSeriesCats)
        val names = Prefs.channelNames(pid)
        channels = if (names.isEmpty()) rawChannels else rawChannels.map { c -> names[c.id]?.let { c.copy(name = it) } ?: c }
        channelById = channels.associateBy { it.id }
    }

    private fun hiddenCatIds(section: String): Set<String> {
        val prefix = "$section:"
        val hidden = Prefs.hiddenCategories(profileId).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toMutableSet()
        if (kidsMode) allCategories(section).filterNot { kidsAllowed(section, it) }.mapTo(hidden) { it.id }
        return hidden
    }

    /** Moves a category one place up (-1) or down (+1) in its section. */
    fun moveCategory(section: String, catId: String, delta: Int) {
        val visible = when (section) { SECTION_VOD -> vodCats; SECTION_SERIES -> seriesCats; else -> liveCats }.map { it.id }.toMutableList()
        val i = visible.indexOf(catId)
        val j = i + delta
        if (i < 0 || j !in visible.indices) return
        visible[i] = visible[j].also { visible[j] = visible[i] }
        val rest = allCategories(section).map { it.id }.filter { it !in visible }
        Prefs.setCategoryOrder(profileId, section, visible + rest)
        applyCustomisations()
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
        val locked = lockedIds(SECTION_LIVE, liveCats) + hiddenCatIds(SECTION_LIVE)
        val hiddenCh = Prefs.hiddenChannels(profileId)
        val base: List<Channel> = when (catId) {
            CAT_ALL -> channels.filter { it.categoryId !in locked }
            CAT_FAV -> {
                val fav = Prefs.favourites(userKey, Prefs.FAV_LIVE)
                channels.filter { it.id in fav && it.categoryId !in locked }
            }
            CAT_RECENT -> Prefs.recentChannels(userKey).mapNotNull { channelById[it] }.filter { it.categoryId !in locked }
            else -> channels.filter { it.categoryId == catId }
        }
        var out = if (hiddenCh.isEmpty()) base else base.filter { it.id !in hiddenCh }
        if (archiveOnly) out = out.filter { it.hasArchive }
        return when {
            catId == CAT_RECENT -> out
            Prefs.channelSort == 1 -> out.sortedBy { it.name.lowercase() }
            Prefs.channelSort == 2 -> out.sortedBy { it.num }
            else -> out
        }
    }

    fun moviesIn(catId: String): List<Movie> {
        val locked = lockedIds(SECTION_VOD, vodCats) + hiddenCatIds(SECTION_VOD)
        val hid = Prefs.hidden(userKey)
        val list = when (catId) {
            CAT_ALL -> movies.filter { it.categoryId !in locked }
            CAT_FAV -> {
                val fav = Prefs.favourites(userKey, Prefs.FAV_VOD)
                movies.filter { it.id in fav && it.categoryId !in locked }
            }
            CAT_CONTINUE -> continueWatching(200).mapNotNull { it.movie }
            else -> movies.filter { it.categoryId == catId }
        }
        return if (hid.isEmpty()) list else list.filter { "m:${it.id}" !in hid }
    }

    fun seriesIn(catId: String): List<Series> {
        val locked = lockedIds(SECTION_SERIES, seriesCats) + hiddenCatIds(SECTION_SERIES)
        val hid = Prefs.hidden(userKey)
        val list = when (catId) {
            CAT_ALL -> series.filter { it.categoryId !in locked }
            CAT_FAV -> {
                val fav = Prefs.favourites(userKey, Prefs.FAV_SERIES)
                series.filter { it.id in fav && it.categoryId !in locked }
            }
            CAT_CONTINUE -> continueWatching(200).mapNotNull { it.series }
            else -> series.filter { it.categoryId == catId }
        }
        return if (hid.isEmpty()) list else list.filter { "s:${it.id}" !in hid }
    }

    /** "Because you watched": best-rated titles from the same category as the latest film watched. */
    fun becauseYouWatchedMovie(limit: Int = 20): Pair<Movie, List<Movie>>? {
        val hid = Prefs.hidden(userKey)
        val last = Prefs.resumeIds("vod_${userKey}_").firstNotNullOfOrNull { movieById[it] } ?: return null
        val watched = Prefs.resumeIds("vod_${userKey}_").toSet()
        val list = moviesIn(last.categoryId).filter { it.id != last.id && it.id !in watched && "m:${it.id}" !in hid }
            .sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }.take(limit)
        return if (list.size >= 3) last to list else null
    }

    /** Same idea for series: same genre (from the provider), else the same category. */
    fun becauseYouWatchedSeries(limit: Int = 20): Pair<Series, List<Series>>? {
        val last = recentSeries(1).firstOrNull() ?: return null
        val genres = last.genre.split(',', '/', '|').map { it.trim().lowercase() }.filter { it.length > 2 }
        val pool = seriesIn(CAT_ALL).filter { it.id != last.id }
        val byGenre = if (genres.isEmpty()) emptyList() else pool.filter { s -> genres.any { s.genre.lowercase().contains(it) } }
        val list = (byGenre.ifEmpty { pool.filter { it.categoryId == last.categoryId } })
            .sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }.take(limit)
        return if (list.size >= 3) last to list else null
    }

    /** A random film for "Surprise me": prefers rated 7+, never hidden or locked. */
    fun surpriseMovie(): Movie? {
        val all = moviesIn(CAT_ALL)
        val good = all.filter { (it.rating.toDoubleOrNull() ?: 0.0) >= 7.0 }
        return (good.ifEmpty { all }).randomOrNull()
    }

    // ------------------------------------------------------------------ Home rows

    /** Newest first; falls back to the end of the provider's list when no dates are given. */
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * After a fast start: signs in (updating expiry / connections) and re-downloads lists older than
     * the refresh interval. New lists are used from the next start, or straight away with Refresh.
     */
    private fun refreshInBackground(context: Context, p: Profile) {
        quickStart = false
        bg.launch {
            kotlinx.coroutines.delay(4000) // let the first screen settle
            try {
                if (p.isXtream) {
                    val acc = authenticate(p)
                    Prefs.saveAccount(p.id, acc)
                    if (profile?.id == p.id) account = acc
                }
                val dir = cacheDir(context, p)
                val files = if (p.isXtream) listOf(
                    "live_cats.json" to api(p, "get_live_categories"),
                    "live_streams.json" to api(p, "get_live_streams"),
                    "vod_cats.json" to api(p, "get_vod_categories"),
                    "vod_streams.json" to api(p, "get_vod_streams"),
                    "series_cats.json" to api(p, "get_series_categories"),
                    "series.json" to api(p, "get_series")
                ) else listOf("playlist.m3u" to p.m3uUrl)
                for ((name, url) in files) {
                    try {
                        fetch(dir, name, url, force = false)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Films added in the last 7 days (newest first). */
    fun newThisWeek(limit: Int = 40): List<Movie> {
        val since = System.currentTimeMillis() - 7L * 24 * 3600_000L
        return recentlyAddedMovies(limit).filter { it.added >= since }
    }

    fun newSeriesThisWeek(limit: Int = 40): List<Series> {
        val since = System.currentTimeMillis() - 7L * 24 * 3600_000L
        return recentlyAddedSeries(limit).filter { it.added >= since }
    }

    /** The viewer's Watch later list as movies and series. */
    fun watchLater(): List<Any> = Prefs.watchLater(userKey).mapNotNull {
        when {
            it.startsWith("m:") -> movie(it.substring(2))
            it.startsWith("s:") -> seriesItem(it.substring(2))
            else -> null
        }
    }

    // The newest titles are sorted once per catalogue / viewer, not on every visit to Home.
    @Volatile private var newestMoviesKey: Any? = null
    @Volatile private var newestMovies: List<Movie> = emptyList()
    @Volatile private var newestSeriesKey: Any? = null
    @Volatile private var newestSeries: List<Series> = emptyList()

    fun recentlyAddedMovies(limit: Int = 30): List<Movie> {
        val key = Triple(System.identityHashCode(movies), userKey, Prefs.hidden(userKey).size)
        if (newestMoviesKey != key) {
            val all = moviesIn(CAT_ALL)
            newestMovies = if (all.any { it.added > 0 }) all.sortedByDescending { it.added }.take(200) else all.takeLast(200).reversed()
            newestMoviesKey = key
        }
        return newestMovies.take(limit)
    }

    fun recentlyAddedSeries(limit: Int = 30): List<Series> {
        val key = Triple(System.identityHashCode(series), userKey, Prefs.hidden(userKey).size)
        if (newestSeriesKey != key) {
            val all = seriesIn(CAT_ALL)
            newestSeries = if (all.any { it.added > 0 }) all.sortedByDescending { it.added }.take(200) else all.takeLast(200).reversed()
            newestSeriesKey = key
        }
        return newestSeries.take(limit)
    }

    /** Movies with a saved resume position, most recently watched first. */
    fun continueWatchingMovies(limit: Int = 20): List<Movie> {
        val locked = lockedIds(SECTION_VOD, vodCats)
        return Prefs.resumeIds("vod_${userKey}_").mapNotNull { movieById[it] }
            .filter { it.categoryId !in locked }.take(limit)
    }

    // ------------------------------------------------------------------ Channel qualities

    private val qualityTokens = Regex(
        "(?i)(\\b(4k|uhd|2160p?|fhd|1080[pi]?|hd|720p?|sd|576p?|480p?|hevc|h\\.?26[45]|x26[45]|50fps|60fps|hq|lq|raw|backup|multi)\\b|ᵁᴴᴰ|ᶠᴴᴰ|ᴴᴰ|ˢᴰ|⁴ᴷ|[◉●★✪♛™®]|\\(\\s*\\)|\\[\\s*\\])"
    )

    // Results of the name analysis are remembered: computing them while scrolling made lists lag.
    private val rankMemo = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val groupMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val movieGroupMemo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 4 = 4K, 3 = Full HD, 2 = HD (or unknown), 1 = SD. */
    fun qualityRank(name: String): Int = rankMemo.getOrPut(name) { computeRank(name) }

    private fun computeRank(name: String): Int {
        val n = name.lowercase()
        return when {
            Regex("\\b(4k|uhd|2160p?)\\b").containsMatchIn(n) || "ᵁᴴᴰ" in name || "⁴ᴷ" in name -> 4
            Regex("\\b(fhd|1080[pi]?)\\b").containsMatchIn(n) || "ᶠᴴᴰ" in name -> 3
            Regex("\\b(sd|576p?|480p?|lq)\\b").containsMatchIn(n) || "ˢᴰ" in name -> 1
            else -> 2
        }
    }

    fun qualityLabel(rank: Int) = when (rank) { 4 -> "4K"; 3 -> "FHD"; 1 -> "SD"; else -> "HD" }

    /** Same channel in different qualities share this key ("MBC 1 FHD", "MBC 1 HD" → "mbc 1"). */
    fun qualityGroup(name: String): String = groupMemo.getOrPut(name) { computeGroup(name) }

    private fun computeGroup(name: String): String =
        qualityTokens.replace(name.lowercase(), " ").replace(Regex("[|:_\\-]+\\s*$"), " ").replace(Regex("\\s+"), " ").trim()

    /** One channel per group: the hand-picked quality, else the best one allowed by the preference. */
    fun collapseQualities(list: List<Channel>): List<Channel> {
        if (!Prefs.groupQualities || list.size < 2) return list
        val groups = list.groupBy { qualityGroup(it.name) }
        if (groups.size == list.size) return list
        val cap = when (Prefs.preferredQuality) { 1 -> 3; 2 -> 2; 3 -> 1; else -> 4 }
        val chosen = HashMap<String, Channel>()
        for ((key, vs) in groups) {
            val pick = vs.firstOrNull { it.id == Prefs.groupChoice(key) }
                ?: vs.filter { qualityRank(it.name) <= cap }.maxByOrNull { qualityRank(it.name) }
                ?: vs.minByOrNull { qualityRank(it.name) }!!
            chosen[key] = pick
        }
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()
        for (c in list) {
            val key = qualityGroup(c.name)
            if (seen.add(key)) out.add(chosen[key] ?: c)
        }
        return out
    }

    @Volatile private var variantIndexFor: List<Channel>? = null
    @Volatile private var variantIndex: Map<String, List<Channel>> = emptyMap()

    /** Every quality of [c] in the catalogue, best first (looked up in an index, not by scanning). */
    fun variantsOf(c: Channel): List<Channel> {
        val key = qualityGroup(c.name)
        if (key.isEmpty()) return listOf(c)
        val list = channels
        if (variantIndexFor !== list) {
            variantIndex = list.groupBy { it.categoryId + "\u0000" + qualityGroup(it.name) }
                .mapValues { (_, v) -> v.sortedByDescending { qualityRank(it.name) } }
            variantIndexFor = list
        }
        return variantIndex[c.categoryId + "\u0000" + key] ?: listOf(c)
    }

    @Volatile private var movieGroupsFor: List<Movie>? = null
    @Volatile private var movieGroups: Map<String, List<Movie>> = emptyMap()

    /** Key shared by copies of one film ("Dune (2021) 4K", "Dune 2021 FHD" → "dune 2021"). */
    fun movieGroup(name: String): String = movieGroupMemo.getOrPut(name) {
        val (t, year) = Tmdb.cleanTitle(name)
        (qualityGroup(t) + " " + year).trim()
    }

    private fun groups(): Map<String, List<Movie>> {
        if (movieGroupsFor !== movies) {
            movieGroups = movies.groupBy { movieGroup(it.name) }
            movieGroupsFor = movies
        }
        return movieGroups
    }

    /** All copies of a film the provider has (any category), best quality first. */
    fun movieCopies(m: Movie): List<Movie> =
        (groups()[movieGroup(m.name)] ?: listOf(m)).sortedByDescending { qualityRank(it.name) }

    /** One entry per film: the copy in the preferred quality. */
    fun collapseMovies(list: List<Movie>): List<Movie> {
        if (!Prefs.groupMovieCopies || list.size < 2) return list
        val cap = when (Prefs.preferredQuality) { 1 -> 3; 2 -> 2; 3 -> 1; else -> 4 }
        val seen = HashMap<String, Movie>()
        val order = ArrayList<String>()
        for (m in list) {
            val k = movieGroup(m.name)
            val cur = seen[k]
            if (cur == null) { seen[k] = m; order.add(k); continue }
            val r = qualityRank(m.name)
            val rc = qualityRank(cur.name)
            val better = if (r <= cap && rc <= cap) r > rc else if (rc > cap) r < rc else false
            if (better) seen[k] = m
        }
        return order.map { seen[it]!! }
    }

    /** Watched to the end, or only the closing credits are left (the last 5 minutes past 85 %). */
    fun isFinished(pos: Long, dur: Long): Boolean =
        dur > 0 && (pos > dur * 0.95 || (dur >= 20 * 60_000L && pos >= dur * 0.85 && dur - pos <= 5 * 60_000L))

    /** One entry per title being watched: a movie, or a series with an episode in progress. */
    data class Resume(val movie: Movie?, val series: Series?, val key: String)

    /** Movies and series in progress, most recently watched first. */
    fun continueWatching(limit: Int = 20): List<Resume> {
        val lockedVod = lockedIds(SECTION_VOD, vodCats)
        val lockedSeries = lockedIds(SECTION_SERIES, seriesCats)
        val movieKey = "vod_${userKey}_"
        val epKey = "ep_${userKey}_"
        val seenSeries = HashSet<String>()
        val out = ArrayList<Resume>()
        for (k in Prefs.resumeIds("")) {
            if (out.size >= limit) break
            if (isFinished(Prefs.position(k), Prefs.duration(k))) continue // only the credits were left
            when {
                k.startsWith(movieKey) -> movieById[k.removePrefix(movieKey)]
                    ?.takeIf { it.categoryId !in lockedVod }
                    ?.let { out.add(Resume(it, null, k)) }
                k.startsWith(epKey) -> {
                    val sid = Prefs.episodeSeries(k)
                    if (sid.isNotEmpty() && seenSeries.add(sid)) {
                        seriesById[sid]?.takeIf { it.categoryId !in lockedSeries }?.let { out.add(Resume(null, it, k)) }
                    }
                }
            }
        }
        return out
    }

    /** "Remove from Continue watching": forgets the saved position (all episodes, for a series). */
    fun forgetProgress(movie: Movie?, series: Series?) {
        if (movie != null) Prefs.setPosition("vod_${userKey}_${movie.id}", 0L)
        if (series != null) {
            for (k in Prefs.resumeIds("ep_${userKey}_").map { "ep_${userKey}_$it" }) {
                if (Prefs.episodeSeries(k) == series.id) Prefs.setPosition(k, 0L)
            }
        }
    }

    fun recentSeries(limit: Int = 20): List<Series> {
        val locked = lockedIds(SECTION_SERIES, seriesCats)
        return Prefs.recentList("recentSeries_$userKey").mapNotNull { seriesById[it] }
            .filter { it.categoryId !in locked }.take(limit)
    }

    fun favouriteChannels(limit: Int = 30): List<Channel> = channelsIn(CAT_FAV).take(limit)

    fun recentChannelList(limit: Int = 20): List<Channel> = channelsIn(CAT_RECENT).take(limit)

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
            // Learn the server's clock offset (its "start" text vs the real timestamp) for timeshift URLs.
            if (serverOffsetMs == null && startRaw.length >= 19 && o.optString("start_timestamp", "").isNotEmpty()) {
                try {
                    val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    val asUtc = utc.parse(startRaw)?.time
                    if (asUtc != null) serverOffsetMs = asUtc - start
                } catch (_: Exception) {
                }
            }
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

    private val movieInfoCache = java.util.concurrent.ConcurrentHashMap<String, MovieInfo>()

    /** Cached result of [movieInfo], or null when it has not been fetched yet. */
    fun cachedMovieInfo(m: Movie): MovieInfo? = movieInfoCache[m.id]

    suspend fun movieInfo(m: Movie): MovieInfo {
        movieInfoCache[m.id]?.let { return it }
        val info = fetchMovieInfo(m)
        if (info.plot.isNotEmpty() || info.backdrop.isNotEmpty()) movieInfoCache[m.id] = info
        return info
    }

    private suspend fun fetchMovieInfo(m: Movie): MovieInfo = withContext(Dispatchers.IO) {
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
                backdrop = firstOf(info.opt("backdrop_path")),
                trailer = youtubeId(str(info, "youtube_trailer", "trailer")),
                tmdbId = str(info, "tmdb_id", "tmdb")
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
        root.optJSONObject("info")?.let { info ->
            seriesExtras[seriesId] = MovieInfo(
                backdrop = firstOf(info.opt("backdrop_path")),
                trailer = youtubeId(str(info, "youtube_trailer", "trailer")),
                tmdbId = str(info, "tmdb_id", "tmdb"),
                cast = str(info, "cast"),
                director = str(info, "director")
            )
        }

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

    /** Backdrop, trailer and TMDB id from get_series_info, filled when episodes load. */
    val seriesExtras = java.util.concurrent.ConcurrentHashMap<String, MovieInfo>()

    /** Accepts a bare YouTube id or any YouTube URL. */
    fun youtubeId(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        Regex("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})").find(t)?.let { return it.groupValues[1] }
        return if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) t else ""
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
