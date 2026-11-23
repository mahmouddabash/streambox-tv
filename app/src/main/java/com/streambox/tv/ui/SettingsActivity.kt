package com.streambox.tv.ui

import android.os.Bundle
import android.text.InputType
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
        rows.add(Row("Check for updates", "Installed: build ${com.streambox.tv.data.Updater.currentBuild}. Checked automatically once a day") {
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
