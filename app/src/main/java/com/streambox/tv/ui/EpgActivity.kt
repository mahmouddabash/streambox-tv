package com.streambox.tv.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Channel
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Programme
import com.streambox.tv.data.Repo
import kotlinx.coroutines.launch

/** Full guide of one channel. Past programmes play from the provider's archive (catch-up). */
class EpgActivity : AppCompatActivity() {

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
                val tag = when {
                    p.startMs <= now && p.endMs > now -> "   ● NOW"
                    p.endMs <= now && canCatchUp(p) -> "   ⟲ catch-up"
                    else -> ""
                }
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
                c.hasArchive -> "Select a past programme marked ⟲ to watch it again"
                else -> "This channel has no catch-up archive"
            }
            if (current >= 0) {
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(current, 120)
                list.post { list.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
            }
        }
    }

    private fun canCatchUp(p: Programme): Boolean {
        if (Repo.profile?.isXtream != true) return false
        if (!channel.hasArchive && !p.hasArchive) return false
        if (p.hasArchive) return true
        val days = if (channel.archiveDays > 0) channel.archiveDays else 1
        return p.startMs > System.currentTimeMillis() - days * 86_400_000L
    }

    private fun onProgramme(p: Programme) {
        val now = System.currentTimeMillis()
        when {
            p.startMs > now -> toast("This programme has not started yet")
            p.endMs > now -> Ui.play(this, listOf(PlayItem("", channel.name, Repo.urlFor(channel), true, channel.id)), 0)
            canCatchUp(p) -> Ui.play(
                this,
                listOf(PlayItem("", "${channel.name} – ${p.title}", Repo.catchupUrl(channel, p), false)),
                0,
                fromStart = true
            )
            else -> toast("Not available in the catch-up archive")
        }
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
