package com.streambox.tv.ui

import android.os.Bundle
import android.text.InputType
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        rows.add(Row("Player", if (Prefs.externalPlayer) "External player app (VLC, MX Player…)" else "Built-in player") {
            Prefs.externalPlayer = !Prefs.externalPlayer
            refresh()
        })
        rows.add(Row("Channel preview in Live TV", if (Prefs.previewEnabled) "On: first OK previews, second OK goes full screen" else "Off: OK opens full screen") {
            Prefs.previewEnabled = !Prefs.previewEnabled
            refresh()
        })
        rows.add(Row("Open last profile on startup", if (Prefs.openLastProfile) "On" else "Off: always show the profile list") {
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
