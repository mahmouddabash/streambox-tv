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

    private lateinit var show: Series
    private lateinit var btnFav: Button
    private lateinit var status: TextView
    private lateinit var seasonAdapter: SimpleAdapter<Int>
    private lateinit var episodeAdapter: SimpleAdapter<Episode>
    private var seasons: Map<Int, List<Episode>> = emptyMap()

    private fun key(e: Episode) = "ep_${Repo.profileId}_${e.id}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Repo.seriesItem(intent.getStringExtra(EXTRA_ID) ?: "")
        if (s == null) {
            finish()
            return
        }
        show = s
        setContentView(R.layout.activity_series_detail)
        btnFav = findViewById(R.id.btnFav)
        status = findViewById(R.id.status)

        findViewById<TextView>(R.id.title).text = s.name
        findViewById<ImageView>(R.id.poster).loadUrl(s.poster)
        findViewById<TextView>(R.id.plot).text = s.plot
        val parts = ArrayList<String>()
        if (s.rating.isNotBlank() && s.rating != "0") parts.add("★ ${s.rating}")
        if (s.releaseDate.isNotBlank()) parts.add(s.releaseDate)
        if (s.genre.isNotBlank()) parts.add(s.genre)
        findViewById<TextView>(R.id.meta).text = parts.joinToString("   •   ")

        btnFav.setOnClickListener {
            Prefs.toggleFavourite(Repo.profileId, Prefs.FAV_SERIES, s.id)
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
                v.findViewById<TextView>(R.id.line1).text = "${e.num}. ${e.title}$resume"
                val sub = listOf(e.duration, e.plot).filter { it.isNotBlank() }.joinToString("  •  ")
                val l2 = v.findViewById<TextView>(R.id.line2)
                l2.text = sub
                l2.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            },
            onClick = { _, pos ->
                val list = episodeAdapter.items
                Ui.play(this, list.map { PlayItem(key(it), "${s.name} – S${it.season}E${it.num} ${it.title}", it.url, false) }, pos)
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
                status.text = "Could not load episodes: ${e.message ?: "network error"}"
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Show updated resume markers after coming back from the player.
        if (::episodeAdapter.isInitialized) episodeAdapter.submit(episodeAdapter.items)
    }

    private fun updateFav() {
        btnFav.text = if (Prefs.isFavourite(Repo.profileId, Prefs.FAV_SERIES, show.id)) "★ In favourites" else "☆ Favourite"
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
