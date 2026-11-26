package com.streambox.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streambox.tv.R
import com.streambox.tv.data.Movie
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import kotlinx.coroutines.launch

class MovieDetailActivity : AppCompatActivity() {

    private lateinit var movie: Movie
    private lateinit var btnResume: Button
    private lateinit var btnFav: Button
    private val key: String get() = "vod_${Repo.userKey}_${movie.id}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = Repo.movie(intent.getStringExtra(EXTRA_ID) ?: "")
        if (m == null) {
            finish()
            return
        }
        movie = m
        setContentView(R.layout.activity_movie_detail)
        btnResume = findViewById(R.id.btnResume)
        btnFav = findViewById(R.id.btnFav)
        intent.getStringExtra(EXTRA_ID)?.let { Ui.addWatchLaterButton(this, btnFav, "m:$it") }
        val meta = findViewById<TextView>(R.id.meta)
        val plot = findViewById<TextView>(R.id.plot)

        findViewById<TextView>(R.id.title).text = m.name
        findViewById<ImageView>(R.id.poster).loadUrl(m.poster)
        val backdrop = findViewById<ImageView>(R.id.backdrop)
        Ui.backdrop(backdrop, Repo.cachedMovieInfo(m)?.backdrop?.ifBlank { null } ?: m.poster)
        meta.text = if (m.rating.isNotBlank() && m.rating != "0") "★ ${m.rating}" else ""

        val btnPlay = findViewById<Button>(R.id.btnPlay)
        btnPlay.setOnClickListener { play(fromStart = true) }
        btnResume.setOnClickListener { play(fromStart = false) }
        btnFav.setOnClickListener {
            Prefs.toggleFavourite(Repo.userKey, Prefs.FAV_VOD, m.id)
            updateFav()
        }
        updateFav()
        val btnDownload = findViewById<Button>(R.id.btnDownload)
        fun updateDownload() {
            val d = com.streambox.tv.data.Downloads.forKey(key)
            btnDownload.text = when (d?.status) {
                com.streambox.tv.data.Downloads.DONE -> "✓  Downloaded"
                null, com.streambox.tv.data.Downloads.FAILED -> "⬇  Download"
                else -> "⬇  In downloads"
            }
        }
        updateDownload()
        btnDownload.setOnClickListener {
            if (com.streambox.tv.data.Downloads.forKey(key)?.status.let { it != null && it != com.streambox.tv.data.Downloads.FAILED }) {
                startActivity(android.content.Intent(this, DownloadsActivity::class.java))
            } else {
                com.streambox.tv.data.Downloads.add(this, key, m.name, m.poster, Repo.urlFor(m))
                toast("Added to downloads")
                updateDownload()
            }
        }
        btnPlay.requestFocus()

        val btnTrailer = findViewById<Button>(R.id.btnTrailer)
        lifecycleScope.launch {
            val info = Repo.movieInfo(m)
            var trailer = info.trailer
            if (trailer.isNotEmpty()) {
                btnTrailer.visibility = View.VISIBLE
                btnTrailer.setOnClickListener { Ui.openTrailer(this@MovieDetailActivity, trailer) }
            }
            // Optional TMDB enrichment: HD backdrop, cast photos, trailer.
            launch {
                val t = com.streambox.tv.data.Tmdb.details(true, info.tmdbId, m.name) ?: return@launch
                if (t.backdrop.isNotBlank()) Ui.backdrop(backdrop, t.backdrop)
                if (trailer.isEmpty() && t.trailer.isNotEmpty()) {
                    trailer = t.trailer
                    btnTrailer.visibility = View.VISIBLE
                    btnTrailer.setOnClickListener { Ui.openTrailer(this@MovieDetailActivity, t.trailer) }
                }
                if (plot.text.isNullOrBlank() && t.overview.isNotBlank()) plot.text = t.overview
                if (t.cast.isNotEmpty()) showCast(t.cast)
            }
            val parts = ArrayList<String>()
            val rating = info.rating.ifBlank { m.rating }
            if (rating.isNotBlank() && rating != "0") parts.add("★ $rating")
            if (info.releaseDate.isNotBlank()) parts.add(info.releaseDate)
            if (info.duration.isNotBlank()) parts.add(info.duration)
            if (info.genre.isNotBlank()) parts.add(info.genre)
            meta.text = parts.joinToString("   •   ")
            if (info.backdrop.isNotBlank()) Ui.backdrop(backdrop, info.backdrop)
            val sb = StringBuilder(info.plot)
            if (info.director.isNotBlank()) sb.append("\n\nDirector: ").append(info.director)
            if (info.cast.isNotBlank()) sb.append("\n\nCast: ").append(info.cast)
            plot.text = sb.toString().trim()
        }
    }

    private fun showCast(cast: List<com.streambox.tv.data.Tmdb.Person>) {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.cast)
        findViewById<TextView>(R.id.castTitle).visibility = View.VISIBLE
        list.visibility = View.VISIBLE
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        list.adapter = SimpleAdapter<com.streambox.tv.data.Tmdb.Person>(
            R.layout.item_cast,
            bind = { v, p, _ ->
                val img = v.findViewById<ImageView>(R.id.photo)
                if (p.photo.isNotBlank()) img.loadUrl(p.photo) else img.setImageDrawable(null)
                v.findViewById<TextView>(R.id.name).text = p.name
                v.findViewById<TextView>(R.id.role).text = p.role
            }
        ).also { a -> a.submit(cast) }
    }

    override fun onResume() {
        super.onResume()
        if (!::movie.isInitialized) return
        val pos = Prefs.position(key)
        if (pos > 0) {
            btnResume.visibility = View.VISIBLE
            btnResume.text = "Resume from ${Ui.duration(pos)}"
        } else {
            btnResume.visibility = View.GONE
        }
    }

    private fun updateFav() {
        btnFav.text = if (Prefs.isFavourite(Repo.userKey, Prefs.FAV_VOD, movie.id)) "✓  In my list" else "+  My list"
    }

    private fun play(fromStart: Boolean) {
        // Downloaded copy plays offline; otherwise stream.
        val offline = com.streambox.tv.data.Downloads.offlineFile(key)
        val url = offline?.let { com.streambox.tv.data.Downloads.playUri(it) } ?: Repo.urlFor(movie)
        Ui.play(this, listOf(PlayItem(key, movie.name, url, false)), 0, fromStart)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
