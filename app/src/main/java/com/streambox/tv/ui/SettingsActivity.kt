package com.streambox.tv.ui

import android.os.Bundle
import android.text.InputType
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Net
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo

class SettingsActivity : AppCompatActivity() {

    private class Row(val title: String, val value: String, val action: () -> Unit)

    private lateinit var adapter: SimpleAdapter<Row>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.title).text = "Settings"
        findViewById<TextView>(R.id.status).text = "StreamBox TV 1.0"

        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, r, _ ->
                v.findViewById<TextView>(R.id.line1).text = r.title
                v.findViewById<TextView>(R.id.line2).text = r.value
            },
            onClick = { r, _ -> r.action() }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter
        refresh()
    }

    private fun refresh() {
        val rows = ArrayList<Row>()

        rows.add(Row("Live stream format", if (Prefs.streamFormat == "ts") "MPEG-TS (.ts)" else "HLS (.m3u8)") {
            choose("Live stream format", arrayOf("MPEG-TS (.ts)", "HLS (.m3u8)"), if (Prefs.streamFormat == "ts") 0 else 1) {
                Prefs.streamFormat = if (it == 0) "ts" else "m3u8"
            }
        })
        rows.add(Row("About & diagnostics", "Version, connection test, subscription status, problem reports, privacy") {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        })
        rows.add(Row("Language", if (Prefs.language == "ar") "العربية" else "English") {
            choose("Language", arrayOf("English", "العربية"), if (Prefs.language == "ar") 1 else 0) {
                Tr.setLanguage(if (it == 1) "ar" else "en")
            }
        })
        rows.add(Row("Theme colour", Tr.accentNames[Tr.accents.indexOf(Prefs.accent).coerceAtLeast(0)]) {
            choose("Theme colour", Tr.accentNames.toTypedArray(), Tr.accents.indexOf(Prefs.accent).coerceAtLeast(0)) {
                Prefs.accent = Tr.accents[it]
                // Reopen the app so every screen picks up the new colours (resume-where-left-off brings you back).
                startActivity(android.content.Intent(this, SplashActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        })
        if (com.streambox.tv.BuildConfig.SELF_UPDATE) rows.add(Row("Check for updates", "Installed: build ${com.streambox.tv.data.Updater.currentBuild}. Checked automatically once a day") {
            com.streambox.tv.data.Updater.check(this, lifecycleScope, quiet = false)
        })
        rows.add(Row("Picture mode", PictureMode.of(Prefs.pictureMode).label) {
            val modes = PictureMode.values()
            choose("Picture mode", modes.map { it.label }.toTypedArray(), modes.indexOf(PictureMode.of(Prefs.pictureMode))) {
                Prefs.pictureMode = modes[it].id
            }
        })
        rows.add(Row("Video quality", if (Prefs.maxQuality) "Always highest" else "Auto (starts high, adapts to your connection)") {
            Prefs.maxQuality = !Prefs.maxQuality
            refresh()
        })
        rows.add(Row("Player", if (Prefs.externalPlayer) "External player app (VLC, MX Player…)" else "Built-in player") {
            Prefs.externalPlayer = !Prefs.externalPlayer
            refresh()
        })
        rows.add(Row("Channel preview in Live TV", if (Prefs.previewEnabled) "On: first OK previews, second OK goes full screen" else "Off: OK opens full screen") {
            Prefs.previewEnabled = !Prefs.previewEnabled
            refresh()
        })
        rows.add(Row("Resume where I left off", if (Prefs.openLastProfile) "On: reopen the last profile, screen and channel" else "Off: always show the profile list") {
            Prefs.openLastProfile = !Prefs.openLastProfile
            refresh()
        })
        rows.add(Row("Time format", if (Prefs.use24h) "24-hour" else "12-hour") {
            Prefs.use24h = !Prefs.use24h
            refresh()
        })
        rows.add(Row("TV guide time shift", "${if (Prefs.epgShiftHours >= 0) "+" else ""}${Prefs.epgShiftHours} h") {
            val values = (-12..12).toList()
            choose("TV guide time shift", values.map { "${if (it >= 0) "+" else ""}$it h" }.toTypedArray(), values.indexOf(Prefs.epgShiftHours)) {
                Prefs.epgShiftHours = values[it]
                Repo.clearCache(this)
            }
        })
        rows.add(Row("Refresh channel list automatically", "Every ${Prefs.cacheHours} hours") {
            val values = listOf(1, 6, 12, 24, 72)
            choose("Refresh interval", values.map { "Every $it hours" }.toTypedArray(), values.indexOf(Prefs.cacheHours)) {
                Prefs.cacheHours = values[it]
            }
        })
        rows.add(Row("Player identity (User-Agent)", Prefs.userAgent.ifBlank { "Default (${Net.DEFAULT_UA})" }) {
            val presets = listOf("") + Net.FALLBACK_UAS
            val labels = presets.map { if (it.isEmpty()) "Default" else it.take(48) } + "Custom…"
            AlertDialog.Builder(this)
                .setTitle("Player identity (User-Agent)")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < presets.size) {
                        Prefs.userAgent = presets[which]
                        refresh()
                    } else {
                        Ui.input(this, "User-Agent", Prefs.userAgent, InputType.TYPE_CLASS_TEXT) {
                            Prefs.userAgent = it
                            refresh()
                        }
                    }
                }
                .show()
        })
        fun langName(code: String, none: String) =
            PlayerFactory.LANGUAGES.firstOrNull { it.first == code }?.second ?: none
        rows.add(Row("Backup & restore", "Save everything (logins, viewers, favourites, settings) to your phone or a USB drive, and restore it on any TV") { backupMenu() })
        rows.add(Row("Viewers", (listOf(Prefs.mainViewerName) + Prefs.viewers(Repo.profileId).map { (if (it.kids) "🧒 " else "") + it.name })
            .joinToString(", ") + "   •   separate favourites and Continue watching, Kids profiles") { viewersMenu() })
        rows.add(Row("Channel manager", "Sort channels, show hidden channels and categories, undo renames") { channelManager() })
        rows.add(Row("Audio language", langName(Prefs.audioLanguage, "Stream default") + "   •   chosen automatically on every channel and movie") {
            val opts = listOf("" to "Stream default") + PlayerFactory.LANGUAGES
            choose("Audio language", opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == Prefs.audioLanguage }.coerceAtLeast(0)) {
                Prefs.audioLanguage = opts[it].first
            }
        })
        rows.add(Row("Subtitles", langName(Prefs.subtitleLanguage, "Off") + "   •   switched on automatically when available") {
            val opts = listOf("" to "Off") + PlayerFactory.LANGUAGES
            choose("Subtitles", opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == Prefs.subtitleLanguage }.coerceAtLeast(0)) {
                Prefs.subtitleLanguage = opts[it].first
            }
        })
        rows.add(Row("Subtitle style", "Size, colour and background of subtitles") {
            val sizes = arrayOf("Small", "Normal", "Large", "Extra large")
            val colors = arrayOf("White", "Yellow", "Cyan")
            val bgs = arrayOf("Outline", "Dark box", "Shadow")
            AlertDialog.Builder(this)
                .setTitle("Subtitle style")
                .setItems(arrayOf(
                    "Size: ${sizes[Prefs.subtitleSize.coerceIn(0, 3)]}",
                    "Colour: ${colors[Prefs.subtitleColor.coerceIn(0, 2)]}",
                    "Background: ${bgs[Prefs.subtitleBackground.coerceIn(0, 2)]}"
                )) { _, w ->
                    when (w) {
                        0 -> choose("Subtitle size", sizes, Prefs.subtitleSize) { Prefs.subtitleSize = it }
                        1 -> choose("Subtitle colour", colors, Prefs.subtitleColor) { Prefs.subtitleColor = it }
                        else -> choose("Subtitle background", bgs, Prefs.subtitleBackground) { Prefs.subtitleBackground = it }
                    }
                }
                .show()
        })
        rows.add(Row("Subtitle languages (online)", Prefs.subtitleLanguages.ifBlank { "en" } + "  (comma-separated codes, e.g. ar,en)") {
            Ui.input(this, "Subtitle languages", Prefs.subtitleLanguages, InputType.TYPE_CLASS_TEXT) {
                Prefs.subtitleLanguages = it
                refresh()
            }
        })
        rows.add(Row("OpenSubtitles API key", if (Prefs.openSubtitlesKey.isBlank()) "Not set. Free key from opensubtitles.com → API consumers; enables “Find subtitles online”" else "Set ✓") {
            Ui.input(this, "OpenSubtitles API key", Prefs.openSubtitlesKey, InputType.TYPE_CLASS_TEXT) {
                Prefs.openSubtitlesKey = it
                refresh()
            }
        })
        rows.add(Row("TMDB API key", if (Prefs.tmdbKey.isBlank()) "Not set. Free key from themoviedb.org → Settings → API; adds HD backdrops, cast photos and trailers" else "Set ✓") {
            Ui.input(this, "TMDB API key (v3)", Prefs.tmdbKey, InputType.TYPE_CLASS_TEXT) {
                Prefs.tmdbKey = it
                refresh()
            }
        })
        val dirs = com.streambox.tv.data.Recorder.storageDirs(this)
        rows.add(Row("Recording location", com.streambox.tv.data.Recorder.storageDir(this).absolutePath +
            if (dirs.size > 1) "   •   ${dirs.size} drives found" else "   •   plug in a USB drive to record to it") {
            val labels = dirs.mapIndexed { i, d -> (if (i == 0) "TV storage" else "USB / SD card $i") + "   " + d.absolutePath }.toTypedArray()
            choose("Record to", labels, Prefs.recordingStorage.coerceIn(0, (dirs.size - 1).coerceAtLeast(0))) { Prefs.recordingStorage = it }
        })
        val dlDirs = com.streambox.tv.data.Downloads.dirs(this)
        val dlNow = com.streambox.tv.data.Downloads.dir(this)
        rows.add(Row("Download location", (if (Prefs.downloadStorage <= 0 || dlDirs.size < 2) "TV storage" else "USB / SD card ${Prefs.downloadStorage}") +
            "   •   " + com.streambox.tv.data.Downloads.freeSpace(dlNow) +
            if (dlDirs.size > 1) "   •   ${dlDirs.size} drives found" else "   •   plug in a USB drive to save films there") {
            val labels = dlDirs.mapIndexed { i, d ->
                d.mkdirs()
                (if (i == 0) "TV storage" else "USB / SD card $i") + "   •   " + com.streambox.tv.data.Downloads.freeSpace(d)
            }.toTypedArray()
            choose("Save new downloads to", labels, Prefs.downloadStorage.coerceIn(0, (dlDirs.size - 1).coerceAtLeast(0))) {
                Prefs.downloadStorage = it
                if (it > 0) toast("New downloads go to the USB drive. Keep it plugged in to watch them")
            }
        })
        rows.add(Row("Picture-in-picture on Home button", if (Prefs.autoPip) "On: keep watching in a small window" else "Off") {
            Prefs.autoPip = !Prefs.autoPip
            refresh()
        })
        rows.add(Row("Parental control", if (Prefs.parentalEnabled) "PIN is set. Adult categories and locked categories ask for it" else "Off. Set a PIN to lock categories") {
            parental()
        })
        rows.add(Row("Clear cached data", "Channel lists and TV guide are downloaded again next time") {
            Repo.clearCache(this)
            toast("Cache cleared")
        })
        rows.add(Row("Clear resume positions", "Forget where you stopped in movies and episodes") {
            Prefs.clearResumePositions()
            toast("Resume positions cleared")
        })
        adapter.submit(rows)
    }

    private fun choose(title: String, options: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, current) { d, which ->
                onPick(which)
                d.dismiss()
                refresh()
            }
            .show()
    }

    // ------------------------------------------------------------------ Backup & restore

    private var backupServer: com.streambox.tv.data.BackupServer? = null
    private val passwordType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun backupMenu() {
        val options = arrayOf(
            "📱  Back up to my phone",
            "📱  Restore from my phone",
            "💾  Save backup on this TV or a USB drive",
            "💾  Restore from a file on this TV or USB"
        )
        AlertDialog.Builder(this)
            .setTitle("Backup & restore")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> withNewBackup { text -> showPhonePage(text) }
                    1 -> showPhonePage(null)
                    2 -> withNewBackup { text ->
                        val drives = com.streambox.tv.data.Backup.folders(this)
                        val save = { i: Int ->
                            try {
                                val f = com.streambox.tv.data.Backup.saveToFile(this, text, i)
                                AlertDialog.Builder(this).setTitle("Backup saved").setMessage(f.absolutePath)
                                    .setPositiveButton("OK", null).show()
                            } catch (e: Exception) {
                                toast("Couldn't save: ${e.message}")
                            }
                        }
                        if (drives.size <= 1) save(0) else choose("Save to", drives.mapIndexed { i, d ->
                            (if (i == 0) "TV storage" else "USB / SD card $i") + "   " + d.absolutePath
                        }.toTypedArray(), 0) { save(it) }
                    }
                    else -> {
                        val files = com.streambox.tv.data.Backup.files(this)
                        if (files.isEmpty()) toast("No backup files found on this TV or USB drives")
                        else AlertDialog.Builder(this)
                            .setTitle("Choose a backup")
                            .setItems(files.map { it.name + "   " + (it.parentFile?.parent ?: "") }.toTypedArray()) { _, i ->
                                askRestorePassword(files[i].readText())
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    /** Asks for a password (twice) and builds the encrypted backup in the background. */
    private fun withNewBackup(then: (String) -> Unit) {
        Ui.input(this, "Choose a backup password (4+ characters)", "", passwordType) { pw ->
            if (pw.length < 4) { toast("Use at least 4 characters"); return@input }
            Ui.input(this, "Repeat the backup password", "", passwordType) { again ->
                if (again != pw) { toast("Passwords do not match"); return@input }
                toast("Preparing backup…")
                lifecycleScope.launch {
                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        com.streambox.tv.data.Backup.create(pw)
                    }
                    then(text)
                }
            }
        }
    }

    /** QR code for the phone page: download [backupText] (if any) and/or upload a backup to restore. */
    private fun showPhonePage(backupText: String?) {
        backupServer?.stop()
        val server = com.streambox.tv.data.BackupServer(backupText) { uploaded ->
            runOnUiThread { askRestorePassword(uploaded) }
        }
        val url = server.start()
        if (url == null) {
            toast("Connect the TV to your home network first")
            return
        }
        backupServer = server
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(this).apply {
            text = (if (backupText != null) "Scan with your phone and tap “Download backup file”." else "Scan with your phone and choose your .sbk backup file.") +
                "\nThe phone must be on the same Wi-Fi as the TV.\n\n$url"
            setTextColor(0xFFF5F7FF.toInt())
        })
        box.addView(android.widget.ImageView(this).apply {
            val size = (260 * resources.displayMetrics.density).toInt()
            setImageBitmap(Qr.bitmap(url, size))
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply { topMargin = pad / 2 }
        })
        AlertDialog.Builder(this)
            .setTitle(if (backupText != null) "Back up to my phone" else "Restore from my phone")
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener { if (backupServer === server) { server.stop(); backupServer = null } }
            .show()
    }

    private fun askRestorePassword(text: String) {
        Ui.input(this, "Backup password", "", passwordType) { pw ->
            toast("Restoring…")
            lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.streambox.tv.data.Backup.restore(text, pw)
                }
                if (!ok) {
                    AlertDialog.Builder(this@SettingsActivity).setTitle("Couldn't restore")
                        .setMessage("Wrong password, or the file is not a StreamBox backup.")
                        .setPositiveButton("OK", null).show()
                    return@launch
                }
                backupServer?.stop()
                Repo.close()
                toast("Backup restored")
                // Start fresh so every screen uses the restored settings.
                startActivity(android.content.Intent(this@SettingsActivity, SplashActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    override fun onDestroy() {
        backupServer?.stop()
        super.onDestroy()
    }

    private fun viewersMenu() {
        if (!Repo.loaded) {
            toast("Open a profile first")
            return
        }
        val pid = Repo.profileId
        val list = Prefs.viewers(pid)
        val names = listOf("👤  ${Prefs.mainViewerName}  (main)") + list.map { (if (it.kids) "🧒  " else "👤  ") + it.name } + listOf("+  Add viewer", "+  Add Kids viewer")
        AlertDialog.Builder(this)
            .setTitle("Viewers")
            .setItems(names.toTypedArray()) { _, w ->
                when {
                    w == 0 -> Ui.input(this, "Your name", Prefs.mainViewerName) { Prefs.mainViewerName = it; refresh() }
                    w <= list.size -> editViewer(list[w - 1])
                    else -> {
                        val kids = w == list.size + 2
                        Ui.input(this, if (kids) "Kids viewer name" else "Viewer name", "") { name ->
                            if (name.isBlank()) return@input
                            Prefs.saveViewers(pid, list + com.streambox.tv.data.Viewer("v${System.currentTimeMillis()}", name, kids))
                            if (kids && !Prefs.parentalEnabled) toast("Tip: set a parental PIN so kids can't leave their profile")
                            refresh()
                        }
                    }
                }
            }
            .show()
    }

    private fun editViewer(v: com.streambox.tv.data.Viewer) {
        val pid = Repo.profileId
        val opts = arrayOf("Rename", if (v.kids) "Make a normal viewer" else "Make a Kids viewer", "Delete")
        AlertDialog.Builder(this)
            .setTitle(v.name)
            .setItems(opts) { _, w ->
                val list = Prefs.viewers(pid)
                when (w) {
                    0 -> Ui.input(this, "Viewer name", v.name) { name ->
                        Prefs.saveViewers(pid, list.map { if (it.id == v.id) it.copy(name = name.ifBlank { it.name }) else it })
                        refresh()
                    }
                    1 -> {
                        Prefs.saveViewers(pid, list.map { if (it.id == v.id) it.copy(kids = !it.kids) else it })
                        Repo.applyCustomisations()
                        refresh()
                    }
                    else -> {
                        Prefs.saveViewers(pid, list.filterNot { it.id == v.id })
                        if (Prefs.currentViewer(pid) == v.id) Repo.selectViewer("")
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun channelManager() {
        if (!Repo.loaded) {
            toast("Open a profile first")
            return
        }
        val pid = Repo.profileId
        val sorts = arrayOf("Provider order", "A–Z", "Channel number")
        val hiddenCh = Prefs.hiddenChannels(pid).mapNotNull { Repo.channel(it) }
        val hiddenCats = Prefs.hiddenCategories(pid).mapNotNull { key ->
            val section = key.substringBefore(':')
            val id = key.substringAfter(':')
            Repo.allCategories(section).firstOrNull { it.id == id }?.let { key to it }
        }
        val options = arrayOf(
            "Sort channels: ${sorts[Prefs.channelSort.coerceIn(0, 2)]}",
            "Hidden channels (${hiddenCh.size})",
            "Hidden categories (${hiddenCats.size})",
            "Undo all renames, hiding and ordering"
        )
        AlertDialog.Builder(this)
            .setTitle("Channel manager")
            .setItems(options) { _, w ->
                when (w) {
                    0 -> choose("Sort channels", sorts, Prefs.channelSort) { Prefs.channelSort = it }
                    1 -> if (hiddenCh.isEmpty()) toast("No hidden channels. Hide one by holding OK on it in Live TV") else
                        AlertDialog.Builder(this)
                            .setTitle("Show a hidden channel again")
                            .setItems(hiddenCh.map { it.name }.toTypedArray()) { _, i ->
                                Prefs.setChannelHidden(pid, hiddenCh[i].id, false)
                                toast("${hiddenCh[i].name} is visible again")
                                refresh()
                            }
                            .show()
                    2 -> if (hiddenCats.isEmpty()) toast("No hidden categories. Hide one by holding OK on it") else
                        AlertDialog.Builder(this)
                            .setTitle("Show a hidden category again")
                            .setItems(hiddenCats.map { it.second.name }.toTypedArray()) { _, i ->
                                Prefs.setCategoryHidden(pid, hiddenCats[i].first, false)
                                Repo.applyCustomisations()
                                toast("${hiddenCats[i].second.name} is visible again")
                                refresh()
                            }
                            .show()
                    else -> AlertDialog.Builder(this)
                        .setTitle("Undo all channel changes?")
                        .setMessage("Channel names, hidden channels and categories, and category order go back to the provider's.")
                        .setPositiveButton("Undo all") { _, _ ->
                            Prefs.clearChannelManager(pid)
                            Repo.applyCustomisations()
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun parental() {
        if (!Prefs.parentalEnabled) {
            Ui.newPin(this) { refresh() }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Parental control")
            .setItems(arrayOf("Change PIN", "Turn off parental control")) { _, which ->
                Ui.askPin(this) {
                    if (which == 0) {
                        Ui.newPin(this) { refresh() }
                    } else {
                        Prefs.pin = ""
                        toast("Parental control is off")
                        refresh()
                    }
                }
            }
            .show()
    }
}
