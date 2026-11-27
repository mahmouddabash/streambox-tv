package com.streambox.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Episode
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import com.streambox.tv.data.Series
import kotlinx.coroutines.launch

class SeriesDetailActivity : AppCompatActivity() {
    private var seriesCast: List<String> = emptyList()
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private lateinit var show: Series
    private lateinit var btnFav: Button
    private lateinit var status: TextView
    private lateinit var seasonAdapter: SimpleAdapter<Int>
    private lateinit var episodeAdapter: SimpleAdapter<Episode>
    private var seasons: Map<Int, List<Episode>> = emptyMap()

    private fun epTitle(series: String, e: Episode): String {
        // Many providers already name episodes "S01 E05": don't repeat it.
        val t = e.title.trim()
        val hasCode = Regex("(?i)\\bS\\d+\\s*E\\d+").containsMatchIn(t)
        return if (hasCode) "$series – $t" else "$series – S${e.season}E${e.num}" + (if (t.isNotEmpty()) " $t" else "")
    }

    /** Downloaded episodes play offline. */
    private fun playUrl(e: Episode): String =
        com.streambox.tv.data.Downloads.offlineFile(key(e))?.let { com.streambox.tv.data.Downloads.playUri(it) } ?: e.url

    private fun key(e: Episode) = "ep_${Repo.userKey}_${e.id}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Repo.seriesItem(intent.getStringExtra(EXTRA_ID) ?: "")
        // Opened: no longer "new".
        intent.getStringExtra(EXTRA_ID)?.let { id -> if (id in Prefs.newEpisodeSeries) Prefs.newEpisodeSeries = Prefs.newEpisodeSeries - id }
        if (s != null) Prefs.pushRecent("recentSeries_${Repo.userKey}", s.id)
        if (s == null) {
            finish()
            return
        }
        show = s
        setContentView(R.layout.activity_series_detail)
        btnFav = findViewById(R.id.btnFav)
        intent.getStringExtra(EXTRA_ID)?.let {
            Ui.addMoreButton(this, btnFav, "s:$it", absorbAnchor = true, extras = {
                // Cast: from TMDB when available, else the provider's cast line.
                val names = seriesCast.ifEmpty { s.cast.split(',', '،', '/').map { n -> n.trim() }.filter { n -> n.length >= 2 }.take(15) }
                if (names.isEmpty()) emptyList() else listOf(Tr.s("🎭  Cast") to {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(Tr.s("🎭  Cast"))
                        .setItems(names.toTypedArray()) { _, w -> Ui.openActor(this, names[w]) }
                        .show()
                })
            })
        }
        status = findViewById(R.id.status)

        findViewById<TextView>(R.id.title).text = s.name
        findViewById<ImageView>(R.id.poster).loadUrl(s.poster)
        Ui.backdrop(findViewById(R.id.backdrop), s.poster)
        findViewById<TextView>(R.id.plot).text = s.plot
        val parts = ArrayList<String>()
        if (s.rating.isNotBlank() && s.rating != "0") parts.add("★ ${s.rating}")
        if (s.releaseDate.isNotBlank()) parts.add(s.releaseDate)
        if (s.genre.isNotBlank()) parts.add(s.genre)
        findViewById<TextView>(R.id.meta).text = parts.joinToString("   •   ")

