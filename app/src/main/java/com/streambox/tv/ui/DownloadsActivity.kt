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
import com.streambox.tv.data.Downloads
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Repo
import java.io.File

/** Movies and episodes saved for offline viewing. */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: SimpleAdapter<Downloads.Item>
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.title).text = "Downloads"
        status = findViewById(R.id.status)
        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, d, _ ->
                val icon = when (d.status) {
                    Downloads.DONE -> "▶"
                    Downloads.RUNNING -> "⬇"
                    Downloads.PAUSED -> "⏸"
                    Downloads.FAILED -> "⚠"
                    else -> "🕒"
                }
                v.findViewById<TextView>(R.id.line1).text = "$icon  ${d.title}"
                val have = Downloads.bytes(d)
                val size = if (d.total > 0) "${gb(have)} / ${gb(d.total)}" else gb(have)
                val pct = if (d.total > 0) " ${(have * 100 / d.total).coerceIn(0, 100)}%  •" else ""
                val state = when (d.status) {
                    Downloads.DONE -> "Ready to watch offline   •   ${gb(d.total.coerceAtLeast(have))}"
                    Downloads.RUNNING -> "Downloading$pct  $size"
                    Downloads.PAUSED -> "Paused  •  $size"
                    Downloads.FAILED -> "Failed" + if (d.note.isNotBlank()) "  •  ${d.note}" else ""
                    else -> "Waiting in the queue"
                }
                v.findViewById<TextView>(R.id.line2).text = state +
                    if (d.status == Downloads.RUNNING && d.note.isNotBlank()) "  •  ${d.note}" else ""
            },
            onClick = { d, _ -> open(d) }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
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

    private fun gb(b: Long): String =
        if (b >= 1_073_741_824L) String.format(java.util.Locale.US, "%.2f GB", b / 1_073_741_824.0)
        else String.format(java.util.Locale.US, "%.0f MB", b / 1_048_576.0)

    private fun reload() {
        val all = Downloads.all().filter { it.profileId == Repo.profileId || !Repo.loaded }.sortedByDescending { it.addedAt }
        adapter.submit(all, adapter.selected.coerceAtMost(all.size - 1))
        status.text = if (all.isEmpty())
            "Nothing downloaded yet. Open a movie or series and choose ⬇ Download."
        else "Saved in ${Downloads.dir(this).absolutePath}   •   one download at a time"
    }

    private fun open(d: Downloads.Item) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val file = File(d.file)
        if (d.status == Downloads.DONE && file.exists()) actions.add("▶  Play" to {
            Ui.play(this, listOf(PlayItem(d.key, d.title, Uri.fromFile(file).toString(), false)), 0)
        })
        when (d.status) {
            Downloads.RUNNING, Downloads.QUEUED -> actions.add("⏸  Pause" to { Downloads.pause(this, d.id); reload() })
            Downloads.PAUSED, Downloads.FAILED -> actions.add("⬇  Resume download" to { Downloads.resume(this, d.id); reload() })
        }
        actions.add("🗑  Delete" to { Downloads.delete(this, d.id); reload() })
        AlertDialog.Builder(this)
            .setTitle(d.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, w -> actions[w].second() }
            .show()
    }
}
