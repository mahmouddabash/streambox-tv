package com.streambox.tv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.streambox.tv.data.Net
import com.streambox.tv.data.Prefs

@OptIn(markerClass = [UnstableApi::class])
/**
 * Mixes surround sound (5.1, 7.1…) down to stereo when "Stereo sound" is on (default on car
 * screens, phones and tablets). Many car audio systems accept only two channels and stay silent
 * when sent six.
 */
class StereoAwareRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): androidx.media3.exoplayer.audio.AudioSink? {
        val b = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        if (Prefs.stereoAudio) {
            val mixer = androidx.media3.common.audio.ChannelMixingAudioProcessor()
            for (n in 1..8) mixer.putChannelMixingMatrix(toStereo(n))
            b.setAudioProcessors(arrayOf(mixer))
        }
        return b.build()
    }

    /** Standard downmix of an n-channel layout (L R C LFE Ls Rs Lb Rb order) to left/right. */
    private fun toStereo(n: Int): androidx.media3.common.audio.ChannelMixingMatrix {
        val h = 0.7071f
        val roles: List<Pair<Float, Float>> = when (n) {
            1 -> listOf(1f to 1f)
            2 -> listOf(1f to 0f, 0f to 1f)
            3 -> listOf(1f to 0f, 0f to 1f, h to h)
            4 -> listOf(1f to 0f, 0f to 1f, h to 0f, 0f to h)
            5 -> listOf(1f to 0f, 0f to 1f, h to h, h to 0f, 0f to h)
            6 -> listOf(1f to 0f, 0f to 1f, h to h, 0f to 0f, h to 0f, 0f to h)
            7 -> listOf(1f to 0f, 0f to 1f, h to h, 0f to 0f, h to 0f, 0f to h, 0.5f to 0.5f)
            else -> listOf(1f to 0f, 0f to 1f, h to h, 0f to 0f, h to 0f, 0f to h, h to 0f, 0f to h)
        }
        // Keep it loud but avoid clipping when every channel peaks at once.
        val sum = roles.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
        val scale = if (n <= 2) 1f else (1.4f / sum).coerceAtMost(1f)
        val m = FloatArray(n * 2)
        roles.forEachIndexed { i, (l, r) ->
            m[i * 2] = l * scale
            m[i * 2 + 1] = r * scale
        }
        return androidx.media3.common.audio.ChannelMixingMatrix(n, 2, m)
    }
}

@OptIn(markerClass = [UnstableApi::class])
object PlayerFactory {
    /** True when the data saver applies right now (Settings → Picture quality on mobile data). */
    fun saveData(context: Context): Boolean = when (Prefs.dataSaver) {
        1 -> false
        2 -> true
        else -> try {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        } catch (_: Exception) {
            false
        }
    }


    /**
     * [audioFocus]: the main player asks Android for audio focus. Car systems mute apps that play
     * without it; multi-screen tiles and previews don't ask, so they don't pause each other.
     */
    fun build(context: Context, audioFocus: Boolean = true): ExoPlayer {
        val http = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.userAgent())
            .setCacheControl(okhttp3.CacheControl.FORCE_NETWORK)
        val dataSource = DefaultDataSource.Factory(context, http)
        // The device's own decoders first; StreamBox's software decoder fills the gaps (Dolby, DTS…).
        // "Software audio" (Settings) always uses it, for units whose own decoder plays silence.
        val renderers = StereoAwareRenderers(context).setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (Prefs.softwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        // Pick the best picture: no size cap (4K screens included), prefer the highest-bitrate
        // variant, and assume a fast connection so playback starts sharp instead of ramping up.
        val selector = DefaultTrackSelector(context)
        selector.setParameters(
            selector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setViewportSizeToPhysicalDisplaySize(context, false)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setForceHighestSupportedBitrate(Prefs.maxQuality && !saveData(context))
                .apply {
                    // Data saver: on mobile data / a phone hotspot (or always), stay at 720p and ~2.5 Mbps
                    // when the provider offers several qualities.
                    if (saveData(context)) setMaxVideoSize(1280, 720).setMaxVideoBitrate(2_500_000)
                }
                .apply {
                    // Automatic languages: the preferred audio track, and subtitles only when asked for.
                    if (Prefs.audioLanguage.isNotEmpty()) setPreferredAudioLanguages(Prefs.audioLanguage, "en")
                    if (Prefs.subtitleLanguage.isNotEmpty()) {
                        setPreferredTextLanguages(Prefs.subtitleLanguage, "en")
                        setSelectUndeterminedTextLanguage(true)
                        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    }
                }
        )
        val bandwidth = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(25_000_000L)
            .build()
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Seeking inside .ts files (and other streams without an index) uses the bitrate instead of failing.
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setTrackSelector(selector)
            .setBandwidthMeter(bandwidth)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                audioFocus
            )
            .build()
        // Jump to the nearest key frame: seeks finish much faster than frame-exact ones.
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        return player
    }

    /** Languages offered in Settings (code to name). */
    val LANGUAGES = listOf(
        "ar" to "العربية (Arabic)", "en" to "English", "fr" to "Français", "tr" to "Türkçe", "es" to "Español",
        "de" to "Deutsch", "hi" to "हिन्दी (Hindi)", "ur" to "اردو (Urdu)", "fa" to "فارسی (Persian)"
    )

    /** ◀ ▶ / ⏪ ⏩ step for movies and episodes. */
    const val SEEK_STEP_MS = 60_000L

    /** Applies the subtitle size, colour and background chosen in Settings. */
    fun styleSubtitles(view: androidx.media3.ui.PlayerView) {
        val sv = view.subtitleView ?: return
        val fg = when (Prefs.subtitleColor) { 1 -> 0xFFFFE14D.toInt(); 2 -> 0xFF7FE7FF.toInt(); else -> 0xFFFFFFFF.toInt() }
        val (bg, edge) = when (Prefs.subtitleBackground) {
            1 -> 0xB3000000.toInt() to androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
            2 -> 0x00000000 to androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> 0x00000000 to androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setStyle(androidx.media3.ui.CaptionStyleCompat(fg, bg, 0x00000000, edge, 0xFF000000.toInt(), android.graphics.Typeface.DEFAULT_BOLD))
        val scale = floatArrayOf(0.8f, 1.0f, 1.3f, 1.65f)[Prefs.subtitleSize.coerceIn(0, 3)]
        sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
    }

    /** Effects for the chosen picture mode (empty = untouched, zero overhead). */
    fun effects(): List<androidx.media3.common.Effect> {
        val mode = PictureMode.of(Prefs.pictureMode)
        return if (mode == PictureMode.OFF) emptyList() else listOf(PictureEffect(mode))
    }

    /** [mimeType] forces the format (used when probing found the server sends something else). */
    fun mediaItem(url: String, mimeType: String? = null): MediaItem {
        val path = url.substringBefore('?').lowercase()
        val b = MediaItem.Builder().setUri(url)
        when {
            mimeType != null -> b.setMimeType(mimeType)
            path.endsWith(".m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }
}
