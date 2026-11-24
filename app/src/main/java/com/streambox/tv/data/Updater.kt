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

    data class Release(val build: Int, val apkUrl: String, val notes: String, val shaUrl: String = "")

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
                var sha = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val n = a.optString("name")
                    if (n.endsWith(".apk")) url = a.optString("browser_download_url")
                    if (n.endsWith(".apk.sha256")) sha = a.optString("browser_download_url")
                }
                if (url.isEmpty()) null else Release(build, url, o.optString("body"), sha)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** After an update, shows the release notes of the new build once. */
    fun showWhatsNew(activity: Activity, scope: LifecycleCoroutineScope) {
        val seen = Prefs.lastSeenBuild
        Prefs.lastSeenBuild = currentBuild
        if (seen == 0 || seen >= currentBuild) return
        scope.launch {
            val notes = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/mahmouddabash/streambox-tv/releases/tags/v1.0.$currentBuild")
                        .header("Accept", "application/vnd.github+json").build()
                    Net.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else JSONObject(r.body?.string() ?: "{}").optString("body")
                    }
                } catch (_: Exception) {
                    null
                }
            }?.lineSequence()
                ?.filterNot { it.contains("Download **StreamBoxTV.apk**") }
                ?.joinToString("\n")?.replace("**", "")?.trim()
            if (notes.isNullOrBlank() || activity.isFinishing) return@launch
            AlertDialog.Builder(activity)
                .setTitle("What's new in build $currentBuild")
                .setMessage(notes)
                .setPositiveButton("OK", null)
                .show()
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
                    .setPositiveButton("Install with Downloader") { _, _ -> openInDownloader(activity, r.apkUrl) }
                    .setNeutralButton("Install now") { _, _ -> download(activity, scope, r) }
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
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
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
                                    digest.update(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        withContext(Dispatchers.Main) { bar.progress = pct }
                                    }
                                }
                            }
                        }
                    }
                    // 1) The file must match the checksum published with the release.
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    if (r.shaUrl.isNotEmpty()) {
                        val want = Net.client.newCall(Request.Builder().url(r.shaUrl).build()).execute().use {
                            if (!it.isSuccessful) throw java.io.IOException("checksum unavailable (HTTP ${it.code})")
                            it.body?.string().orEmpty().trim().substringBefore(' ').lowercase()
                        }
                        if (want != got) {
                            out.delete()
                            throw SecurityException("the file is damaged or was changed (checksum mismatch)")
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
            // 2) It must be StreamBox, signed with the same key as the app on this TV.
            when (checkSigner(activity, file)) {
                Signer.SAME, Signer.UNKNOWN -> install(activity, file)
                Signer.OTHER_APP -> {
                    file.delete()
                    toast(activity, "Update blocked: the download isn't StreamBox")
                }
                Signer.NEW_KEY -> {
                    file.delete()
                    AlertDialog.Builder(activity)
                        .setTitle("One-time reinstall needed")
                        .setMessage(
                            "This build is signed with StreamBox's new private security key, so Android can't install it over this copy.\n\n" +
                                "1. Settings → Backup & restore → back up your profiles and settings.\n" +
                                "2. Uninstall StreamBox.\n" +
                                "3. Install it again with Downloader:\n   $SHORT_LINK\n" +
                                "4. Restore your backup."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private enum class Signer { SAME, NEW_KEY, OTHER_APP, UNKNOWN }

    /** Compares the downloaded APK's package name and signing certificate with the installed app. */
    @Suppress("DEPRECATION")
    private fun checkSigner(activity: Activity, file: File): Signer = try {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags)
        val mine = pm.getPackageInfo(activity.packageName, flags)
        fun certs(i: android.content.pm.PackageInfo?): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) i?.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            } else i?.signatures
            return sigs.orEmpty().map { sig ->
                java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        when {
            apk == null -> Signer.UNKNOWN
            apk.packageName != activity.packageName -> Signer.OTHER_APP
            else -> {
                val a = certs(apk)
                val m = certs(mine)
                when {
                    a.isEmpty() || m.isEmpty() -> Signer.UNKNOWN // Android still enforces the match itself
                    a.any { it in m } -> Signer.SAME
                    else -> Signer.NEW_KEY
                }
            }
        }
    } catch (_: Exception) {
        Signer.UNKNOWN
    }

    /**
     * Opens Android's installer right away. Some TVs (TCL and others) report that installing isn't
     * allowed even when it is, so we don't ask first: the system shows its own prompt if needed.
     */
    private fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        @Suppress("DEPRECATION")
        val attempts = listOf(
            Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, false),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        )
        for (i in attempts) {
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        manualInstall(activity)
    }

    /** Last resort: tell the user how to install with the Downloader app (or allow StreamBox in settings). */
    private fun manualInstall(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Install the update")
            .setMessage(
                "This TV didn't open its installer. You can:\n\n" +
                    "• Open the Downloader app and enter:\n  $LINK\n\n" +
                    "• Or allow StreamBox under Settings → Apps → Install unknown apps, then try again."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    val i = if (Build.VERSION.SDK_INT >= 26)
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    else Intent(Settings.ACTION_SECURITY_SETTINGS)
                    activity.startActivity(i)
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private const val DOWNLOADER = "com.esaba.downloader"

    /**
     * Hands the newest APK link to the Downloader app (already allowed to install on most TVs).
     * If Downloader can't take the link directly, it is opened and the address is shown to type in.
     */
    fun openInDownloader(activity: Activity, exactUrl: String? = null) {
        val pm = activity.packageManager
        // A link unique to this version, so Downloader can't reuse an older file it saved before.
        val url = exactUrl ?: "$LINK?b=${System.currentTimeMillis() / 60000}"
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(DOWNLOADER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(direct)
            return
        } catch (_: Exception) {
        }
        val launch = pm.getLaunchIntentForPackage(DOWNLOADER)
            ?: pm.getLeanbackLaunchIntentForPackage(DOWNLOADER)
        if (launch == null) {
            AlertDialog.Builder(activity)
                .setTitle("Downloader isn't installed")
                .setMessage("Install “Downloader” by AFTVnews from the Play Store, then enter:\n\n$SHORT_LINK")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Install with Downloader")
            .setMessage("Downloader will open. Type this address in its box and press Go:\n\n$SHORT_LINK")
            .setPositiveButton("Open Downloader") { _, _ ->
                try {
                    activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    const val SHORT_LINK = "github.com/mahmouddabash/streambox-tv/releases/latest/download/StreamBoxTV.apk"

    const val LINK = "https://github.com/mahmouddabash/streambox-tv/releases/latest/download/StreamBoxTV.apk"
}
