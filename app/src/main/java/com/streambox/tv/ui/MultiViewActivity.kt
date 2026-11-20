package com.streambox.tv.ui

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streambox.tv.R
import com.streambox.tv.data.Category
import com.streambox.tv.data.Channel
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo

/**
 * Watch 2 or 4 live channels at once. Move between screens with the arrows; the selected screen
 * plays its sound. OK = choose a channel for that screen, long-press OK / MENU = open it full screen.
 */
@OptIn(markerClass = [UnstableApi::class])
class MultiViewActivity : AppCompatActivity() {

    private class Tile(val frame: FrameLayout, val view: PlayerView, val label: TextView) {
        var channel: Channel? = null
        var player: ExoPlayer? = null
    }

    private val tiles = ArrayList<Tile>()
    private var count = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.loaded) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        count = if (intent.getIntExtra(EXTRA_COUNT, 2) >= 4) 4 else 2
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val rowsCount = if (count == 4) 2 else 1
        for (r in 0 until rowsCount) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until 2) row.addView(makeTile(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)

        // Restore the last channels, starting with the one passed in.
        val saved = Prefs.recentList("multi_${Repo.profileId}")
        val first = intent.getStringExtra(EXTRA_CHANNEL)
        val ids = (listOfNotNull(first) + saved).distinct()
        tiles.forEachIndexed { i, t -> t.channel = ids.getOrNull(i)?.let { Repo.channel(it) } }
        tiles.first().frame.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeTile(): FrameLayout {
        val frame = FrameLayout(this).apply {
            isFocusable = true
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }
        val view = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = false
            setBackgroundColor(0xFF05070D.toInt())
        }
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val label = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setBackgroundResource(R.drawable.bg_badge)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        val tile = Tile(frame, view, label)
        tiles.add(tile)
        frame.setOnFocusChangeListener { _, _ -> updateAudio() }
        frame.setOnClickListener { pickChannel(tile) }
        frame.setOnLongClickListener { openFull(tile); true }
        return frame
    }

    override fun onStart() {
        super.onStart()
        tiles.forEach { play(it) }
        updateAudio()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { t ->
            t.view.player = null
            t.player?.release()
            t.player = null
        }
        Prefs.saveRecentList("multi_${Repo.profileId}", tiles.mapNotNull { it.channel?.id })
    }

    private fun play(t: Tile) {
        val c = t.channel
        if (c == null) {
            t.label.text = "Press OK to choose a channel"
            t.player?.release()
            t.player = null
            t.view.player = null
            return
        }
        t.label.text = "${c.num}  ${c.name}"
        var p = t.player
        if (p == null) {
            p = PlayerFactory.build(this)
            // Several decoders at once: cap each screen at HD to stay smooth on TV boxes.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(if (count == 4) 1280 else 1920, if (count == 4) 720 else 1080)
                .setForceHighestSupportedBitrate(false)
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    t.label.text = "${c.num}  ${c.name}   •   can't play (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase()})"
                    t.frame.postDelayed({ if (t.player === p) { p.prepare(); p.play() } }, 4000)
                }
            })
            t.player = p
            t.view.player = p
        }
        p.setMediaItem(PlayerFactory.mediaItem(Repo.urlFor(c)))
        p.prepare()
        p.playWhenReady = true
        updateAudio()
    }

    private fun updateAudio() {
        tiles.forEach { t -> t.player?.volume = if (t.frame.hasFocus() || t.frame.isFocused) 1f else 0f }
    }

    private fun pickChannel(t: Tile) {
        val cats = ArrayList<Category>()
        cats.add(Category(Repo.CAT_FAV, "★ Favourites"))
        cats.add(Category(Repo.CAT_RECENT, "Recent"))
        cats.add(Category(Repo.CAT_ALL, "All channels"))
        cats.addAll(Repo.liveCats.filter { !Repo.isLocked(Repo.SECTION_LIVE, it) })
        AlertDialog.Builder(this)
            .setTitle("Choose a group")
            .setItems(cats.map { it.name }.toTypedArray()) { _, w ->
                val list = Repo.channelsIn(cats[w].id)
                if (list.isEmpty()) {
                    toast("No channels in ${cats[w].name}")
                    return@setItems
                }
                AlertDialog.Builder(this)
                    .setTitle(cats[w].name)
                    .setItems(list.map { "${it.num}   ${it.name}" }.toTypedArray()) { _, i ->
                        t.channel = list[i]
                        play(t)
                    }
                    .show()
            }
            .show()
    }

    private fun openFull(t: Tile) {
        val c = t.channel ?: return
        val list = Repo.channelsIn(Repo.CAT_ALL)
        Ui.play(this, list.map { com.streambox.tv.data.PlayItem("", it.name, Repo.urlFor(it), true, it.id) },
            list.indexOfFirst { it.id == c.id }.coerceAtLeast(0))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO)
        ) {
            tiles.firstOrNull { it.frame.isFocused }?.let { openFull(it) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_COUNT = "count"
        const val EXTRA_CHANNEL = "channel"
    }
}
