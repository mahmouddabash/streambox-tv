package com.streambox.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Category
import com.streambox.tv.data.Channel
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Programme
import com.streambox.tv.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Live TV: categories | channels | preview + guide. */
class LiveActivity : AppCompatActivity() {

    private lateinit var catAdapter: SimpleAdapter<Category>
    private lateinit var chAdapter: SimpleAdapter<Channel>
    private lateinit var epgAdapter: SimpleAdapter<Programme>
    private lateinit var channelsView: RecyclerView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private lateinit var previewView: PlayerView
    private lateinit var previewHint: TextView
    private lateinit var channelName: TextView
    private lateinit var epgStatus: TextView

    private var currentCat = Repo.CAT_ALL
    private var catChannels: List<Channel> = emptyList()
    private var favs: Set<String> = emptySet()
    private var previewPlayer: ExoPlayer? = null
    private var previewId: String? = null
    private var epgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.loaded) {
            finish()
            return
        }
        setContentView(R.layout.activity_live)

        channelsView = findViewById(R.id.channels)
        empty = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        previewView = findViewById(R.id.preview)
        previewHint = findViewById(R.id.previewHint)
        channelName = findViewById(R.id.channelName)
        epgStatus = findViewById(R.id.epgStatus)
        findViewById<TextView>(R.id.title).text = "Live TV"

        catAdapter = SimpleAdapter(
            R.layout.item_text,
            bind = { v, c, _ ->
                (v as TextView).text = if (Repo.isLocked(Repo.SECTION_LIVE, c)) "🔒  ${c.name}" else c.name
            },
            onClick = { c, pos ->
                Ui.unlock(this, Repo.SECTION_LIVE, c) {
                    catAdapter.select(pos)
                    showCategory(c.id)
                }
            },
            onLongClick = { c, _ ->
                Ui.categoryMenu(this, Repo.SECTION_LIVE, c) { catAdapter.submit(categories(), catAdapter.selected) }
                true
            }
        )
        val catView = findViewById<RecyclerView>(R.id.categories)
        catView.layoutManager = LinearLayoutManager(this)
        catView.adapter = catAdapter

        chAdapter = SimpleAdapter(
            R.layout.item_channel,
            bind = { v, c, _ ->
                v.findViewById<TextView>(R.id.num).text = c.num.toString()
                v.findViewById<TextView>(R.id.name).text = c.name
                v.findViewById<ImageView>(R.id.logo).loadUrl(c.logo)
                val badge = if (c.id in favs) "★" else ""
                v.findViewById<TextView>(R.id.badge).text = badge
            },
            onClick = { c, pos -> onChannelClick(c, pos) },
            onLongClick = { c, pos -> channelMenu(c, pos); true }
        )
        channelsView.layoutManager = LinearLayoutManager(this)
        channelsView.itemAnimator = null
        channelsView.adapter = chAdapter

        epgAdapter = SimpleAdapter(
            R.layout.item_epg_small,
            bind = { v, p, _ ->
                v.findViewById<TextView>(R.id.line1).text = "${Ui.time(p.startMs)} – ${Ui.time(p.endMs)}"
                v.findViewById<TextView>(R.id.line2).text = p.title
            }
        )
        val epgView = findViewById<RecyclerView>(R.id.epg)
        epgView.layoutManager = LinearLayoutManager(this)
        epgView.adapter = epgAdapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        // Reopen the category and channel the user was on last time.
        val cats = categories()
        val savedCat = Prefs.lastCategory(Repo.profileId, screenKey)
        var catIdx = cats.indexOfFirst { it.id == savedCat && !Repo.isLocked(Repo.SECTION_LIVE, it) }
        if (catIdx < 0) catIdx = 0
        catAdapter.submit(cats, catIdx)
        showCategory(cats[catIdx].id)
        val focused = preselectLast()
        catView.post {
            if (catIdx > 0) (catView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(catIdx, 120)
            if (!focused) catView.requestFocus()
        }
    }

    private val screenKey: String get() = Prefs.SCREEN_LIVE

    override fun onResume() {
        super.onResume()
        if (Repo.loaded) Prefs.lastScreen = screenKey
    }

    private fun categories(): List<Category> {
        val list = ArrayList<Category>()
        list.add(Category(Repo.CAT_ALL, "All channels"))
        list.add(Category(Repo.CAT_FAV, "★ Favourites"))
        list.add(Category(Repo.CAT_RECENT, "Recently watched"))
        list.addAll(Repo.liveCats)
        return list
    }

    private fun showCategory(id: String) {
        currentCat = id
        Prefs.setLastCategory(Repo.profileId, screenKey, id)
        favs = Prefs.favourites(Repo.profileId, Prefs.FAV_LIVE)
        catChannels = Repo.channelsIn(id)
        applyFilter()
    }

    /**
     * Highlights, scrolls to and focuses the channel watched most recently, without starting a stream.
     * Returns false when that channel is not in the current list.
     */
    private fun preselectLast(): Boolean {
        val lastId = Prefs.recentChannels(Repo.profileId).firstOrNull() ?: return false
        val idx = chAdapter.items.indexOfFirst { it.id == lastId }
        if (idx < 0) return false
        chAdapter.select(idx)
        val lm = channelsView.layoutManager as LinearLayoutManager
        channelsView.post {
            lm.scrollToPositionWithOffset(idx, 120)
            channelsView.post { lm.findViewByPosition(idx)?.requestFocus() }
        }
        return true
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        val list = if (q.isEmpty()) catChannels else catChannels.filter { it.name.contains(q, ignoreCase = true) }
        chAdapter.submit(list, list.indexOfFirst { it.id == previewId })
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onChannelClick(c: Channel, pos: Int) {
        if (!Prefs.previewEnabled || previewId == c.id) {
            playFullscreen(pos)
        } else {
            chAdapter.select(pos)
            startPreview(c)
        }
    }

    private fun startPreview(c: Channel) {
        previewId = c.id
        channelName.text = c.name
        previewHint.visibility = View.GONE
        val player = previewPlayer ?: PlayerFactory.build(this).also {
            previewPlayer = it
            previewView.player = it
        }
        player.setMediaItem(PlayerFactory.mediaItem(Repo.urlFor(c)))
        player.prepare()
        player.playWhenReady = true
        loadEpg(c)
    }

    private fun loadEpg(c: Channel) {
        epgJob?.cancel()
        epgAdapter.submit(emptyList())
        epgStatus.text = if (Repo.hasEpgSource) "Loading guide…" else "No TV guide for this playlist"
        if (!Repo.hasEpgSource) return
        epgJob = lifecycleScope.launch {
            val list = Repo.upcoming(this@LiveActivity, c)
            if (previewId != c.id) return@launch
            epgAdapter.submit(list)
            epgStatus.text = if (list.isEmpty()) "No programme information" else "Now: ${list[0].title}"
        }
    }

    private fun releasePreview() {
        previewView.player = null
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun playFullscreen(pos: Int) {
        // Free the connection first: many providers allow a single stream at a time.
        releasePreview()
        val list = chAdapter.items
        val items = list.map { PlayItem("", it.name, Repo.urlFor(it), true, it.id) }
        Ui.play(this, items, pos)
    }

    private fun openGuide(c: Channel) {
        releasePreview()
        startActivity(Intent(this, EpgActivity::class.java).putExtra(EpgActivity.EXTRA_CHANNEL, c.id))
    }

    private fun channelMenu(c: Channel, pos: Int) {
        val isFav = Prefs.isFavourite(Repo.profileId, Prefs.FAV_LIVE, c.id)
        val options = arrayOf(
            "Play full screen",
            if (isFav) "Remove from favourites" else "Add to favourites",
            "TV guide",
            "Rename channel",
            "Hide channel"
        )
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playFullscreen(pos)
                    1 -> {
                        Prefs.toggleFavourite(Repo.profileId, Prefs.FAV_LIVE, c.id)
                        favs = Prefs.favourites(Repo.profileId, Prefs.FAV_LIVE)
                        if (currentCat == Repo.CAT_FAV) showCategory(currentCat) else chAdapter.refresh(pos)
                    }
                    2 -> openGuide(c)
                    3 -> Ui.input(this, "Rename channel (empty = original name)", c.name) { name ->
                        Prefs.setChannelName(Repo.profileId, c.id, name)
                        Repo.applyCustomisations()
                        showCategory(currentCat)
                    }
                    4 -> {
                        Prefs.setChannelHidden(Repo.profileId, c.id, true)
                        toast("Hidden. Show it again in Settings → Channel manager")
                        showCategory(currentCat)
                    }
                }
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        if (::previewView.isInitialized) {
            releasePreview()
            previewId = null
            previewHint.visibility = View.VISIBLE
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Recently watched / favourites may have changed while the player was open.
        if (::chAdapter.isInitialized && (currentCat == Repo.CAT_RECENT || currentCat == Repo.CAT_FAV)) showCategory(currentCat)
        else if (::chAdapter.isInitialized) chAdapter.select(-1)
    }

    companion object {
    }
}
