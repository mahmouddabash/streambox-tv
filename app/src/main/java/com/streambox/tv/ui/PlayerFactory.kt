package com.streambox.tv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambox.tv.data.Net

@OptIn(markerClass = [UnstableApi::class])
object PlayerFactory {

    fun build(context: Context): ExoPlayer {
        val http = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.userAgent())
        val dataSource = DefaultDataSource.Factory(context, http)
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
            .build()
    }

    fun mediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').lowercase()
        val b = MediaItem.Builder().setUri(url)
        when {
            path.endsWith(".m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }
}
