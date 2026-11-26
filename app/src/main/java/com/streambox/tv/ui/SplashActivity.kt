package com.streambox.tv.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.streambox.tv.R
import com.streambox.tv.data.Prefs

/** Short animated brand intro, then the profile picker (which opens the last profile on its own). */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private fun askDeviceType(then: () -> Unit) {
        val tv = "📺  " + Tr.s("TV with a remote control")
        val touch = "📱  " + Tr.s("Touch screen (car, phone, tablet)")
        val rec = "   (" + Tr.s("recommended") + ")"
        // The option that matches this device comes first and is marked.
        val opts = if (Prefs.detectedTouch) listOf("touch" to touch + rec, "tv" to tv) else listOf("tv" to tv + rec, "touch" to touch)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Tr.s("How will you use StreamBox on this device?"))
            .setItems(opts.map { it.second }.toTypedArray()) { _, w ->
                Prefs.deviceMode = opts[w].first
                then()
            }
            .setCancelable(false)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<View>(R.id.logo)
        val word = findViewById<View>(R.id.wordmark)
        val tag = findViewById<View>(R.id.tagline)
        val bar = findViewById<View>(R.id.bar)
        val fill = findViewById<View>(R.id.barFill)

        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        word.translationY = 30f
        word.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(500).start()
        tag.animate().alpha(1f).setStartDelay(450).setDuration(500).start()
        bar.animate().alpha(1f).setStartDelay(550).setDuration(300).start()

        // Light sweeping across the loading bar
        val density = resources.displayMetrics.density
        ObjectAnimator.ofFloat(fill, View.TRANSLATION_X, -60 * density, 180 * density).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        handler.postDelayed({ next() }, 1500)
    }

    private fun next() {
        if (done || isFinishing) return
        // First start: TV with a remote, or a touch screen (car, phone, tablet)?
        if (Prefs.deviceMode.isEmpty()) {
            done = true
            askDeviceType {
                done = false
                next()
            }
            return
        }
        done = true
        val profiles = Intent(this, ProfilesActivity::class.java)
        val last = Prefs.profiles().firstOrNull { it.id == Prefs.lastProfileId }
        if (Prefs.openLastProfile && last != null && (!Prefs.isProtected(last) || Prefs.protectedUnlocked)) {
            // Profile list underneath (so Back still reaches it), then straight into the last profile.
            startActivities(arrayOf(
                profiles,
                Intent(this, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_PROFILE, last.id)
                    .putExtra(HomeActivity.EXTRA_RESTORE, true)
                    .apply { intent.getStringExtra(HomeActivity.EXTRA_PLAY_CHANNEL)?.let { putExtra(HomeActivity.EXTRA_PLAY_CHANNEL, it) } }
            ))
        } else {
            startActivity(profiles)
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
