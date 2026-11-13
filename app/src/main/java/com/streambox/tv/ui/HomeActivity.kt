package com.streambox.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streambox.tv.R
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Profile
import com.streambox.tv.data.Repo
import kotlinx.coroutines.launch
import java.text.NumberFormat

class HomeActivity : AppCompatActivity() {

    private lateinit var profile: Profile
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tiles: List<Button>
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val id = intent.getStringExtra(EXTRA_PROFILE)
        val p = Prefs.profiles().firstOrNull { it.id == id }
        if (p == null) {
            finish()
            return
        }
        profile = p
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        findViewById<TextView>(R.id.profileName).text = p.name

        val live = findViewById<Button>(R.id.tileLive)
        val movies = findViewById<Button>(R.id.tileMovies)
        val series = findViewById<Button>(R.id.tileSeries)
        val catchup = findViewById<Button>(R.id.tileCatchup)
        val refresh = findViewById<Button>(R.id.tileRefresh)
        tiles = listOf(live, movies, series, catchup, refresh)

        live.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        movies.setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, Repo.SECTION_VOD))
        }
        series.setOnClickListener {
            if (Repo.series.isEmpty()) toast("This profile has no series")
            else startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, Repo.SECTION_SERIES))
        }
        catchup.setOnClickListener {
            if (Repo.channels.none { it.hasArchive }) toast("Your provider offers no catch-up channels")
            else startActivity(Intent(this, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_ARCHIVE_ONLY, true))
        }
        refresh.setOnClickListener { load(true) }
        findViewById<Button>(R.id.tileSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.tileProfiles).setOnClickListener { finish() }

        if (Repo.loaded && Repo.profileId == p.id) showLoaded() else load(false)
    }

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        tiles.forEach { it.isEnabled = false }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                if (Repo.profileId != profile.id) Repo.close()
                Repo.load(this@HomeActivity, profile, force) { status.text = it }
                showLoaded()
                if (force) toast("Content refreshed")
            } catch (e: Exception) {
                status.text = "Loading failed: ${e.message ?: "network error"}"
                findViewById<Button>(R.id.tileRefresh).isEnabled = true
                findViewById<Button>(R.id.tileRefresh).text = "Try again"
            } finally {
                loading = false
                progress.visibility = View.GONE
            }
        }
    }

    private fun showLoaded() {
        val n = NumberFormat.getIntegerInstance()
        status.text = ""
        tiles.forEach { it.isEnabled = true }
        findViewById<Button>(R.id.tileLive).text = "LIVE TV\n${n.format(Repo.channels.size)} channels"
        findViewById<Button>(R.id.tileMovies).text = "MOVIES\n${n.format(Repo.movies.size)} titles"
        findViewById<Button>(R.id.tileSeries).text = "SERIES\n${n.format(Repo.series.size)} shows"
        findViewById<Button>(R.id.tileRefresh).text = "Refresh content"

        val a = Repo.account
        val parts = ArrayList<String>()
        if (a.status.isNotEmpty()) parts.add(a.status)
        if (a.expiryMs > 0) parts.add("Expires ${Ui.date(a.expiryMs)}")
        if (a.maxConnections.isNotEmpty()) parts.add("Connections ${a.activeConnections.ifEmpty { "0" }}/${a.maxConnections}")
        findViewById<TextView>(R.id.accountInfo).text = parts.joinToString("  •  ")
        findViewById<Button>(R.id.tileLive).requestFocus()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
