package com.streambox.tv.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Recorder
import java.io.File

/** Scheduled, running and finished recordings. */
class RecordingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private lateinit var adapter: SimpleAdapter<Recorder.Recording>
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.title).text = "Recordings"
        status = findViewById(R.id.status)
        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, r, _ ->
                val icon = when (r.status) {
                    Recorder.RECORDING -> "⏺"
                    Recorder.SCHEDULED -> "🕒"
                    Recorder.DONE -> "▶"
                    else -> "⚠"
                }
                v.findViewById<TextView>(R.id.line1).text = "$icon  ${r.title}   •   ${r.channelName}"
                val size = File(r.file).length()
                val sizeText = if (size > 0) "   •   ${"%.1f".format(size / 1_048_576.0)} MB" else ""
                val state = when (r.status) {
                    Recorder.RECORDING -> "Recording until ${Ui.time(r.endMs)}   •   " + Tr.s("press OK to watch while it records")
                    Recorder.SCHEDULED -> "Scheduled" + when (r.repeat) {
                        "daily" -> "   •   🔁 " + Tr.s("every day")
                        "weekly" -> "   •   🔁 " + Tr.s("every week")
                        else -> ""
                    }
                    Recorder.DONE -> "Recorded"
                    else -> "Failed"
                }
                v.findViewById<TextView>(R.id.line2).text =
                    "${Ui.day(r.startMs)}  ${Ui.time(r.startMs)} – ${Ui.time(r.endMs)}   •   $state$sizeText" +
                        if (r.note.isNotBlank()) "   •   ${r.note}" else ""
            },
            onClick = { r, _ -> open(r) }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        reload()
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun reload() {
        val all = Recorder.all()
        adapter.submit(all)
        status.text = if (all.isEmpty())
            "No recordings yet. Record from the player menu (◀▶ on live TV) or from a channel's guide."
        else "Saved to: ${Recorder.storageDir(this).absolutePath}"
    }

    private fun open(r: Recorder.Recording) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val file = File(r.file)
        if (file.length() > 0) actions.add("▶  Play" to {
            Ui.play(this, listOf(PlayItem("rec_${r.id}", r.title, Uri.fromFile(file).toString(), false)), 0)
        })
        if (r.status == Recorder.RECORDING) actions.add("⏹  Stop recording" to {
            com.streambox.tv.data.RecordingService.stop(this, r.id)
            Recorder.update(r.id) { it.copy(status = if (file.length() > 0) Recorder.DONE else Recorder.FAILED, note = "Stopped") }
            reload()
        })
        actions.add((if (r.status == Recorder.SCHEDULED) "✕  Cancel" else "🗑  Delete") to {
            Recorder.delete(this, r)
            reload()
        })
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, w -> actions[w].second() }
            .show()
    }
}
