package com.streambox.tv.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Channel
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Programme
import com.streambox.tv.data.Recorder
import com.streambox.tv.data.Reminders
import com.streambox.tv.data.Repo
import kotlinx.coroutines.launch

/** Full guide of one channel: watch what's on now, set reminders or record upcoming programmes. */
class EpgActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private lateinit var channel: Channel
    private lateinit var adapter: SimpleAdapter<Programme>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Repo.channel(intent.getStringExtra(EXTRA_CHANNEL) ?: "")
        if (c == null) {
            finish()
            return
        }
        channel = c
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.title).text = c.name
        val status = findViewById<TextView>(R.id.status)
        status.text = "Loading guide…"

        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, p, _ ->
                val now = System.currentTimeMillis()
                var tag = when {
                    p.startMs <= now && p.endMs > now -> "   ● NOW"
                    else -> ""
                }
                if (p.startMs > now && Reminders.has(channel.id, p.startMs)) tag += "   ⏰"
                if (isRecording(p)) tag += "   ⏺"
                v.findViewById<TextView>(R.id.line1).text =
                    "${Ui.day(p.startMs)}  ${Ui.time(p.startMs)} – ${Ui.time(p.endMs)}   ${p.title}$tag"
                v.findViewById<TextView>(R.id.line2).text = p.desc
            },
            onClick = { p, _ -> onProgramme(p) }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        lifecycleScope.launch {
            val guide = Repo.fullGuide(this@EpgActivity, c)
            val now = System.currentTimeMillis()
            val current = guide.indexOfFirst { it.endMs > now }
            adapter.submit(guide, current)
            status.text = when {
                guide.isEmpty() -> "No programme information for this channel"
                else -> "Choose a programme to watch, record or set a reminder"
            }
            if (current >= 0) {
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(current, 120)
                list.post { list.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
            }
        }
    }

    private fun onProgramme(p: Programme) {
        val now = System.currentTimeMillis()
        when {
            p.startMs > now -> futureMenu(p)
            p.endMs > now -> nowMenu(p)
            else -> toast("This programme has already finished")
        }
    }

    private fun isRecording(p: Programme) = Recorder.all().any {
        it.channelId == channel.id && it.startMs <= p.startMs + 60_000 && it.endMs >= p.endMs - 60_000 && it.status != Recorder.FAILED
    }

    private fun refreshList() = adapter.notifyItemRangeChanged(0, adapter.itemCount)

    private fun futureMenu(p: Programme) {
        val reminded = Reminders.has(channel.id, p.startMs)
        val options = arrayOf(if (reminded) "⏰  Cancel reminder" else "⏰  Remind me when it starts", "⏺  Record this programme")
        AlertDialog.Builder(this)
            .setTitle("${Ui.time(p.startMs)}  ${p.title}")
            .setItems(options) { _, w ->
                if (w == 0) {
                    val on = Reminders.toggle(this, channel, p)
                    toast(if (on) "I'll remind you at ${Ui.time(p.startMs)}" else "Reminder cancelled")
                } else {
                    Recorder.schedule(this, channel, p.title, p.startMs - 60_000L, p.endMs + 2 * 60_000L)
                    toast("Recording scheduled: ${p.title}")
                }
                refreshList()
            }
            .show()
    }

    private fun nowMenu(p: Programme) {
        val options = arrayOf("▶  Watch live", "⏺  Record until ${Ui.time(p.endMs)}")
        AlertDialog.Builder(this)
            .setTitle(p.title)
            .setItems(options) { _, w ->
                if (w == 0) Ui.play(this, listOf(PlayItem("", channel.name, Repo.urlFor(channel), true, channel.id)), 0)
                else {
                    Recorder.schedule(this, channel, p.title, System.currentTimeMillis(), p.endMs + 2 * 60_000L)
                    toast("Recording started")
                    refreshList()
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
