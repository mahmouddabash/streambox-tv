package com.streambox.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streambox.tv.R
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Full-screen player.
 * Live: ▲▼ / CH± zap, OK shows info, ◀▶ or MENU opens audio/subtitle/aspect options.
 * VOD: standard transport controls, resume, auto-next episode; MENU opens the same options.
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var ovTitle: TextView
    private lateinit var ovNow: TextView
    private lateinit var ovNext: TextView
    private lateinit var ovHint: TextView
    private lateinit var ovProgress: ProgressBar
    private lateinit var errorView: TextView

    private var player: ExoPlayer? = null
    private var items: List<PlayItem> = emptyList()
    private var index = 0
    private var fromStart = false
    private var retries = 0
    private var resizeIdx = 0
    private var epgJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val retry = Runnable {
        player?.let {
            it.prepare()
            it.playWhenReady = true
        }
    }

    private val current: PlayItem get() = items[index]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = Repo.queue
        index = Repo.queueIndex
        if (items.isEmpty() || index !in items.indices) {
            finish()
            return
        }
        fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        ovTitle = findViewById(R.id.ovTitle)
        ovNow = findViewById(R.id.ovNow)
        ovNext = findViewById(R.id.ovNext)
        ovHint = findViewById(R.id.ovHint)
        ovProgress = findViewById(R.id.ovProgress)
        errorView = findViewById(R.id.error)

        playerView.setOnClickListener { if (current.isLive) toggleOverlay() }
        playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        val p = PlayerFactory.build(this)
        player = p
        playerView.player = p
        p.addListener(listener)
        startItem(resume = !fromStart)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        savePosition()
        // After leaving and coming back, always continue where we were.
        fromStart = false
        playerView.player = null
        player?.removeListener(listener)
        player?.release()
        player = null
    }

    // ------------------------------------------------------------------ Playback

    private fun startItem(resume: Boolean) {
        val p = player ?: return
        val item = current
        Repo.queueIndex = index
        retries = 0
        errorView.visibility = View.GONE
        handler.removeCallbacks(retry)

        playerView.useController = !item.isLive
        p.setMediaItem(PlayerFactory.mediaItem(item.url))
        val pos = if (resume && !item.isLive) Prefs.position(item.key) else 0L
        if (pos > 0) p.seekTo(pos)
        p.prepare()
        p.playWhenReady = true

        if (item.isLive && item.channelId.isNotEmpty()) Prefs.addRecentChannel(Repo.profileId, item.channelId)
        showOverlay()
    }

    private fun savePosition() {
        val p = player ?: return
        if (items.isEmpty()) return
        val item = current
        if (item.isLive || item.key.isEmpty()) return
        val pos = p.currentPosition
        val dur = p.duration
        when {
            dur != C.TIME_UNSET && dur > 0 && pos > dur * 0.95 -> Prefs.setPosition(item.key, 0L)
            pos > 10_000L -> Prefs.setPosition(item.key, pos)
        }
    }

    private fun zap(delta: Int) {
        if (items.size < 2) return
        index = (index + delta + items.size) % items.size
        startItem(resume = false)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retries = 0
                errorView.visibility = View.GONE
            } else if (state == Player.STATE_ENDED) {
                val item = current
                if (item.isLive) {
                    // A live stream that "ends" has dropped: reconnect.
                    handler.postDelayed(retry, 1500)
                } else {
                    Prefs.setPosition(item.key, 0L)
                    if (index < items.size - 1) {
                        index++
                        startItem(resume = false)
                    } else {
                        finish()
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = current
            if (item.isLive && retries < 5) {
                retries++
                errorView.text = "Connection lost. Reconnecting… ($retries/5)"
                errorView.visibility = View.VISIBLE
                handler.postDelayed(retry, 2500)
            } else {
                errorView.text = "Cannot play this stream.\n${error.errorCodeName}\n\n" +
                    if (item.isLive) "Press ▲▼ for another channel, or Back." else "Press Back to return."
                errorView.visibility = View.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------------ Overlay

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay()
    }

    private fun showOverlay() {
        val item = current
        if (!item.isLive) {
            overlay.visibility = View.GONE
            return
        }
        ovTitle.text = item.title
        ovNow.text = ""
        ovNext.text = ""
        ovProgress.visibility = View.GONE
        ovHint.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 6000)

        epgJob?.cancel()
        val channel = Repo.channel(item.channelId) ?: return
        ovTitle.text = "${channel.num}   ${channel.name}"
        if (!Repo.hasEpgSource) return
        epgJob = lifecycleScope.launch {
            val list = Repo.upcoming(this@PlayerActivity, channel)
            if (current.channelId != channel.id) return@launch
            val now = System.currentTimeMillis()
            list.getOrNull(0)?.let { pr ->
                ovNow.text = "${Ui.time(pr.startMs)} – ${Ui.time(pr.endMs)}   ${pr.title}"
                if (pr.endMs > pr.startMs && pr.startMs <= now) {
                    ovProgress.progress = ((now - pr.startMs) * 1000 / (pr.endMs - pr.startMs)).toInt()
                    ovProgress.visibility = View.VISIBLE
                }
            }
            list.getOrNull(1)?.let { pr -> ovNext.text = "Next  ${Ui.time(pr.startMs)}   ${pr.title}" }
        }
    }

    // ------------------------------------------------------------------ Keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (items.isEmpty() || player == null) return super.dispatchKeyEvent(event)
        val live = current.isLive
        val down = event.action == KeyEvent.ACTION_DOWN

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (down) optionsMenu()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> if (live || items.size > 1) {
                if (down) {
                    savePosition()
                    zap(1)
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (live || items.size > 1) {
                if (down) {
                    savePosition()
                    zap(-1)
                }
                return true
            }
        }

        if (live) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (down) zap(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_INFO -> {
                    if (down && event.repeatCount == 0) toggleOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && event.repeatCount == 0) optionsMenu()
                    return true
                }
            }
        } else if (event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ Options

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Fit", "Stretch", "Zoom")

    private fun optionsMenu() {
        val p = player ?: return
        val options = ArrayList<String>()
        options.add("Audio track")
        options.add("Subtitles")
        options.add("Aspect ratio: ${resizeNames[resizeIdx]}")
        if (!current.isLive) options.add("Playback speed: ${p.playbackParameters.speed}x")
        AlertDialog.Builder(this)
            .setTitle(current.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> trackMenu(C.TRACK_TYPE_AUDIO, "Audio track")
                    1 -> trackMenu(C.TRACK_TYPE_TEXT, "Subtitles")
                    2 -> {
                        resizeIdx = (resizeIdx + 1) % resizeModes.size
                        playerView.resizeMode = resizeModes[resizeIdx]
                        toast("Aspect ratio: ${resizeNames[resizeIdx]}")
                    }
                    3 -> speedMenu()
                }
            }
            .show()
    }

    private fun speedMenu() {
        val p = player ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == p.playbackParameters.speed }) { d, which ->
                p.playbackParameters = PlaybackParameters(speeds[which])
                d.dismiss()
            }
            .show()
    }

    private fun trackMenu(type: Int, title: String) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == type }
        val labels = ArrayList<String>()
        val choices = ArrayList<Pair<Int, Int>>()
        var checked = -1

        if (type == C.TRACK_TYPE_TEXT) {
            labels.add("Off")
            choices.add(Pair(-1, -1))
            checked = 0
        }
        for ((gi, g) in groups.withIndex()) {
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                if (g.isTrackSelected(ti)) checked = labels.size
                labels.add(describe(g.getTrackFormat(ti), labels.size, type))
                choices.add(Pair(gi, ti))
            }
        }
        if (choices.isEmpty() || (type == C.TRACK_TYPE_TEXT && choices.size == 1)) {
            toast(if (type == C.TRACK_TYPE_AUDIO) "No alternative audio tracks" else "This stream has no subtitles")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                val (gi, ti) = choices[which]
                val b = p.trackSelectionParameters.buildUpon()
                if (gi < 0) {
                    b.setTrackTypeDisabled(type, true)
                } else {
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(groups[gi].mediaTrackGroup, ti))
                }
                p.trackSelectionParameters = b.build()
                d.dismiss()
            }
            .show()
    }

    private fun describe(f: Format, n: Int, type: Int): String {
        val parts = ArrayList<String>()
        f.label?.let { if (it.isNotBlank()) parts.add(it) }
        f.language?.let { if (it.isNotBlank() && it != "und") parts.add(java.util.Locale(it).displayLanguage.ifBlank { it }) }
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> parts.add("Mono")
                2 -> parts.add("Stereo")
                6 -> parts.add("5.1")
                8 -> parts.add("7.1")
            }
        }
        return if (parts.isEmpty()) "Track ${n + 1}" else parts.joinToString("  •  ")
    }

    companion object {
        const val EXTRA_FROM_START = "fromStart"
    }
}
