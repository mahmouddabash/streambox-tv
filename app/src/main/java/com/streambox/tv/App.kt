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
        com.streambox.tv.data.Downloads.recover(this)
        keepContentClearOfSystemBars()
        addTouchBackArrow()
        addNightMode()
        // True-black background for OLED screens (Settings → Look).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                if (Prefs.oledBlack) a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            }
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // The phone remote sends its keys to whichever StreamBox screen is showing.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { com.streambox.tv.data.RemoteServer.foreground = a }
            override fun onActivityPaused(a: android.app.Activity) {
                if (com.streambox.tv.data.RemoteServer.foreground === a) com.streambox.tv.data.RemoteServer.foreground = null
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        Prefs.detectedTouch = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) &&
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    /** Night mode: a soft dark layer over every screen (video included) at night or always. */
    private fun addNightMode() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) {
                val content = a.findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val on = when (Prefs.nightMode) {
                    2 -> true
                    1 -> h >= 22 || h < 6
                    else -> false
                }
                var layer = content.findViewWithTag<android.view.View>("sb_night")
                if (!on) {
                    layer?.let { content.removeView(it) }
                    return
                }
                if (layer == null) {
                    layer = android.view.View(a).apply {
                        tag = "sb_night"
                        setBackgroundColor(0x59000000) // ~35 % darker
                        isClickable = false
                        isFocusable = false
                        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    content.addView(layer, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
                layer.bringToFront()
                layer.elevation = 100f
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /**
     * Touch screens (phones, tablets, car head units) have no remote Back button: show a back arrow
     * in the top corner of every screen except Home. TVs (leanback) don't get it.
     */
    private fun addTouchBackArrow() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                if (a is com.streambox.tv.ui.HomeActivity || a is com.streambox.tv.ui.SplashActivity) return
                val content = a.findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
                val existing = content.findViewWithTag<android.view.View>("sb_back")
                // Only in touch mode (chosen on first start, Settings → Device type).
                if (!Prefs.isTouchDevice) {
                    existing?.let { content.removeView(it) }
                    return
                }
                if (existing != null) return
                val d = a.resources.displayMetrics.density
                val btn = android.widget.ImageButton(a).apply {
                    tag = "sb_back"
                    setImageResource(R.drawable.ic_back_arrow)
                    contentDescription = "Back"
                    isFocusable = false // the remote's D-pad never lands on it
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x88000000.toInt())
                    }
                    elevation = 24 * d
                    setOnClickListener {
                        (a as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: a.finish()
                    }
                }
                val size = (48 * d).toInt()
                content.addView(btn, android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.START).apply {
                    setMargins((10 * d).toInt(), (10 * d).toInt(), 0, 0)
                })
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /**
     * Android 15+ draws apps behind the status and navigation bars (on phones). Pad every screen
     * except the full-screen players so nothing ends up under the bars. TVs have no bars: no change.
     */
    private fun keepContentClearOfSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(a: android.app.Activity, b: android.os.Bundle?) {
                if (a is com.streambox.tv.ui.PlayerActivity || a is com.streambox.tv.ui.MultiViewActivity) return
                val content = a.findViewById<android.view.View>(android.R.id.content) ?: return
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                    val bars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                    )
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
