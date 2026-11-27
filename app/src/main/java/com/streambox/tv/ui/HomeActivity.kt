package com.streambox.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streambox.tv.R
import com.streambox.tv.data.Channel
import com.streambox.tv.data.Movie
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Profile
import com.streambox.tv.data.Repo
import com.streambox.tv.data.Series
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Streaming-service style home: a full-screen hero that follows the focused title,
 * a top navigation bar and horizontal rows (continue watching, recently added, channels…).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var profile: Profile
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nav: List<Button>
    private lateinit var rowsView: RecyclerView
    private lateinit var heroA: ImageView
    private lateinit var heroB: ImageView
    private lateinit var heroEyebrow: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroPlot: TextView
    private var loading = false

    private var rows: List<Row> = emptyList()
    private var focusRow = 0
    private var focusCol = 0
    private var heroJob: Job? = null
    private var heroUrl = ""
    private var heroToken = 0
    private val pool = RecyclerView.RecycledViewPool()

    // ------------------------------------------------------------------ Model

    private enum class Kind { MOVIE, SERIES, CHANNEL, DOWNLOAD }

    private class Card(
        val kind: Kind,
        val id: String,
        val title: String,
        val image: String,
        val badge: String = "",
        /** 0..1000, or -1 for none. */
        val progress: Int = -1
    )

    private class Row(val title: String, val cards: List<Card>)

    // ------------------------------------------------------------------ Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        com.streambox.tv.data.CrashReporter.offerIfCrashed(this)

        val id = intent.getStringExtra(EXTRA_PROFILE)
        val p = Prefs.profiles().firstOrNull { it.id == id }
        if (p == null) {
            finish()
            return
        }
        profile = p
        Prefs.lastProfileId = p.id
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        heroA = findViewById(R.id.heroImage)
        heroB = findViewById(R.id.heroImage2)
        heroEyebrow = findViewById(R.id.heroEyebrow)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroPlot = findViewById(R.id.heroPlot)
        findViewById<TextView>(R.id.profileName).text = p.name
        // Short screens (car units): when the hero text doesn't fit above the rows it would slide
        // under the top bar, so drop the least important lines until it fits.
        findViewById<View>(R.id.hero).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post { fitHero() } }
        heroTitle.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { resetHeroFit() }
        })

        val live = findViewById<Button>(R.id.tileLive)
        val movies = findViewById<Button>(R.id.tileMovies)
        val series = findViewById<Button>(R.id.tileSeries)
        val refresh = findViewById<Button>(R.id.tileRefresh)
        val search = findViewById<Button>(R.id.tileSearch)
        search.setOnClickListener { if (Repo.loaded) startActivity(Intent(this, SearchActivity::class.java)) }
        val multi = findViewById<Button>(R.id.tileMulti)
        multi.setOnClickListener {
            if (!Repo.loaded || Repo.channels.isEmpty()) return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Multi-screen")
                .setItems(arrayOf("2 screens", "4 screens")) { _, w ->
                    startActivity(Intent(this, MultiViewActivity::class.java).putExtra(MultiViewActivity.EXTRA_COUNT, if (w == 0) 2 else 4))
                }
                .show()
        }
        val downloads = findViewById<Button>(R.id.tileDownloads)
        // Kids profiles need the parental PIN: downloads and recordings aren't filtered for children.
        downloads.setOnClickListener { Ui.guardKids(this) { startActivity(Intent(this, DownloadsActivity::class.java)) } }
        val rec = findViewById<Button>(R.id.tileRec)
        rec.setOnClickListener { Ui.guardKids(this) { startActivity(Intent(this, RecordingsActivity::class.java)) } }
        val settings = findViewById<Button>(R.id.tileSettings)
        val profiles = findViewById<Button>(R.id.tileProfiles)
        findViewById<Button>(R.id.tileSurprise).setOnClickListener {
            val m = if (Repo.loaded) Repo.surpriseMovie() else null
            if (m == null) toast("Nothing to pick from yet") else {
                toast(Tr.s("🎲  Surprise: {}").replace("{}", m.name))
                startActivity(Intent(this, MovieDetailActivity::class.java).putExtra(MovieDetailActivity.EXTRA_ID, m.id))
            }
        }
        nav = listOf(live, movies, series)

        live.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        movies.setOnClickListener {
            if (Repo.movies.isEmpty()) toast("This profile has no movies")
            else startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, Repo.SECTION_VOD))
        }
        series.setOnClickListener {
            if (Repo.series.isEmpty()) toast("This profile has no series")
            else startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, Repo.SECTION_SERIES))
        }
        refresh.setOnClickListener { load(true) }
        // Narrow screens (car units, phones): no clock in the top bar, so the buttons always fit.
        if (resources.configuration.screenWidthDp < 1100) findViewById<View>(R.id.clock)?.visibility = View.GONE
        // The top bar keeps the everyday icons; the rest sit behind "⋯" so nothing is pushed off screen.
        val tucked = listOf(
            findViewById<Button>(R.id.tileSurprise) to "🎲  Surprise me",
            multi to "▦  Multi-screen",
            rec to "⏺  Recordings",
            refresh to "⟳  Refresh content"
        )
        tucked.forEach { it.first.visibility = View.GONE }
        findViewById<Button>(R.id.tileMore).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(tucked.map { Tr.s(it.second) }.toTypedArray()) { _, w -> tucked[w].first.performClick() }
                .show()
        }
        settings.setOnClickListener { Ui.guardKids(this) { startActivity(Intent(this, SettingsActivity::class.java)) } }
        profiles.setOnClickListener {
            if (!Repo.loaded) { finish(); return@setOnClickListener }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(Repo.viewerName)
                .setItems(arrayOf("👤  Switch viewer", "⇄  Switch provider profile")) { _, w ->
                    if (w == 0) whoIsWatching.launch(Intent(this, ViewersActivity::class.java))
                    else Ui.guardKids(this) { finish() }
                }
                .show()
        }
        for ((b, label) in listOf(search to "Search", multi to "Multi-screen", downloads to "Downloads", rec to "Recordings", refresh to "Refresh content", findViewById<Button>(R.id.tileMore) to "More", settings to "Settings", profiles to "Switch profile")) {
            b.contentDescription = label
            b.setOnFocusChangeListener { _, has -> if (has) status.text = label else if (!loading) status.text = "" }
        }

        rowsView = findViewById(R.id.rows)
        rowsView.layoutManager = LinearLayoutManager(this)
        rowsView.itemAnimator = null
        rowsView.adapter = RowsAdapter()

        setHeroIdle("Welcome", p.name, "Loading your channels, movies and series…")
        if (Repo.loaded && Repo.profileId == p.id) {
            showLoaded()
            restoreLastScreen()
        } else load(false)
    }

    override fun onResume() {
        super.onResume()
        Prefs.lastScreen = Prefs.SCREEN_HOME
    }

    /** First load after launch: jump on to the screen the user left the app from. */
    /** Full-screen "Who's watching?": reload Home when the viewer changed. */
    private val whoIsWatching = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) recreate()
    }

    /** True while "Who's watching?" is open on start-up: stay on Home instead of jumping to the last screen. */
    private var askingViewer = false

    private fun restoreLastScreen() {
        if (askingViewer) return
        // Tapped a channel on the home-screen widget.
        intent.getStringExtra(EXTRA_PLAY_CHANNEL)?.let { id ->
            intent.removeExtra(EXTRA_PLAY_CHANNEL)
            intent.removeExtra(EXTRA_RESTORE)
            playChannelById(id)
            return
        }
        if (!intent.getBooleanExtra(EXTRA_RESTORE, false)) return
        intent.removeExtra(EXTRA_RESTORE)
        // "Start with the last channel": straight back to what was on.
        if (Prefs.startLastChannel) {
            Repo.recentChannelList(1).firstOrNull()?.let {
                playChannelById(it.id)
                return
            }
        }
        val target = when (Prefs.lastScreen) {
            Prefs.SCREEN_LIVE -> Intent(this, LiveActivity::class.java)
            Prefs.SCREEN_CATCHUP -> Intent(this, LiveActivity::class.java)
            Repo.SECTION_VOD -> if (Repo.movies.isNotEmpty())
                Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, Repo.SECTION_VOD) else null
            Repo.SECTION_SERIES -> if (Repo.series.isNotEmpty())
                Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, Repo.SECTION_SERIES) else null
            else -> null
        } ?: return
        startActivity(target.putExtra(EXTRA_RESTORE, true))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Series you follow (My list + those you watch): every 12 h, see whether the provider added
     * episodes. New ones get a "New episodes" row and a notification.
     */
    private fun checkNewEpisodes() {
        if (Repo.kidsMode || System.currentTimeMillis() - Prefs.lastEpisodeCheck < 12 * 3_600_000L) return
        Prefs.lastEpisodeCheck = System.currentTimeMillis()
        lifecycleScope.launch {
            val followed = (Repo.seriesIn(Repo.CAT_FAV) + Repo.recentSeries(15)).distinctBy { it.id }.take(25)
            val fresh = ArrayList<Series>()
            for (s in followed) {
                val total = try { Repo.episodes(s.id).values.sumOf { it.size } } catch (_: Exception) { continue }
                if (total <= 0) continue
                val before = Prefs.episodeCount(s.id)
                Prefs.setEpisodeCount(s.id, total)
                if (before in 0 until total) fresh.add(s)
            }
            if (fresh.isEmpty()) return@launch
            Prefs.newEpisodeSeries = Prefs.newEpisodeSeries + fresh.map { it.id }
            notifyNewEpisodes(fresh)
            buildRows()
            restoreFocus()
        }
    }

    private fun notifyNewEpisodes(list: List<Series>) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("episodes", Tr.s("New episodes"), android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = android.app.PendingIntent.getActivity(this, 77, Intent(this, SplashActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "episodes") else android.app.Notification.Builder(this)
            nm.notify(7703, b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle(Tr.s("New episodes"))
                .setContentText(list.joinToString(", ") { it.name })
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {
        }
    }

    /** Trending on TMDB this week that your provider has (needs a TMDB key); loaded once per start. */
    private fun loadTrending() {
        if (!com.streambox.tv.data.Tmdb.enabled || trending != null) return
        trending = emptyList()
        lifecycleScope.launch {
            val titles = com.streambox.tv.data.Tmdb.trendingTitles()
            if (titles.isEmpty()) return@launch
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                fun n(s: String) = com.streambox.tv.data.Tmdb.cleanTitle(s).first.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                val mi = Repo.moviesIn(Repo.CAT_ALL).groupBy { n(it.name) }
                val si = Repo.seriesIn(Repo.CAT_ALL).groupBy { n(it.name) }
                val out = LinkedHashSet<Any>()
                for ((t, isMovie) in titles) {
                    (if (isMovie) mi[n(t)] else si[n(t)])?.firstOrNull()?.let { out.add(it) }
                }
                out.toList()
            }
            trending = found
            if (found.isNotEmpty()) {
                buildRows()
                restoreFocus()
            }
        }
    }

    /** The current viewer's picture on the profile button (when there are several viewers). */
    private fun showViewerAvatar() {
        val b = findViewById<Button>(R.id.tileProfiles)
        if (Prefs.viewers(Repo.profileId).isEmpty()) return
        val id = Prefs.currentViewer(Repo.profileId)
        val av = if (id.isEmpty()) Prefs.mainViewerAvatar else Prefs.viewers(Repo.profileId).firstOrNull { it.id == id }?.avatar ?: 0
        b.text = ViewersActivity.AVATARS[av % ViewersActivity.AVATARS.size]
        b.textSize = 20f
        b.setCompoundDrawables(null, null, null, null)
    }

    /** Plays a channel, with the favourites (or its category) as the up/down zapping list. */
    private fun playChannelById(id: String) {
        val c = Repo.channel(id) ?: return
        val favs = Repo.favouriteChannels(200)
        val list = if (favs.any { it.id == id }) favs else Repo.channelsIn(c.categoryId).ifEmpty { listOf(c) }
        val idx = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        Ui.play(this, list.map { PlayItem("", it.name, Repo.urlFor(it), true, it.id) }, idx)
    }

    override fun onRestart() {
        super.onRestart()
        // Continue-watching and recent lists may have changed while we were away.
        if (Repo.loaded && Repo.profileId == profile.id) {
            buildRows()
            restoreFocus()
        }
    }

    // ------------------------------------------------------------------ Loading

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        nav.forEach { it.isEnabled = false }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                if (Repo.profileId != profile.id) Repo.close()
                Repo.load(this@HomeActivity, profile, force) { status.text = it }
                showLoaded()
                restoreLastScreen()
                if (force) toast("Content refreshed")
            } catch (e: Exception) {
                status.text = Ui.mask("Loading failed: ${e.message ?: "network error"}")
                setHeroIdle("Couldn't load", profile.name, "Check your connection, then press Refresh (top right) to try again.")
                findViewById<Button>(R.id.tileRefresh).requestFocus()
            } finally {
                loading = false
                nav.forEach { it.isEnabled = true }
                progress.visibility = View.GONE
            }
        }
    }

    private fun showLoaded() {
        val n = NumberFormat.getIntegerInstance()
        status.text = ""
        nav.forEach { it.isEnabled = true }

        val a = Repo.account
        val parts = ArrayList<String>()
        parts.add(profile.name)
        if (Prefs.viewers(profile.id).isNotEmpty()) parts.add((if (Repo.kidsMode) "🧒 " else "👤 ") + Repo.viewerName)
        if (a.status.isNotEmpty()) parts.add(a.status)
        if (a.expiryMs > 0) parts.add("Expires ${Ui.date(a.expiryMs)}")
        if (a.maxConnections.isNotEmpty()) parts.add("Connections ${a.activeConnections.ifEmpty { "0" }}/${a.maxConnections}")
        findViewById<TextView>(R.id.accountInfo).text = parts.joinToString("   •   ")

        buildRows()
        showViewerAvatar()
        loadTrending()
        checkNewEpisodes()
        // Several viewers: ask who's watching once per app start.
        if (!viewerAsked && Prefs.viewers(Repo.profileId).isNotEmpty()) {
            viewerAsked = true
            askingViewer = true
            whoIsWatching.launch(Intent(this, ViewersActivity::class.java))
        }
        // The Play Store build is updated by Google Play, never by the app itself.
        if (com.streambox.tv.BuildConfig.SELF_UPDATE) {
            com.streambox.tv.data.Updater.showWhatsNew(this, lifecycleScope)
            com.streambox.tv.data.Updater.checkDaily(this, lifecycleScope)
        }
        val first = rows.firstOrNull()?.cards?.firstOrNull()
        if (first != null) {
            showHero(first, rows[0].title)
            focusRow = 0
            focusCol = 0
            restoreFocus()
        } else {
            setHeroIdle(
                profile.name.uppercase(),
                "Ready to watch",
                "${n.format(Repo.channels.size)} channels  •  ${n.format(Repo.movies.size)} movies  •  ${n.format(Repo.series.size)} series"
            )
            findViewById<Button>(R.id.tileLive).requestFocus()
        }
    }

    private fun buildRows() {
        val out = ArrayList<Row>()
        fun movieCard(m: Movie, withProgress: Boolean = false): Card {
            val pr = if (withProgress) {
                val key = "vod_${Repo.userKey}_${m.id}"
                val pos = Prefs.position(key)
                val dur = Prefs.duration(key)
                // Older saves have no length: show a soft indicator that grows with time watched.
                if (dur > 0) (pos * 1000 / dur).toInt().coerceIn(30, 1000)
                else (pos / 60_000L * 9).toInt().coerceIn(60, 900)
            } else -1
            return Card(Kind.MOVIE, m.id, m.name, m.poster, rating(m.rating), pr)
        }
        fun seriesCard(s: Series) = Card(Kind.SERIES, s.id, s.name, s.poster, rating(s.rating))
        fun channelCard(c: Channel) = Card(Kind.CHANNEL, c.id, c.name, c.logo)

        Repo.continueWatching().takeIf { it.isNotEmpty() }?.let { list ->
            out.add(Row("Continue watching", list.map { r ->
                val pos = Prefs.position(r.key)
                val dur = Prefs.duration(r.key)
                val pr = if (dur > 0) (pos * 1000 / dur).toInt().coerceIn(30, 1000) else (pos / 60_000L * 9).toInt().coerceIn(60, 900)
                if (r.movie != null) Card(Kind.MOVIE, r.movie.id, r.movie.name, r.movie.poster, rating(r.movie.rating), pr)
                else Card(Kind.SERIES, r.series!!.id, r.series.name, r.series.poster, "", pr)
            }))
        }
        Repo.recentChannelList().takeIf { it.isNotEmpty() }
            ?.let { out.add(Row("Recently watched channels", it.map(::channelCard))) }
        // Followed series with episodes added since last time.
        Prefs.newEpisodeSeries.mapNotNull { Repo.seriesItem(it) }.takeIf { it.isNotEmpty() }
            ?.let { out.add(0, Row("New episodes", it.map { s -> Card(Kind.SERIES, s.id, s.name, s.poster, "🆕") })) }
        // Downloaded films and episodes, one click to watch offline (not for Kids profiles).
        if (!Repo.kidsMode) {
            com.streambox.tv.data.Downloads.all()
                .filter { it.profileId == Repo.profileId && it.status == com.streambox.tv.data.Downloads.DONE && java.io.File(it.file).exists() }
                .sortedByDescending { it.addedAt }.take(30)
                .takeIf { it.isNotEmpty() }
                ?.let { list -> out.add(Row("Downloaded", list.map { Card(Kind.DOWNLOAD, it.id, it.title, it.poster, "⬇") })) }
        }
        Repo.becauseYouWatchedMovie()?.let { (m, list) -> out.add(Row("Because you watched ${m.name}", list.map { movieCard(it) })) }
        Repo.becauseYouWatchedSeries()?.let { (s, list) -> out.add(Row("Because you watched ${s.name}", list.map(::seriesCard))) }
        trending?.takeIf { it.isNotEmpty() }?.let { list ->
            out.add(Row("Trending this week", list.map { if (it is Movie) movieCard(it) else seriesCard(it as Series) }))
        }
        Repo.watchLater().takeIf { it.isNotEmpty() }?.let { list ->
            out.add(Row("Watch later", list.map { if (it is Movie) movieCard(it) else seriesCard(it as Series) }))
        }
        // "New this week" when the provider added something in the last 7 days, else the newest.
        Repo.newThisWeek().takeIf { it.isNotEmpty() }?.let { out.add(Row("New this week: movies", it.map { m -> movieCard(m) })) }
            ?: Repo.recentlyAddedMovies().takeIf { it.isNotEmpty() }?.let { out.add(Row("New movies", it.map { m -> movieCard(m) })) }
        Repo.newSeriesThisWeek().takeIf { it.isNotEmpty() }?.let { out.add(Row("New this week: series", it.map(::seriesCard))) }
            ?: Repo.recentlyAddedSeries().takeIf { it.isNotEmpty() }?.let { out.add(Row("New series", it.map(::seriesCard))) }
        Repo.recentSeries().takeIf { it.isNotEmpty() }
            ?.let { out.add(Row("Your series", it.map(::seriesCard))) }
        Repo.favouriteChannels().takeIf { it.isNotEmpty() }?.let { favs ->
            // With "Number buttons: favourites", show each channel's number (1 = first favourite).
            out.add(Row("Favourite channels", favs.mapIndexed { i, c ->
                if (Prefs.numberKeysFavourites && i < 99) Card(Kind.CHANNEL, c.id, "${i + 1} · ${c.name}", c.logo) else channelCard(c)
            }))
        }
        ChannelWidget.save(this, Repo.favouriteChannels(6))
        Repo.moviesIn(Repo.CAT_FAV).take(30).takeIf { it.isNotEmpty() }
            ?.let { out.add(Row("My list", it.map { m -> movieCard(m) })) }
        if (out.none { r -> r.cards.any { it.kind == Kind.CHANNEL } }) {
            Repo.channelsIn(Repo.CAT_ALL).take(30).takeIf { it.isNotEmpty() }
                ?.let { out.add(Row("Live channels", it.map(::channelCard))) }
        }
        rows = out
        rowsView.adapter?.notifyDataSetChanged()
    }

    private fun rating(r: String): String {
        val v = r.toDoubleOrNull() ?: return ""
        if (v <= 0) return ""
        return "★ " + if (v >= 10) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
    }

    private fun restoreFocus() {
        if (rows.isEmpty()) return
        val r = focusRow.coerceIn(0, rows.size - 1)
        val c = focusCol.coerceIn(0, rows[r].cards.size - 1)
        rowsView.scrollToPosition(r)
        rowsView.post {
            val rowHolder = rowsView.findViewHolderForAdapterPosition(r) as? RowHolder ?: return@post
            rowHolder.list.scrollToPosition(c)
            rowHolder.list.post { rowHolder.list.findViewHolderForAdapterPosition(c)?.itemView?.requestFocus() }
        }
    }

    // ------------------------------------------------------------------ Hero

    private var heroFitStep = 0

    private fun resetHeroFit() {
        if (heroFitStep == 0) return
        heroFitStep = 0
        heroEyebrow.visibility = View.VISIBLE
        findViewById<View>(R.id.accountInfo).visibility = View.VISIBLE
        heroPlot.visibility = View.VISIBLE
        heroPlot.maxLines = 3
        heroTitle.maxLines = 2
    }

    private fun fitHero() {
        val hero = findViewById<android.view.ViewGroup>(R.id.hero) ?: return
        var need = hero.paddingTop + hero.paddingBottom
        for (i in 0 until hero.childCount) {
            val c = hero.getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as android.view.ViewGroup.MarginLayoutParams
            need += c.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        if (need <= hero.height || heroFitStep >= 6) return
        heroFitStep++
        when (heroFitStep) {
            1 -> findViewById<View>(R.id.accountInfo).visibility = View.GONE
            2 -> heroPlot.maxLines = 2
            3 -> heroEyebrow.visibility = View.GONE
            4 -> heroPlot.maxLines = 1
            5 -> heroTitle.maxLines = 1
            6 -> heroPlot.visibility = View.GONE
        }
        hero.requestLayout()
    }

    private fun setHeroIdle(eyebrow: String, title: String, plot: String) {
        heroEyebrow.text = eyebrow
        heroTitle.text = title
        heroMeta.text = ""
        heroPlot.text = plot
        setHeroImage("")
    }

    private fun showHero(card: Card, rowTitle: String) {
        heroJob?.cancel()
        heroEyebrow.text = when (card.kind) {
            Kind.CHANNEL -> "● Live  •  $rowTitle"
            else -> rowTitle
        }
        heroTitle.text = card.title
        heroMeta.text = ""
        heroPlot.text = ""
        when (card.kind) {
            Kind.DOWNLOAD -> {
                heroMeta.text = Tr.s("Ready to watch offline")
                setHeroImage(card.image)
            }
            Kind.MOVIE -> {
                val m = Repo.movie(card.id) ?: return
                val cached = Repo.cachedMovieInfo(m)
                if (cached != null) applyMovieInfo(m, cached) else {
                    heroMeta.text = card.badge
                    setHeroImage(m.poster)
                }
                if (cached == null && Repo.profile?.isXtream == true) {
                    heroJob = lifecycleScope.launch {
                        delay(450) // wait until focus settles before asking the server
                        val info = Repo.movieInfo(m)
                        if (heroTitle.text == card.title) applyMovieInfo(m, info)
                    }
                }
            }
            Kind.SERIES -> {
                val s = Repo.seriesItem(card.id) ?: return
                heroMeta.text = metaLine(card.badge, s.releaseDate.take(4), s.genre)
                heroPlot.text = s.plot
                setHeroImage(s.poster)
            }
            Kind.CHANNEL -> {
                val c = Repo.channel(card.id) ?: return
                heroMeta.text = ""
                setHeroImage("")
                if (Repo.hasEpgSource) {
                    heroJob = lifecycleScope.launch {
                        delay(300)
                        val list = Repo.upcoming(this@HomeActivity, c)
                        if (heroTitle.text != card.title) return@launch
                        list.getOrNull(0)?.let { pr ->
                            heroMeta.text = metaLine("Now  ${Ui.time(pr.startMs)} – ${Ui.time(pr.endMs)}")
                            heroPlot.text = pr.title + if (pr.desc.isNotBlank()) " — ${pr.desc}" else ""
                        }
                        list.getOrNull(1)?.let { pr ->
                            if (heroPlot.text.isEmpty()) heroPlot.text = "Next: ${pr.title}"
                        }
                    }
                }
            }
        }
    }

    private fun applyMovieInfo(m: Movie, info: com.streambox.tv.data.MovieInfo) {
        val r = rating(info.rating.ifBlank { m.rating })
        heroMeta.text = metaLine(r, info.releaseDate.take(4), info.duration, info.genre)
        heroPlot.text = info.plot
        setHeroImage(info.backdrop.ifBlank { m.poster })
    }

    private fun metaLine(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString("    ")

    /** Cross-fades to [url] with a slow Ken Burns zoom; blank fades the image out. */
    private fun setHeroImage(url: String) {
        if (url == heroUrl) return
        heroUrl = url
        val token = ++heroToken
        if (url.isBlank()) {
            heroA.animate().alpha(0f).setDuration(400).start()
            heroB.animate().alpha(0f).setDuration(400).start()
            return
        }
        lifecycleScope.launch {
            delay(180) // skip images for titles the user only scrolls past
            if (token != heroToken) return@launch
            val result = imageLoader.execute(
                ImageRequest.Builder(this@HomeActivity).data(url).size(1280, 720).allowHardware(true).build()
            )
            val d = (result as? SuccessResult)?.drawable ?: return@launch
            if (token != heroToken) return@launch
            val (show, hide) = if (heroA.alpha >= heroB.alpha) heroB to heroA else heroA to heroB
            show.animate().cancel()
            show.setImageDrawable(d)
            show.scaleX = 1.0f
            show.scaleY = 1.0f
            show.animate().alpha(0.85f).setDuration(400).start()
            hide.animate().cancel()
            hide.animate().alpha(0f).setDuration(400).start()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // The remote's search / mic key opens search with voice input.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && Repo.loaded &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH || event.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST)
        ) {
            startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_VOICE, true))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ Actions

    /** Hold OK on a card: remove it from Continue watching / Watch later, or manage a download. */
    private fun cardMenu(card: Card, row: Row) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        when {
            row.title == "Continue watching" && card.kind != Kind.DOWNLOAD -> opts.add("✕  Remove from Continue watching" to {
                if (card.kind == Kind.MOVIE) Repo.forgetProgress(Repo.movie(card.id), null)
                else Repo.forgetProgress(null, Repo.seriesItem(card.id))
                buildRows()
                restoreFocus()
            })
            row.title == "Watch later" -> opts.add("✕  Remove from Watch later" to {
                Prefs.setWatchLater(Repo.userKey, (if (card.kind == Kind.MOVIE) "m:" else "s:") + card.id, false)
                buildRows()
                restoreFocus()
            })
            card.kind == Kind.DOWNLOAD -> {
                opts.add("🗑  Delete download" to {
                    com.streambox.tv.data.Downloads.delete(this, card.id)
                    buildRows()
                    restoreFocus()
                })
                opts.add("⬇  Open Downloads" to { startActivity(Intent(this, DownloadsActivity::class.java)) })
            }
        }
        if (opts.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(card.title)
            .setItems(opts.map { Tr.s(it.first) }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    private fun open(card: Card, row: Row) {
        when (card.kind) {
            Kind.DOWNLOAD -> {
                val d = com.streambox.tv.data.Downloads.get(card.id) ?: return
                val f = java.io.File(d.file)
                if (!f.exists()) {
                    toast("The file is missing (USB drive unplugged?)")
                    return
                }
                Ui.play(this, listOf(PlayItem(d.key, d.title, com.streambox.tv.data.Downloads.playUri(f), false)), 0)
            }
            Kind.MOVIE -> startActivity(Intent(this, MovieDetailActivity::class.java).putExtra(MovieDetailActivity.EXTRA_ID, card.id))
            Kind.SERIES -> startActivity(Intent(this, SeriesDetailActivity::class.java).putExtra(SeriesDetailActivity.EXTRA_ID, card.id))
            Kind.CHANNEL -> {
                val channels = row.cards.filter { it.kind == Kind.CHANNEL }.mapNotNull { Repo.channel(it.id) }
                val idx = channels.indexOfFirst { it.id == card.id }.coerceAtLeast(0)
                Ui.play(this, channels.map { PlayItem("", it.name, Repo.urlFor(it), true, it.id) }, idx)
            }
        }
    }

    // ------------------------------------------------------------------ Adapters

    private class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val h = RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false))
            h.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            h.list.setRecycledViewPool(pool)
            h.list.itemAnimator = null
            return h
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.list.adapter = CardsAdapter(row, position)
        }
    }

    private class CardHolder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val fallback: TextView = v.findViewById(R.id.fallbackTitle)
        val badge: TextView = v.findViewById(R.id.badge)
        val resume: ProgressBar = v.findViewById(R.id.resume)
    }

    private inner class CardsAdapter(val row: Row, val rowIndex: Int) : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = row.cards.size
        override fun getItemViewType(position: Int) = if (row.cards[position].kind == Kind.CHANNEL) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val layout = if (viewType == 1) R.layout.item_home_channel else R.layout.item_home_poster
            return CardHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = row.cards[position]
            val isChannel = card.kind == Kind.CHANNEL
            if (card.image.isBlank()) {
                holder.image.setImageDrawable(null)
                holder.fallback.visibility = View.VISIBLE
            } else {
                holder.fallback.visibility = if (isChannel) View.VISIBLE else View.GONE
                // Poster that fails to load: show the title instead of an empty tile.
                holder.image.loadUrl(card.image) { if (holder.fallback.text == card.title) holder.fallback.visibility = View.VISIBLE }
            }
            if (!isChannel) {
                holder.image.setBackgroundResource(if (card.image.isBlank()) R.drawable.placeholder else R.drawable.bg_round_clip)
            }
            holder.fallback.text = card.title
            if (!isChannel) {
                holder.badge.text = card.badge
                holder.badge.visibility = if (card.badge.isEmpty()) View.GONE else View.VISIBLE
            }
            holder.resume.visibility = if (card.progress >= 0) View.VISIBLE else View.GONE
            if (card.progress >= 0) holder.resume.progress = card.progress

            holder.itemView.setOnClickListener { open(card, row) }
            holder.itemView.setOnLongClickListener { cardMenu(card, row); true }
            holder.itemView.setOnFocusChangeListener { _, has ->
                if (has) {
                    focusRow = rowIndex
                    focusCol = holder.bindingAdapterPosition.coerceAtLeast(0)
                    showHero(card, row.title)
                    rowsView.smoothScrollToPosition(rowIndex)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        /** "Who's watching?" is asked once per app start. */
        private var viewerAsked = false
        const val EXTRA_RESTORE = "restore"
        const val EXTRA_PLAY_CHANNEL = "play_channel"
        /** Trending titles found at the provider (per app start). */
        private var trending: List<Any>? = null
    }
}
