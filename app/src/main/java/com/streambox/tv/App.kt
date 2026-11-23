package com.streambox.tv

import android.app.Application
import com.streambox.tv.data.Prefs

class App : Application(), coil.ImageLoaderFactory {
    /** Posters and logos: cached on disk even when IPTV servers say "no-cache", 16-bit bitmaps to save memory. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient { com.streambox.tv.data.Net.client }
        .respectCacheHeaders(false)
        .allowRgb565(true)
        .crossfade(false)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(200L * 1024 * 1024).build() }
        .build()

    override fun onCreate() {
        super.onCreate()
        com.streambox.tv.data.CrashReporter.install(this)
        Prefs.init(this)
        com.streambox.tv.data.Reminders.init(this)
        com.streambox.tv.ui.Tr.init(this)
    }
}
