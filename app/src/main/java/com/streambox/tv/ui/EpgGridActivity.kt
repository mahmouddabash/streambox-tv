package com.streambox.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Category
import com.streambox.tv.data.Channel
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Programme
import com.streambox.tv.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Multi-channel TV guide: channels down the left, their programmes along each row.
 * Each row loads its own guide when it scrolls into view, so even a huge channel list stays responsive.
 * Moving focus onto a programme shows its full details in the pane at the top.
 */
class EpgGridActivity : AppCompatActivity() {

    private lateinit var detailTitle: TextView
    private lateinit var detailTime: TextView
    private lateinit var detailDesc: TextView
    private lateinit var status: TextView
    private lateinit var rowAdapter: RowAdapter
    private lateinit var catAdapter: SimpleAdapter<Category>

    private var density = 2f
    private var currentCat = Repo.CAT_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.loaded) {
            finish()
            return
        }
        setContentView(R.layout.activity_epg_grid)
        density = resources.displayMetrics.density
        detailTitle = findViewById(R.id.detailTitle)
        detailTime = findViewById(R.id.detailTime)
        detailDesc = findViewById(R.id.detailDesc)
        status = findViewById(R.id.status)

        catAdapter = SimpleAdapter(
            R.layout.item_chip,
            bind = { v, c, _ -> (v as TextView).text = c.name },
            onClick = { c, pos ->
                if (Repo.isLocked(Repo.SECTION_LIVE, c)) {
                    Ui.unlock(this, Repo.SECTION_LIVE, c) {
                        catAdapter.select(pos); showCategory(c.id)
                    }
                } else {
                    catAdapter.select(pos); showCategory(c.id)
                }
            }
        )
        val catView = findViewById<RecyclerView>(R.id.categories)
        catView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        catView.adapter = catAdapter

        rowAdapter = RowAdapter()
        val rows = findViewById<RecyclerView>(R.id.rows)
        rows.layoutManager = LinearLayoutManager(this)
        rows.setItemViewCacheSize(12)
        rows.adapter = rowAdapter

        catAdapter.submit(categories(), 0)
        showCategory(Repo.CAT_ALL)
    }

    private fun categories(): List<Category> {
        val list = ArrayList<Category>()
        list.add(Category(Repo.CAT_ALL, "All"))
        list.add(Category(Repo.CAT_FAV, "★ Favourites"))
        list.addAll(Repo.liveCats)
        return list
    }

    private fun showCategory(id: String) {
        currentCat = id
        val channels = Repo.channelsIn(id)
        rowAdapter.submit(channels)
        status.text = when {
            channels.isEmpty() -> "No channels in this category"
            else -> "${channels.size} channels · ▲▼ channels, ◀▶ programmes, OK to watch"
        }
    }

    private fun onProgrammeFocused(c: Channel, p: Programme) {
        detailTitle.text = "${c.name} — ${p.title}"
        detailTime.text = "${Ui.day(p.startMs)}  ${Ui.time(p.startMs)} – ${Ui.time(p.endMs)}"
        detailDesc.text = p.desc
    }

    private fun onProgrammeClicked(c: Channel, p: Programme) {
        val now = System.currentTimeMillis()
        when {
            p.startMs <= now && p.endMs > now ->
                Ui.play(this, listOf(PlayItem("", c.name, Repo.urlFor(c), true, c.id)), 0)
            p.startMs > now -> toast("Starts at ${Ui.time(p.startMs)}")
            canCatchUp(c, p) -> Ui.play(
                this,
                listOf(PlayItem("", "${c.name} – ${p.title}", Repo.catchupUrl(c, p), false)),
                0,
                fromStart = true
            )
            else -> toast("Not available to watch again")
        }
    }

    private fun playLive(c: Channel) =
        Ui.play(this, listOf(PlayItem("", c.name, Repo.urlFor(c), true, c.id)), 0)

    private fun canCatchUp(c: Channel, p: Programme): Boolean {
        if (Repo.profile?.isXtream != true) return false
        if (!c.hasArchive && !p.hasArchive) return false
        if (p.hasArchive) return true
        val days = if (c.archiveDays > 0) c.archiveDays else 1
        return p.startMs > System.currentTimeMillis() - days * 86_400_000L
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    // ------------------------------------------------------------------ Rows

    private inner class RowAdapter : RecyclerView.Adapter<RowVH>() {
        private var channels: List<Channel> = emptyList()

        fun submit(list: List<Channel>) {
            channels = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_grid_row, parent, false)
            return RowVH(v)
        }

        override fun onBindViewHolder(holder: RowVH, position: Int) = holder.bind(channels[position])

        override fun onViewRecycled(holder: RowVH) = holder.recycle()
    }

    private inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val num: TextView = v.findViewById(R.id.num)
        private val name: TextView = v.findViewById(R.id.name)
        private val logo: ImageView = v.findViewById(R.id.logo)
        private val channelBox: View = v.findViewById(R.id.channel)
        private val list: RecyclerView = v.findViewById(R.id.programmes)
        private val cellAdapter = CellAdapter()
        private var job: Job? = null

        init {
            list.layoutManager = LinearLayoutManager(list.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = cellAdapter
            list.isFocusable = false
        }

        fun bind(c: Channel) {
            num.text = c.num.toString()
            name.text = c.name
            logo.loadUrl(c.logo)
            channelBox.setOnClickListener { playLive(c) }
            cellAdapter.set(c, emptyList())

            job?.cancel()
            val cached = guideCache[c.id]
            if (cached != null) {
                cellAdapter.set(c, cached)
            } else {
                job = lifecycleScope.launch {
                    val list = Repo.upcoming(this@EpgGridActivity, c)
                    guideCache[c.id] = list
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) cellAdapter.set(c, list)
                }
            }
        }

        fun recycle() {
            job?.cancel()
            job = null
        }
    }

    // ------------------------------------------------------------------ Programme cells

    private inner class CellAdapter : RecyclerView.Adapter<CellVH>() {
        private var channel: Channel? = null
        private var items: List<Programme> = emptyList()

        fun set(c: Channel, list: List<Programme>) {
            channel = c
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = if (items.isEmpty()) 1 else items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_grid_cell, parent, false)
            return CellVH(v)
        }

        override fun onBindViewHolder(holder: CellVH, position: Int) {
            val c = channel ?: return
            if (items.isEmpty()) holder.bindEmpty() else holder.bind(c, items[position])
        }
    }

    private inner class CellVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.cellTitle)
        private val time: TextView = v.findViewById(R.id.cellTime)

        fun bindEmpty() {
            setWidth(dp(260))
            itemView.setBackgroundResource(R.drawable.bg_item)
            title.text = "No guide"
            time.text = ""
            itemView.isFocusable = false
            itemView.setOnClickListener(null)
            itemView.setOnFocusChangeListener(null)
        }

        fun bind(c: Channel, p: Programme) {
            val now = System.currentTimeMillis()
            val isNow = p.startMs <= now && p.endMs > now
            val minutes = ((p.endMs - p.startMs) / 60000L).toInt().coerceIn(15, 240)
            setWidth(dp((minutes * 2.6f).toInt().coerceIn(130, 560)))
            itemView.setBackgroundResource(if (isNow) R.drawable.bg_cell_now else R.drawable.bg_item)
            title.text = (if (isNow) "● " else "") + p.title
            time.text = "${Ui.time(p.startMs)} – ${Ui.time(p.endMs)}"
            itemView.isFocusable = true
            itemView.setOnClickListener { onProgrammeClicked(c, p) }
            itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onProgrammeFocused(c, p) }
        }

        private fun setWidth(px: Int) {
            val lp = itemView.layoutParams
            lp.width = px
            itemView.layoutParams = lp
        }
    }

    /** Guide per channel, kept for this screen's lifetime so re-scrolling is instant. */
    private val guideCache = HashMap<String, List<Programme>>()
}