        findViewById<android.widget.Button>(R.id.btnDownload).setOnClickListener {
            val list = episodeAdapter.items
            if (list.isEmpty()) return@setOnClickListener
            // Pick the episodes to save (all are ticked; untick the ones you don't want).
            val labels = list.map { e ->
                val st = com.streambox.tv.data.Downloads.forKey(key(e))?.status
                "E${e.num}  ${e.title}" + when (st) {
                    com.streambox.tv.data.Downloads.DONE -> "   ✓"
                    null, com.streambox.tv.data.Downloads.FAILED -> ""
                    else -> "   ⬇"
                }
            }.toTypedArray()
            val checked = BooleanArray(list.size) { com.streambox.tv.data.Downloads.forKey(key(list[it])).let { d -> d == null || d.status == com.streambox.tv.data.Downloads.FAILED } }
            val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Choose episodes to download")
                .setMultiChoiceItems(labels, checked) { _, w, on -> checked[w] = on }
                .setPositiveButton("⬇  Download") { _, _ ->
                    var added = 0
                    list.forEachIndexed { i, e ->
                        if (!checked[i]) return@forEachIndexed
                        Prefs.setEpisodeSeries(key(e), s.id)
                        if (com.streambox.tv.data.Downloads.add(this, key(e), epTitle(s.name, e), s.poster, e.url)) added++
                    }
                    toast(if (added > 0) "$added episodes added to downloads" else "Nothing new to download")
                    episodeAdapter.notifyDataSetChanged()
                }
                .setNeutralButton("Select none", null)
                .setNegativeButton("Cancel", null)
                .show()
            // "Select none" / "Select all" toggles every tick without closing the dialog.
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { b ->
                val any = checked.any { it }
                for (i in checked.indices) {
                    checked[i] = !any
                    dlg.listView.setItemChecked(i, !any)
                }
                (b as android.widget.Button).text = Tr.s(if (any) "Select all" else "Select none")
            }
        }
        val btnTrailer = findViewById<android.widget.Button>(R.id.btnTrailer)
        fun showTrailer(id: String) {
            if (id.isEmpty() || btnTrailer.visibility == View.VISIBLE) return
            btnTrailer.visibility = View.VISIBLE
            btnTrailer.setOnClickListener { Ui.openTrailer(this, id) }
        }
        lifecycleScope.launch {
            // Extras arrive with the episode list; wait for them, then ask TMDB if enabled.
            var extra: com.streambox.tv.data.MovieInfo? = null
            repeat(40) {
                extra = Repo.seriesExtras[s.id]
                if (extra != null) return@repeat
                kotlinx.coroutines.delay(250)
            }
            extra?.let { e ->
                if (e.backdrop.isNotBlank()) Ui.backdrop(findViewById(R.id.backdrop), e.backdrop)
                showTrailer(e.trailer)
            }
            val t = com.streambox.tv.data.Tmdb.details(false, extra?.tmdbId ?: "", s.name) ?: return@launch
            if (t.backdrop.isNotBlank()) Ui.backdrop(findViewById(R.id.backdrop), t.backdrop)
            showTrailer(t.trailer)
            seriesCast = t.cast.map { it.name }.filter { it.isNotBlank() }.take(15)
        }

        btnFav.setOnClickListener {
            Prefs.toggleFavourite(Repo.userKey, Prefs.FAV_SERIES, s.id)
            updateFav()
        }
        updateFav()

        seasonAdapter = SimpleAdapter(
            R.layout.item_chip,
            bind = { v, n, _ -> (v as TextView).text = "Season $n" },
            onClick = { n, pos ->
                seasonAdapter.select(pos)
                episodeAdapter.submit(seasons[n] ?: emptyList())
            }
        )
        val seasonsView = findViewById<RecyclerView>(R.id.seasons)
        seasonsView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonsView.adapter = seasonAdapter

        episodeAdapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, e, _ ->
                val pos = Prefs.position(key(e))
                val resume = if (pos > 0) "   ▶ ${Ui.duration(pos)}" else ""
                val dl = when (com.streambox.tv.data.Downloads.forKey(key(e))?.status) {
                    com.streambox.tv.data.Downloads.DONE -> "   ✓ offline"
                    null, com.streambox.tv.data.Downloads.FAILED -> ""
                    else -> "   ⬇"
                }
                v.findViewById<TextView>(R.id.line1).text = "${e.num}. ${e.title}$resume$dl"
                val sub = listOf(e.duration, e.plot).filter { it.isNotBlank() }.joinToString("  •  ")
                val l2 = v.findViewById<TextView>(R.id.line2)
                l2.text = sub
                l2.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            },
            onClick = { _, pos ->
                val list = episodeAdapter.items
                list.forEach { Prefs.setEpisodeSeries(key(it), s.id) }
                Ui.play(this, list.map { PlayItem(key(it), epTitle(s.name, it), playUrl(it), false) }, pos)
            },
            onLongClick = { e, _ ->
                Prefs.setEpisodeSeries(key(e), s.id)
                val added = com.streambox.tv.data.Downloads.add(this, key(e), epTitle(s.name, e), s.poster, e.url)
                toast(if (added) "Added to downloads" else "Already in downloads")
                episodeAdapter.refresh(episodeAdapter.items.indexOf(e))
                true
            }
        )
        val episodesView = findViewById<RecyclerView>(R.id.episodes)
        episodesView.layoutManager = LinearLayoutManager(this)
        episodesView.itemAnimator = null
        episodesView.adapter = episodeAdapter

        lifecycleScope.launch {
            try {
                seasons = Repo.episodes(s.id)
                if (seasons.isEmpty()) {
                    status.text = "No episodes found"
                } else {
                    status.visibility = View.GONE
                    val numbers = seasons.keys.toList()
                    seasonAdapter.submit(numbers, 0)
                    episodeAdapter.submit(seasons[numbers[0]] ?: emptyList())
                    episodesView.post { episodesView.requestFocus() }
                }
            } catch (e: Exception) {
                status.text = Ui.mask("Could not load episodes: ${e.message ?: "network error"}")
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Show updated resume markers after coming back from the player.
        if (::episodeAdapter.isInitialized) episodeAdapter.submit(episodeAdapter.items)
    }

    private fun updateFav() {
        btnFav.text = if (Prefs.isFavourite(Repo.userKey, Prefs.FAV_SERIES, show.id)) "✓  In my list" else "+  My list"
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
