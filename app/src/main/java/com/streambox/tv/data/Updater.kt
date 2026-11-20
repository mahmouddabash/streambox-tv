package com.streambox.tv.data

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.streambox.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Checks GitHub Releases for a newer build and installs it in place. */
object Updater {
    private const val API = "https://api.github.com/repos/mahmouddabash/streambox-tv/releases/latest"
    private const val DAY = 86_400_000L

    data class Release(val build: Int, val apkUrl: String, val notes: String)

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = JSONObject(r.body?.string() ?: return@withContext null)
                val build = o.optString("tag_name").substringAfterLast('.').toIntOrNull() ?: return@withContext null
                val assets = o.optJSONArray("assets") ?: return@withContext null
                var url = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                if (url.isEmpty()) null else Release(build, url, o.optString("body"))
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Silent daily check from the home screen; shows a dialog only when an update exists. */
    fun checkDaily(activity: Activity, scope: LifecycleCoroutineScope) {
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < DAY) return
        Prefs.lastUpdateCheck = System.currentTimeMillis()
        check(activity, scope, quiet = true)
    }

    fun check(activity: Activity, scope: LifecycleCoroutineScope, quiet: Boolean) {
        scope.launch {
            val r = latest()
            if (activity.isFinishing) return@launch
            when {
                r == null -> if (!quiet) toast(activity, "Couldn't reach GitHub to check for updates")
                r.build <= currentBuild -> if (!quiet) toast(activity, "You have the latest version (build $currentBuild)")
                else -> AlertDialog.Builder(activity)
                    .setTitle("Update available")
                    .setMessage("Build ${r.build} is ready (you have build $currentBuild).\nInstall it now? Your profiles and settings are kept.")
                    .setPositiveButton("Install now") { _, _ -> download(activity, scope, r) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun toast(a: Activity, msg: String) =
        android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, r: Release) {
        val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(60, 30, 60, 30)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading update…")
            .setView(bar)
            .setCancelable(false)
            .show()
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "StreamBoxTV-${r.build}.apk")
                    Net.client.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw java.io.IOException("Empty download")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            out.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        withContext(Dispatchers.Main) { bar.progress = pct }
                                    }
                                }
                            }
                        }
                    }
                    out
                }
            } catch (e: Exception) {
                dialog.dismiss()
                toast(activity, "Download failed: ${e.message}")
                return@launch
            }
            dialog.dismiss()
            install(activity, file)
        }
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "Allow StreamBox to install apps, then choose Install now again")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (_: Exception) {
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(i)
        } catch (e: Exception) {
            toast(activity, "Couldn't open the installer: ${e.message}")
        }
    }
}
