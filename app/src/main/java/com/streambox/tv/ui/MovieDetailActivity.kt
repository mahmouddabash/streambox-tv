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
    private val key: String get() = "vod_${Repo.profileId}_${movie.id}"

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
        val meta = findViewById<TextView>(R.id.meta)
        val plot = findViewById<TextView>(R.id.plot)

        findViewById<TextView>(R.id.title).text = m.name
        findViewById<ImageView>(R.id.poster).loadUrl(m.poster)
        meta.text = if (m.rating.isNotBlank() && m.rating != "0") "★ ${m.rating}" else ""

        val btnPlay = findViewById<Button>(R.id.btnPlay)
        btnPlay.setOnClickListener { play(fromStart = true) }
        btnResume.setOnClickListener { play(fromStart = false) }
        btnFav.setOnClickListener {
            Prefs.toggleFavourite(Repo.profileId, Prefs.FAV_VOD, m.id)
            updateFav()
        }
        updateFav()
        btnPlay.requestFocus()

        lifecycleScope.launch {
            val info = Repo.movieInfo(m)
            val parts = ArrayList<String>()
            val rating = info.rating.ifBlank { m.rating }
            if (rating.isNotBlank() && rating != "0") parts.add("★ $rating")
            if (info.releaseDate.isNotBlank()) parts.add(info.releaseDate)
            if (info.duration.isNotBlank()) parts.add(info.duration)
            if (info.genre.isNotBlank()) parts.add(info.genre)
            meta.text = parts.joinToString("   •   ")
            val sb = StringBuilder(info.plot)
            if (info.director.isNotBlank()) sb.append("\n\nDirector: ").append(info.director)
            if (info.cast.isNotBlank()) sb.append("\n\nCast: ").append(info.cast)
            plot.text = sb.toString().trim()
        }
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
        btnFav.text = if (Prefs.isFavourite(Repo.profileId, Prefs.FAV_VOD, movie.id)) "★ In favourites" else "☆ Favourite"
    }

    private fun play(fromStart: Boolean) {
        Ui.play(this, listOf(PlayItem(key, movie.name, Repo.urlFor(movie), false)), 0, fromStart)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
