package com.streambox.tv.data

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.streambox.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a report when the app crashes; on the next start the user can send it by scanning a QR code
 * (opens a pre-filled GitHub issue on their phone). Nothing leaves the TV without the user doing that.
 */
object CrashReporter {
    private lateinit var file: File

    fun install(app: Application) {
        file = File(app.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText(header() + "\nThread: ${thread.name}\n\n" + scrub(sw.toString()))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
        recordSilentExit(app)
    }

    /**
     * Some closes leave no Java error (a crash inside native code such as the VPN, the system
     * running out of memory, or a freeze). Android 11+ keeps the reason: save it as a report too.
     */
    private fun recordSilentExit(app: Application) {
        if (Build.VERSION.SDK_INT < 30 || file.exists()) return
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return
            val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() ?: return
            val sp = app.getSharedPreferences("streambox_crash", 0)
            if (info.timestamp <= sp.getLong("seenExit", 0L)) return
            sp.edit().putLong("seenExit", info.timestamp).apply()
            val why = when (info.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                android.app.ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by the system: low memory"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by the system: too much resource use"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
                else -> return // normal exits (user, update, background kill) are not problems
            }
            var trace = ""
            if (info.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                trace = try { info.traceInputStream?.bufferedReader()?.use { it.readText().take(6000) } ?: "" } catch (_: Throwable) { "" }
            }
            file.writeText(header() + "\nExit: $why (${info.description ?: ""})\nWhen: " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp)) +
                "\nMemory at exit: ${info.pss / 1024} MB\nFree VPN on: ${try { Prefs.vpnOn } catch (_: Throwable) { "?" }}\n\n" + scrub(trace))
        } catch (_: Throwable) {
        }
    }

    fun header(): String = buildString {
        append("StreamBox TV ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n")
    }

    /** Never put a provider login into a report. */
    private fun scrub(text: String): String {
        var t = text
        for (p in try { Prefs.profiles() } catch (_: Throwable) { emptyList() }) {
            for (secret in listOf(p.server, p.username, p.password, p.m3uUrl)) {
                if (secret.length >= 3) t = t.replace(secret, "•••")
            }
        }
        return t
    }

    fun lastReport(): String? = if (::file.isInitialized && file.exists()) file.readText() else null

    fun clear() {
        if (::file.isInitialized) file.delete()
    }

    /** After a crash: offer to send the report on the next start. */
    fun offerIfCrashed(activity: Activity) {
        val report = lastReport() ?: return
        AlertDialog.Builder(activity)
            .setTitle("StreamBox closed unexpectedly")
            .setMessage("A problem report was saved. Send it so the problem can be fixed? Your logins are not included.")
            .setPositiveButton("Send report") { _, _ -> showReportQr(activity, report) }
            .setNegativeButton("Not now") { _, _ -> clear() }
            .show()
    }

    fun showReportQr(activity: Activity, report: String) {
        val title = report.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") || it.startsWith("Exit:") }?.take(90) ?: "Crash report"
        val url = "https://github.com/mahmouddabash/streambox-tv/issues/new?title=" + Uri.encode("Crash: $title") +
            "&body=" + Uri.encode("```\n" + report.take(1400) + "\n```")
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(activity).apply {
            text = "Scan with your phone to send the report:"
            setTextColor(0xFFF5F7FF.toInt())
        })
        box.addView(ImageView(activity).apply {
            val size = (300 * activity.resources.displayMetrics.density).toInt()
            setImageBitmap(com.streambox.tv.ui.Qr.bitmap(url, size))
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = pad / 2 }
        })
        AlertDialog.Builder(activity)
            .setTitle("Send problem report")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> clear() }
            .show()
    }
}
