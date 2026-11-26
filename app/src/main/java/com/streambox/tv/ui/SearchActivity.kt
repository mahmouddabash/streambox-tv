package com.streambox.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Channel
import com.streambox.tv.data.Movie
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Repo
import com.streambox.tv.data.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** One search box for channels, movies and series, with voice input from the remote's mic. */
class SearchActivity : AppCompatActivity() {

    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var rowsView: RecyclerView
    private var job: Job? = null
    private var rows: List<Pair<String, List<Any>>> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            query.setText(text)
            query.setSelection(text.length)
            search(text, immediate = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.loaded) {
            finish()
            return
        }
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        rowsView = findViewById(R.id.rows)
        rowsView.layoutManager = LinearLayoutManager(this)
        rowsView.itemAnimator = null
        rowsView.adapter = RowsAdapter()

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = search(s?.toString() ?: "", immediate = false)
        })
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                focusFirstResult()
                true
            } else false
        }
        findViewById<Button>(R.id.voice).setOnClickListener { startVoice() }

        status.text = "Type, or press the mic and speak"
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) startVoice() else query.requestFocus()
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, movie or series")
        try {
            voice.launch(i)
        } catch (_: ActivityNotFoundException) {
            toast("Voice search isn't available on this TV")
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun focusFirstResult() {
        rowsView.post {
            val h = rowsView.findViewHolderForAdapterPosition(0) as? RowHolder ?: return@post
            h.list.post { h.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The remote's search / voice key starts voice input here too.
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)
        ) {
            startVoice()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun search(text: String, immediate: Boolean) {
        job?.cancel()
        val q = norm(text.trim())
        if (q.length < 2) {
            rows = emptyList()
            rowsView.adapter?.notifyDataSetChanged()
            status.text = if (q.isEmpty()) "Type, or press the mic and speak" else "Keep typing…"
            return
        }
        job = lifecycleScope.launch {
            if (!immediate) delay(280)
            val words = q.split(' ').filter { it.isNotEmpty() }
            fun match(name: String): Boolean {
                val n = norm(name)
                return words.all { n.contains(it) }
            }
            val result = withContext(Dispatchers.Default) {
                val ch = Repo.channelsIn(Repo.CAT_ALL).filter { match(it.name) }.take(60)
                val mv = Repo.moviesIn(Repo.CAT_ALL).filter { match(it.name) }.take(60)
                val sr = Repo.seriesIn(Repo.CAT_ALL).filter { match(it.name) }.take(60)
                Triple(ch, mv, sr)
            }
            val out = ArrayList<Pair<String, List<Any>>>()
            if (result.first.isNotEmpty()) out.add("Channels (${result.first.size})" to result.first)
            if (result.second.isNotEmpty()) out.add("Movies (${result.second.size})" to result.second)
            if (result.third.isNotEmpty()) out.add("Series (${result.third.size})" to result.third)
            // Actors: series whose cast (from the provider) includes the name.
            val byCast = withContext(Dispatchers.Default) {
                Repo.seriesIn(Repo.CAT_ALL).filter { s -> s.cast.isNotBlank() && match(s.cast) && s !in result.third }.take(60)
            }
            if (byCast.isNotEmpty()) out.add("🎭 Series starring “${text.trim()}” (${byCast.size})" to byCast)
            rows = out
            rowsView.adapter?.notifyDataSetChanged()
            status.text = if (out.isEmpty()) "Nothing found for “${text.trim()}”" else "Press ▼ to browse the results"
            // Films and series by actor via TMDB (when a TMDB key is set in Settings).
            if (q.length >= 3 && com.streambox.tv.data.Tmdb.enabled) {
                val credits = com.streambox.tv.data.Tmdb.personCredits(text.trim()) ?: return@launch
                val found = withContext(Dispatchers.Default) {
                    val wanted = credits.titles.map { norm(it.first) to it.second }.filter { it.first.length >= 2 }
                    val movieIdx = Repo.moviesIn(Repo.CAT_ALL).groupBy { norm(com.streambox.tv.data.Tmdb.cleanTitle(it.name).first) }
                    val seriesIdx = Repo.seriesIn(Repo.CAT_ALL).groupBy { norm(com.streambox.tv.data.Tmdb.cleanTitle(it.name).first) }
                    val list = LinkedHashSet<Any>()
                    for ((t, isMovie) in wanted) {
                        (if (isMovie) movieIdx[t] else seriesIdx[t])?.let { list.addAll(it.take(2)) }
                        if (list.size >= 60) break
                    }
                    list.toList()
                }
                if (found.isNotEmpty()) {
                    rows = rows + ("🎭 ${credits.name} (${found.size})" to found)
                    rowsView.adapter?.notifyDataSetChanged()
                    if (out.isEmpty()) status.text = "Press ▼ to browse the results"
                }
            }
        }
    }

    /** Lower-case, accents removed, Arabic letter variants unified, punctuation to spaces. */
    private fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        return t.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    // ------------------------------------------------------------------ Adapters

    private class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val h = RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false))
            h.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            h.list.setRecycledViewPool(pool)
            return h
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.title.text = rows[position].first
            holder.list.adapter = CardsAdapter(rows[position].second)
        }
    }

    private class CardHolder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val fallback: TextView = v.findViewById(R.id.fallbackTitle)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    private inner class CardsAdapter(val items: List<Any>) : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Channel) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val layout = if (viewType == 1) R.layout.item_home_channel else R.layout.item_home_poster
            return CardHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(h: CardHolder, position: Int) {
            when (val it = items[position]) {
                is Channel -> {
                    bindImage(h, it.logo, it.name, poster = false)
                    h.itemView.setOnClickListener { _ ->
                        val list = items.filterIsInstance<Channel>()
                        Ui.play(this@SearchActivity, list.map { c -> PlayItem("", c.name, Repo.urlFor(c), true, c.id) }, list.indexOf(it))
                    }
                }
                is Movie -> {
                    bindImage(h, it.poster, it.name, poster = true)
                    h.itemView.setOnClickListener { _ ->
                        startActivity(Intent(this@SearchActivity, MovieDetailActivity::class.java).putExtra(MovieDetailActivity.EXTRA_ID, it.id))
                    }
                }
                is Series -> {
                    bindImage(h, it.poster, it.name, poster = true)
                    h.itemView.setOnClickListener { _ ->
                        startActivity(Intent(this@SearchActivity, SeriesDetailActivity::class.java).putExtra(SeriesDetailActivity.EXTRA_ID, it.id))
                    }
                }
            }
        }

        private fun bindImage(h: CardHolder, url: String, name: String, poster: Boolean) {
            h.fallback.text = name
            if (url.isBlank()) {
                h.image.setImageDrawable(null)
                h.fallback.visibility = View.VISIBLE
                if (poster) h.image.setBackgroundResource(R.drawable.placeholder)
            } else {
                if (poster) h.image.setBackgroundResource(R.drawable.bg_round_clip)
                h.image.loadUrl(url)
                h.fallback.visibility = if (poster) View.GONE else View.VISIBLE
            }
            if (poster) h.badge.visibility = View.GONE
        }
    }

    companion object {
        const val EXTRA_VOICE = "voice"
    }
}
