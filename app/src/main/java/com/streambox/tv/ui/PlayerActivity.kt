package com.streambox.tv.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streambox.tv.R
import com.streambox.tv.data.Channel
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Recorder
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import com.streambox.tv.data.StreamProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Full-screen player.
 * Live: ▲▼ / CH± / digits zap, OK shows info, ⏯ pauses (and rewinds through the archive),
 * ◀▶ or MENU opens the options (audio, subtitles, record, multi-screen, sleep timer…).
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
    private lateinit var numberView: TextView
    private lateinit var shiftView: TextView

    // Typing a channel number on the remote
    private val digits = StringBuilder()
    private val commitNumber = Runnable { commitDigits() }

    // Previous channel (LAST / "back to previous")
    private var prevItems: List<PlayItem>? = null
    private var prevIndex = -1

    // Live pause / rewind: 0 = watching live; otherwise the wall-clock time at position 0 of the timeshift stream.
    private var shiftStartMs = 0L
    private var pausedAtMs = 0L
    private val shiftTicker = object : Runnable {
        override fun run() {
            updateShiftBadge()
            handler.postDelayed(this, 15_000)
        }
    }
    private val sleepRunnable = Runnable { sleepNow() }

    // Movies/episodes: ◀ ▶ add up whole minutes, then one seek is made (no re-buffer per key press).
    private var pendingSeekMs = 0L
    private val applySeek = Runnable { commitSeek() }
    private val hideSeek = Runnable { shiftView.visibility = View.GONE }
    /** Set when we open another screen ourselves, so that doesn't trigger picture-in-picture. */
    private var leavingInternally = false
    private var wasInPip = false

    private var player: ExoPlayer? = null
    private var items: List<PlayItem> = emptyList()
    private var index = 0
    private var fromStart = false
    private var retries = 0
    private var resizeIdx = 0
    private var epgJob: Job? = null

    // Format recovery: when the player cannot parse what the server sends, probe the URL and retry.
    private var token = 0
    private var attemptUrl = ""
    private var startPos = 0L
    private var recoverySteps = 0
    private var triedAltExt = false
    private var firstProbe: StreamProbe.Result? = null
    private var probeJob: Job? = null
    private var failed = false
    private var attemptMime: String? = null
    /** Set when the GPU could not run the picture filter; playback continues without it. */
    private var effectsDisabled = false

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
        numberView = findViewById(R.id.number)
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            ?.setKeyTimeIncrement(PlayerFactory.SEEK_STEP_MS)
        shiftView = findViewById(R.id.shift)

        // Only one player at a time (a picture-in-picture window is replaced by the new one).
        active?.get()?.takeIf { it !== this }?.finish()
        active = java.lang.ref.WeakReference(this)

        playerView.setOnClickListener { if (current.isLive) toggleOverlay() }
        playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        createPlayer()
        startItem(resume = !fromStart)
        scheduleSleep()
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    private fun createPlayer() {
        val p = PlayerFactory.build(this)
        if (!effectsDisabled) {
            val fx = PlayerFactory.effects()
            if (fx.isNotEmpty()) {
                try {
                    p.setVideoEffects(fx)
                } catch (_: Exception) {
                    effectsDisabled = true
                }
            }
        }
        player = p
        playerView.player = p
        PlayerFactory.styleSubtitles(playerView)
        p.addListener(listener)
    }

    /** Rebuilds the player (new picture mode / quality setting) and continues at the same spot. */
    private fun rebuildPlayer() {
        val old = player ?: return
        if (!current.isLive) startPos = old.currentPosition
        playerView.player = null
        old.removeListener(listener)
        old.release()
        createPlayer()
        load(attemptUrl, attemptMime)
    }

    override fun onResume() {
        super.onResume()
        leavingInternally = false
    }

    override fun onStop() {
        super.onStop()
        // The picture-in-picture window was closed: end playback instead of lingering in the background.
        if (wasInPip && Build.VERSION.SDK_INT >= 24 && !isInPictureInPictureMode) {
            wasInPip = false
            finish()
        }
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
        shiftStartMs = 0L
        pausedAtMs = 0L
        extraSubs = emptyList()
        handler.removeCallbacks(shiftTicker)
        shiftView.visibility = View.GONE
        Repo.queueIndex = index
        retries = 0
        token++
        recoverySteps = 0
        triedAltExt = false
        firstProbe = null
        failed = false
        probeJob?.cancel()
        errorView.visibility = View.GONE
        handler.removeCallbacks(retry)

        playerView.useController = !item.isLive
        startPos = if (resume && !item.isLive) Prefs.position(item.key) else 0L
        load(item.url, null)

        if (item.isLive && item.channelId.isNotEmpty()) Prefs.addRecentChannel(Repo.profileId, item.channelId)
        showOverlay()
    }

    /** Subtitles downloaded for the current item (kept when the player is rebuilt). */
    private var extraSubs: List<androidx.media3.common.MediaItem.SubtitleConfiguration> = emptyList()

    private fun load(url: String, mimeType: String?) {
        val p = player ?: return
        attemptUrl = url
        attemptMime = mimeType
        val item = PlayerFactory.mediaItem(url, mimeType)
        p.setMediaItem(if (extraSubs.isEmpty()) item else item.buildUpon().setSubtitleConfigurations(extraSubs).build())
        if (startPos > 0) p.seekTo(startPos)
        p.prepare()
        p.playWhenReady = true
    }

    private fun isParsingError(e: PlaybackException) = e.errorCode in
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

    /** Xtream links can usually be requested as .ts or .m3u8; gives the other one, or null. */
    private fun altExtensionUrl(url: String): String? {
        if (!Regex("/(live|movie|series|timeshift)/").containsMatchIn(url)) return null
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val path = url.substring(0, q)
        val dot = path.lastIndexOf('.')
        if (dot <= path.lastIndexOf('/')) return null
        val ext = path.substring(dot + 1).lowercase()
        val alt = if (ext == "m3u8") "ts" else "m3u8"
        return path.substring(0, dot + 1) + alt + url.substring(q)
    }

    /** Finds out what the server really sends and retries in that format, or explains why it can't play. */
    private fun recover(error: PlaybackException) {
        recoverySteps++
        val myToken = token
        val url = attemptUrl
        errorView.text = "Checking the stream format…"
        errorView.visibility = View.VISIBLE
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            val r = try { StreamProbe.probe(url) } catch (_: Exception) { null }
            if (myToken != token || player == null) return@launch
            if (firstProbe == null) firstProbe = r
            val mime = when (r?.kind) {
                StreamProbe.Kind.HLS -> MimeTypes.APPLICATION_M3U8
                StreamProbe.Kind.DASH -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            val alt = if (!triedAltExt) altExtensionUrl(url) else null
            when {
                r != null && mime != null -> {
                    errorView.visibility = View.GONE
                    load(r.finalUrl, mime)
                }
                r != null && (r.httpCode >= 400 || r.kind == StreamProbe.Kind.TEXT || r.kind == StreamProbe.Kind.EMPTY) ->
                    fail(error)
                alt != null -> {
                    triedAltExt = true
                    errorView.visibility = View.GONE
                    load(alt, null)
                }
                else -> fail(error)
            }
        }
    }

    private fun fail(error: PlaybackException) {
        failed = true
        val item = current
        val r = firstProbe
        val reason = when {
            r == null -> error.errorCodeName
            r.httpCode >= 400 -> "The server answered HTTP ${r.httpCode}" +
                if (r.message.isNotEmpty()) ":\n\"${r.message}\"" else "."
            r.kind == StreamProbe.Kind.TEXT -> "The server sent a message instead of video" +
                if (r.message.isNotEmpty()) ":\n\"${r.message}\"" else "."
            r.kind == StreamProbe.Kind.EMPTY -> "The server sent an empty response (stream offline or connection limit reached)."
            r.kind == StreamProbe.Kind.ASF || r.kind == StreamProbe.Kind.RM ->
                "This video is ${r.kind.label}, which the built-in player does not support."
            else -> "Format: ${r.kind.label}. The built-in player could not read it (${error.errorCodeName})."
        }
        errorView.text = "Cannot play this stream.\n$reason\n\nOK = open in VLC / MX Player     " +
            if (item.isLive) "▲▼ = other channel     Back = return" else "Back = return"
        errorView.visibility = View.VISIBLE
        playerView.hideController()
    }

    private fun openExternal() {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(Uri.parse(current.url), "video/*")
            i.putExtra("title", current.title)
            leavingInternally = true
            startActivity(i)
        } catch (_: Exception) {
            toast("No external player installed. Install VLC or MX Player from the Play Store.")
        }
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
            pos > 10_000L -> Prefs.setPosition(item.key, pos, if (dur != C.TIME_UNSET) dur else 0L)
        }
    }

    private fun zap(delta: Int) {
        if (items.size < 2) return
        rememberPrevious()
        index = (index + delta + items.size) % items.size
        startItem(resume = false)
    }

    private fun rememberPrevious() {
        if (current.isLive) {
            prevItems = items
            prevIndex = index
        }
    }

    // ------------------------------------------------------------------ Channel numbers & previous channel

    private fun typeDigit(d: Int) {
        if (digits.length >= 5) digits.setLength(0)
        digits.append(d)
        numberView.text = digits.toString()
        numberView.visibility = View.VISIBLE
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 1600)
    }

    private fun commitDigits() {
        val n = digits.toString().toIntOrNull()
        digits.setLength(0)
        numberView.visibility = View.GONE
        if (n == null) return
        // Prefer the current list, then all channels.
        val inList = items.indexOfFirst { Repo.channel(it.channelId)?.num == n }
        if (inList >= 0) {
            if (inList != index) {
                rememberPrevious()
                index = inList
                startItem(resume = false)
            }
            return
        }
        val all = Repo.channelsIn(Repo.CAT_ALL)
        val i = all.indexOfFirst { it.num == n }
        if (i < 0) {
            toast("No channel $n")
            return
        }
        rememberPrevious()
        items = all.map { PlayItem("", it.name, Repo.urlFor(it), true, it.id) }
        Repo.queue = items
        index = i
        startItem(resume = false)
    }

    private fun previousChannel() {
        val pi = prevItems
        if (pi == null || prevIndex !in pi.indices) {
            toast("No previous channel yet")
            return
        }
        val curItems = items
        val curIndex = index
        items = pi
        Repo.queue = pi
        index = prevIndex
        prevItems = curItems
        prevIndex = curIndex
        startItem(resume = false)
    }

    // ------------------------------------------------------------------ Live pause / rewind

    private val liveChannel: Channel? get() = Repo.channel(current.channelId)

    private fun livePosition(): Long {
        val p = player ?: return System.currentTimeMillis()
        return if (shiftStartMs > 0) shiftStartMs + p.currentPosition else System.currentTimeMillis()
    }

    private fun togglePause() {
        val p = player ?: return
        if (p.isPlaying || p.playWhenReady) {
            pausedAtMs = livePosition()
            p.pause()
            updateShiftBadge()
        } else {
            resumeLive()
        }
    }

    private fun resumeLive() {
        val p = player ?: return
        val paused = pausedAtMs
        pausedAtMs = 0L
        val behind = System.currentTimeMillis() - paused
        when {
            shiftStartMs > 0 -> p.play()
            paused > 0 && behind > 20_000 && Repo.canTimeshift(liveChannel) -> startShift(paused)
            paused > 0 && behind > 60_000 -> {
                // No archive: the buffered stream is too old, reconnect to live.
                toast("Back to live")
                goLive()
            }
            else -> p.play()
        }
        updateShiftBadge()
    }

    /** Plays the provider's archive from [fromMs] (wall-clock time). */
    private fun startShift(fromMs: Long) {
        val c = liveChannel ?: return
        if (!Repo.canTimeshift(c)) {
            toast("Rewind isn't available on this channel")
            return
        }
        val now = System.currentTimeMillis()
        val days = if (c.archiveDays > 0) c.archiveDays else 1
        val earliest = now - days * 86_400_000L
        val target = fromMs.coerceIn(earliest, now - 30_000L)
        val start = target - target % 60_000L
        val minutes = ((now - start) / 60_000L).toInt() + 120
        shiftStartMs = start
        startPos = target - start
        load(Repo.timeshiftUrl(c, start, minutes), null)
        handler.removeCallbacks(shiftTicker)
        handler.post(shiftTicker)
    }

    private fun rewind(minutes: Int) {
        if (!Repo.canTimeshift(liveChannel)) {
            toast("Rewind isn't available on this channel")
            return
        }
        val target = livePosition() - minutes * 60_000L
        val p = player
        if (shiftStartMs > 0 && target >= shiftStartMs && p != null) p.seekTo(target - shiftStartMs) else startShift(target)
        pausedAtMs = 0L
        updateShiftBadge()
    }

    private fun forward(minutes: Int) {
        if (shiftStartMs == 0L) return
        val target = livePosition() + minutes * 60_000L
        if (target >= System.currentTimeMillis() - 45_000L) goLive() else player?.seekTo(target - shiftStartMs)
        updateShiftBadge()
    }

    /** Watch the current programme from its beginning (needs EPG + archive). */
    private fun startOver() {
        val c = liveChannel ?: return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val pr = Repo.upcoming(this@PlayerActivity, c).firstOrNull { it.startMs <= now && it.endMs > now }
            if (pr == null) toast("No programme information for this channel") else startShift(pr.startMs)
        }
    }

    private fun goLive() {
        shiftStartMs = 0L
        pausedAtMs = 0L
        startPos = 0L
        handler.removeCallbacks(shiftTicker)
        load(current.url, null)
        updateShiftBadge()
    }

    private fun updateShiftBadge() {
        if (!current.isLive) return
        val behindMin = ((System.currentTimeMillis() - livePosition()) / 60_000L).toInt()
        val text = when {
            pausedAtMs > 0 -> "⏸  Paused" + if (Repo.canTimeshift(liveChannel)) "   •   ⏯ continue from here" else "   •   ⏯ resume"
            shiftStartMs > 0 -> "⏪  ${if (behindMin < 1) "<1" else behindMin.toString()} min behind live   •   ⏩ catch up   •   menu: Back to live"
            else -> ""
        }
        shiftView.text = text
        shiftView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ Sleep timer & picture-in-picture

    private fun scheduleSleep() {
        handler.removeCallbacks(sleepRunnable)
        val left = Prefs.sleepAtMs - System.currentTimeMillis()
        if (Prefs.sleepAtMs > 0 && left > 0) handler.postDelayed(sleepRunnable, left)
        else if (Prefs.sleepAtMs > 0) Prefs.sleepAtMs = 0L
    }

    private fun sleepNow() {
        Prefs.sleepAtMs = 0L
        player?.pause()
        toast("Sleep timer: playback stopped")
        finishAffinity()
    }

    private fun sleepMenu() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val labels = mins.map { if (it == 0) "Off" else "$it minutes" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, w ->
                Prefs.sleepAtMs = if (mins[w] == 0) 0L else System.currentTimeMillis() + mins[w] * 60_000L
                scheduleSleep()
                toast(if (mins[w] == 0) "Sleep timer off" else "Playback stops in ${mins[w]} minutes")
            }
            .show()
    }

    private fun sleepLabel(): String {
        val left = Prefs.sleepAtMs - System.currentTimeMillis()
        return if (Prefs.sleepAtMs > 0 && left > 0) "${(left / 60_000L) + 1} min left" else "Off"
    }

    private val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip(): Boolean {
        if (!pipSupported || Build.VERSION.SDK_INT < 26) return false
        return try {
            val f = player?.videoFormat
            val ratio = if (f != null && f.width > 0 && f.height > 0)
                Rational(f.width, f.height).let { r -> if (r.toFloat() in 0.42f..2.38f) r else Rational(16, 9) }
            else Rational(16, 9)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        } catch (_: Exception) {
            false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home button while playing: keep watching in a small window.
        if (Prefs.autoPip && !leavingInternally && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (!isInPip && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            // Closed from the small window (not expanded): stop playback.
            finish()
            return
        }
        wasInPip = isInPip
        if (isInPip) {
            overlay.visibility = View.GONE
            numberView.visibility = View.GONE
            shiftView.visibility = View.GONE
            playerView.useController = false
            playerView.hideController()
        } else {
            playerView.useController = !current.isLive
            updateShiftBadge()
        }
    }

    // ------------------------------------------------------------------ Record & multi-screen

    private fun recordMenu() {
        val c = liveChannel ?: return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val pr = if (Repo.hasEpgSource) Repo.upcoming(this@PlayerActivity, c).firstOrNull { it.startMs <= now && it.endMs > now } else null
            val options = ArrayList<Pair<String, Long>>()
            if (pr != null) options.add("Record \"${pr.title}\" until ${Ui.time(pr.endMs)}" to pr.endMs + 2 * 60_000L)
            for (m in intArrayOf(30, 60, 120)) options.add("Record the next $m minutes" to now + m * 60_000L)
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("⏺  Record ${c.name}")
                .setItems(options.map { it.first }.toTypedArray()) { _, w ->
                    Recorder.schedule(this@PlayerActivity, c, pr?.title ?: c.name, System.currentTimeMillis(), options[w].second)
                    toast("Recording to ${Recorder.storageDir(this@PlayerActivity).absolutePath}")
                }
                .show()
        }
    }

    private fun multiScreen() {
        AlertDialog.Builder(this)
            .setTitle("Multi-screen")
            .setItems(arrayOf("2 screens", "4 screens")) { _, w ->
                leavingInternally = true
                startActivity(Intent(this, MultiViewActivity::class.java)
                    .putExtra(MultiViewActivity.EXTRA_COUNT, if (w == 0) 2 else 4)
                    .putExtra(MultiViewActivity.EXTRA_CHANNEL, current.channelId))
                finish()
            }
            .show()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retries = 0
                failed = false
                errorView.visibility = View.GONE
            } else if (state == Player.STATE_ENDED) {
                val item = current
                if (item.isLive && shiftStartMs > 0) {
                    goLive() // caught up with the end of the archive window
                } else if (item.isLive) {
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
            if (error.errorCode in 7000..7999 && !effectsDisabled) {
                // The TV's graphics chip could not run the picture filter: play without it.
                effectsDisabled = true
                toast("Picture enhancement isn't supported on this device, playing the original picture")
                handler.post { rebuildPlayer() }
            } else if (isParsingError(error) && recoverySteps < 3) {
                startPos = if (item.isLive) 0L else maxOf(startPos, player?.currentPosition ?: 0L)
                recover(error)
            } else if (isParsingError(error)) {
                fail(error)
            } else if (item.isLive && retries < 5) {
                retries++
                errorView.text = "Connection lost. Reconnecting… ($retries/5)"
                errorView.visibility = View.VISIBLE
                handler.postDelayed(retry, 2500)
            } else {
                fail(error)
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

        if (failed) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) openExternal()
                return true
            }
        }

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
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0) {
                if (down) typeDigit(digit)
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_LAST_CHANNEL -> {
                    if (down) previousChannel()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (down && event.repeatCount == 0) {
                        val playing = player?.playWhenReady == true
                        when {
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE && playing -> togglePause()
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && !playing -> togglePause()
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePause()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (down) rewind(1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (down) forward(1)
                    return true
                }
            }
            if (digits.isNotEmpty() && down && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                handler.removeCallbacks(commitNumber)
                commitDigits()
                return true
            }
            if (pausedAtMs > 0 && down && event.repeatCount == 0 &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                resumeLive()
                return true
            }
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
        } else if (!playerView.isControllerFullyVisible && (
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        ) {
            // ◀ / ⏪ = back one minute, ▶ / ⏩ = forward one minute (holding the key keeps stepping).
            if (down) {
                val back = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                if (event.repeatCount == 0 || event.repeatCount % 3 == 0) stepSeek(if (back) -1 else 1)
            }
            return true
        } else if (event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ Minute seeking (movies & episodes)

    private fun stepSeek(minutes: Int) {
        val p = player ?: return
        val dur = p.duration
        val base = p.currentPosition
        var target = base + pendingSeekMs + minutes * PlayerFactory.SEEK_STEP_MS
        if (target < 0) target = 0
        if (dur != C.TIME_UNSET && dur > 0 && target > dur - 5_000) target = (dur - 5_000).coerceAtLeast(0)
        pendingSeekMs = target - base
        val stepMin = Math.round(pendingSeekMs / 60_000.0).toInt()
        val sign = if (stepMin > 0) "+" else if (stepMin < 0) "−" else "±"
        val arrow = if (pendingSeekMs < 0) "⏪" else "⏩"
        shiftView.text = "$arrow  $sign${kotlin.math.abs(stepMin)} min   →   ${clock(target)}" +
            if (dur != C.TIME_UNSET && dur > 0) "  /  ${clock(dur)}" else ""
        shiftView.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.removeCallbacks(applySeek)
        handler.postDelayed(applySeek, 650)
    }

    private fun commitSeek() {
        val p = player ?: return
        if (pendingSeekMs != 0L) p.seekTo((p.currentPosition + pendingSeekMs).coerceAtLeast(0))
        pendingSeekMs = 0L
        handler.postDelayed(hideSeek, 1500)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
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
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (current.isLive) {
            val canShift = Repo.canTimeshift(liveChannel)
            if (shiftStartMs > 0) options.add("⏩  Back to live" to { goLive() })
            options.add((if (pausedAtMs > 0) "▶  Resume" else "⏸  Pause live TV") to { togglePause() })
            if (canShift) {
                options.add("⏪  Rewind 5 minutes" to { rewind(5) })
                options.add("⏮  Watch this programme from the start" to { startOver() })
            }
            options.add("⏺  Record" to { recordMenu() })
            prevItems?.getOrNull(prevIndex)?.let { prev -> options.add("↩  Previous channel: ${prev.title}" to { previousChannel() }) }
            options.add("▦  Multi-screen" to { multiScreen() })
        }
        if (pipSupported) options.add("⧉  Picture-in-picture" to { enterPip() })
        options.add("☾  Sleep timer: ${sleepLabel()}" to { sleepMenu() })
        val mode = PictureMode.of(Prefs.pictureMode)
        val pictureLabel = if (effectsDisabled && mode != PictureMode.OFF) "Not supported on this TV" else mode.label.substringBefore(" (")
        options.add("Picture mode: $pictureLabel" to { pictureMenu() })
        options.add("Quality: ${qualityLabel(p)}" to { qualityMenu() })
        options.add("Audio track" to { trackMenu(C.TRACK_TYPE_AUDIO, "Audio track") })
        options.add("Subtitles" to { trackMenu(C.TRACK_TYPE_TEXT, "Subtitles") })
        if (!current.isLive) options.add("Find subtitles online (OpenSubtitles)" to { findSubtitles() })
        options.add("Subtitle style" to { subtitleStyleMenu() })
        options.add("Aspect ratio: ${resizeNames[resizeIdx]}" to {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeIdx]
            toast("Aspect ratio: ${resizeNames[resizeIdx]}")
        })
        if (!current.isLive) options.add("Playback speed: ${p.playbackParameters.speed}x" to { speedMenu() })
        AlertDialog.Builder(this)
            .setTitle(current.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    /** "Auto • 1920×1080" style label with the resolution actually playing. */
    private fun qualityLabel(p: ExoPlayer): String {
        val f = p.videoFormat
        val res = if (f != null && f.height > 0) "${f.width}×${f.height}" else ""
        val mode = if (Prefs.maxQuality) "Highest" else "Auto"
        return if (res.isEmpty()) mode else "$mode  •  $res"
    }

    private fun pictureMenu() {
        val modes = PictureMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        val cur = modes.indexOf(PictureMode.of(Prefs.pictureMode))
        AlertDialog.Builder(this)
            .setTitle("Picture mode")
            .setSingleChoiceItems(labels, cur) { d, which ->
                d.dismiss()
                if (which == cur && !effectsDisabled) return@setSingleChoiceItems
                Prefs.pictureMode = modes[which].id
                effectsDisabled = false
                rebuildPlayer()
                toast("Picture mode: ${modes[which].label.substringBefore(" (")}")
            }
            .show()
    }

    private fun qualityMenu() {
        val labels = arrayOf("Auto (adapts to your connection)", "Always highest quality")
        AlertDialog.Builder(this)
            .setTitle("Video quality")
            .setSingleChoiceItems(labels, if (Prefs.maxQuality) 1 else 0) { d, which ->
                d.dismiss()
                val want = which == 1
                if (want == Prefs.maxQuality) return@setSingleChoiceItems
                Prefs.maxQuality = want
                rebuildPlayer()
            }
            .show()
    }

    private fun subtitleStyleMenu() {
        val sizes = arrayOf("Small", "Normal", "Large", "Extra large")
        val colors = arrayOf("White", "Yellow", "Cyan")
        val bgs = arrayOf("Outline", "Dark box", "Shadow")
        val rows = arrayOf(
            "Size: ${sizes[Prefs.subtitleSize.coerceIn(0, 3)]}",
            "Colour: ${colors[Prefs.subtitleColor.coerceIn(0, 2)]}",
            "Background: ${bgs[Prefs.subtitleBackground.coerceIn(0, 2)]}"
        )
        AlertDialog.Builder(this)
            .setTitle("Subtitle style")
            .setItems(rows) { _, w ->
                when (w) {
                    0 -> Prefs.subtitleSize = (Prefs.subtitleSize + 1) % 4
                    1 -> Prefs.subtitleColor = (Prefs.subtitleColor + 1) % 3
                    else -> Prefs.subtitleBackground = (Prefs.subtitleBackground + 1) % 3
                }
                PlayerFactory.styleSubtitles(playerView)
                subtitleStyleMenu()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun findSubtitles() {
        if (!com.streambox.tv.data.OpenSubtitles.enabled) {
            AlertDialog.Builder(this)
                .setTitle("OpenSubtitles")
                .setMessage("Add a free OpenSubtitles API key in Settings → Online services, then try again.\n\nGet one at opensubtitles.com → Profile → API consumers.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val title = current.title
        toast("Searching subtitles…")
        lifecycleScope.launch {
            val results = try {
                com.streambox.tv.data.OpenSubtitles.search(title)
            } catch (e: Exception) {
                toast("Subtitle search failed: ${e.message}")
                return@launch
            }
            if (results.isEmpty()) {
                toast("No subtitles found for this title")
                return@launch
            }
            val top = results.take(25)
            val labels = top.map {
                "${java.util.Locale(it.language).displayLanguage.ifBlank { it.language }}  •  ${it.release.take(60)}  •  ⬇ ${it.downloads}"
            }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Choose subtitles")
                .setItems(labels) { _, w -> applyDownloadedSubtitle(top[w]) }
                .show()
        }
    }

    private fun applyDownloadedSubtitle(r: com.streambox.tv.data.OpenSubtitles.Result) {
        lifecycleScope.launch {
            val file = try {
                com.streambox.tv.data.OpenSubtitles.download(this@PlayerActivity, r)
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
                return@launch
            }
            val p = player ?: return@launch
            extraSubs = listOf(
                androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(r.language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
            startPos = p.currentPosition
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(r.language)
                .build()
            load(attemptUrl, attemptMime)
            toast("Subtitles added")
        }
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
        private var active: java.lang.ref.WeakReference<PlayerActivity>? = null
    }
}
