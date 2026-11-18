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
object PlayerFactory {

    fun build(context: Context): ExoPlayer {
        val http = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.userAgent())
        val dataSource = DefaultDataSource.Factory(context, http)
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

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
                .setForceHighestSupportedBitrate(Prefs.maxQuality)
        )
        val bandwidth = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(25_000_000L)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
            .setTrackSelector(selector)
            .setBandwidthMeter(bandwidth)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
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
