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
        // "Play MBC 1" / "شغل ام بي سي": start the channel straight away.
        if (!text.isNullOrBlank() && playByVoice(text)) return@registerForActivityResult
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
        val preset = intent.getStringExtra(EXTRA_QUERY)
        when {
            !preset.isNullOrBlank() -> {
                // From the phone remote: "play …" starts a channel, anything else is searched.
                if (!playByVoice(preset)) {
                    query.setText(preset)
                    query.setSelection(preset.length)
                    search(preset, immediate = true)
                }
            }
            intent.getBooleanExtra(EXTRA_VOICE, false) -> startVoice()
            else -> query.requestFocus()
        }
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
            // "أفلام أكشن جديدة فوق 7" / "comedy series 2023": understood as filters, all on the device.
            val smart = withContext(Dispatchers.Default) { smartSearch(text) }
            val result = withContext(Dispatchers.Default) {
                val ch = Repo.channelsIn(Repo.CAT_ALL).filter { match(it.name) }.take(60)
                val mv = Repo.collapseMovies(Repo.moviesIn(Repo.CAT_ALL).filter { match(it.name) }).take(60)
                val sr = Repo.seriesIn(Repo.CAT_ALL).filter { match(it.name) }.take(60)
                Triple(ch, mv, sr)
            }
            val out = ArrayList<Pair<String, List<Any>>>()
            if (smart != null && smart.second.isNotEmpty()) out.add("✨ ${smart.first} (${smart.second.size})" to smart.second)
            if (result.first.isNotEmpty()) out.add("Channels (${result.first.size})" to result.first)
            if (result.second.isNotEmpty()) out.add("Movies (${result.second.size})" to result.second)
            if (result.third.isNotEmpty()) out.add("Series (${result.third.size})" to result.third)
            // Actors: series whose cast (from the provider) includes the name.
            val byCast = withContext(Dispatchers.Default) {
                Repo.seriesIn(Repo.CAT_ALL).filter { s -> s.cast.isNotBlank() && match(s.cast) && s !in result.third }.take(60)
            }
            if (byCast.isNotEmpty()) out.add("🎭 Series starring “${text.trim()}” (${byCast.size})" to byCast)
            // Typed in one script, title in the other ("Saqr" ⇄ "صقر"): match by sound.
            val key = skeleton(text)
            val latin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
            val arabic = text.any { it in '\u0600'..'\u06FF' }
            if (key.length >= 3 && latin != arabic) {
                val sounds = withContext(Dispatchers.Default) {
                    fun other(n: String) = if (latin) n.any { it in '\u0600'..'\u06FF' } else n.any { it in 'a'..'z' || it in 'A'..'Z' }
                    fun hit(n: String) = other(n) && skeleton(n).contains(key)
                    val seen = out.flatMap { it.second }.toSet()
                    (Repo.channelsIn(Repo.CAT_ALL).filter { hit(it.name) } + Repo.moviesIn(Repo.CAT_ALL).filter { hit(it.name) } +
                        Repo.seriesIn(Repo.CAT_ALL).filter { hit(it.name) }).filter { it !in seen }.take(60)
                }
                if (sounds.isNotEmpty()) out.add("🔤 Sounds like “${text.trim()}” (${sounds.size})" to sounds)
            }
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

    private fun playByVoice(said: String): Boolean {
        val m = Regex("^\\s*(play|watch|open|put on|شغل|شغّل|افتح|اعرض|حط)\\s+(.+)$", RegexOption.IGNORE_CASE).find(said) ?: return false
        val want = norm(m.groupValues[2].removePrefix("channel ").removePrefix("قناة "))
        if (want.isEmpty()) return false
        val all = Repo.channelsIn(Repo.CAT_ALL)
        val c = all.firstOrNull { norm(it.name) == want }
            ?: all.firstOrNull { norm(it.name).replace(" ", "") == want.replace(" ", "") }
            ?: all.filter { norm(it.name).contains(want) }.minByOrNull { it.name.length }
            ?: all.filter { skeleton(it.name).let { s -> s.length >= 2 && s == skeleton(want) } }.minByOrNull { it.name.length }
            ?: return false
        Ui.play(this, listOf(PlayItem("", c.name, Repo.urlFor(c), true, c.id)), 0)
        finish()
        return true
    }

    private val genreWords = mapOf(
        "action" to listOf("action", "أكشن", "اكشن", "حركة", "حركه"),
        "comedy" to listOf("comedy", "comedies", "funny", "كوميدي", "كوميديا", "كوميدية", "مضحك"),
        "drama" to listOf("drama", "دراما", "درامي"),
        "horror" to listOf("horror", "scary", "رعب", "مرعب"),
        "romance" to listOf("romance", "romantic", "رومانسي", "رومانسية", "رومانسيه", "حب"),
        "thriller" to listOf("thriller", "suspense", "إثارة", "اثارة", "اثاره", "تشويق"),
        "crime" to listOf("crime", "جريمة", "جريمه", "بوليسي"),
        "animation" to listOf("animation", "animated", "cartoon", "anime", "كرتون", "انمي", "أنمي", "رسوم", "انيميشن"),
        "documentary" to listOf("documentary", "وثائقي", "وثائقية"),
        "sci-fi" to listOf("sci-fi", "scifi", "science fiction", "خيال علمي", "خيال"),
        "adventure" to listOf("adventure", "مغامرة", "مغامرات"),
        "family" to listOf("family", "عائلي", "عائلية"),
        "war" to listOf("war", "حرب", "حربي"),
        "history" to listOf("history", "historical", "تاريخي", "تاريخية"),
        "kids" to listOf("kids", "children", "أطفال", "اطفال"),
        "turkish" to listOf("turkish", "تركي", "تركية"),
        "korean" to listOf("korean", "كوري", "كورية"),
        "indian" to listOf("indian", "bollywood", "هندي", "هندية"),
        "egyptian" to listOf("egyptian", "مصري", "مصرية"),
        "arabic" to listOf("arabic", "arab", "عربي", "عربية")
    )

    /**
     * Understands simple requests like "new action films above 7" / "أفلام أكشن جديدة فوق 7" /
     * "comedy series 2023": type, genre (from category names and series genres), year, minimum
     * rating and "new". Returns a label and the matches, or null when the text isn't such a request.
     */
    private fun smartSearch(raw: String): Pair<String, List<Any>>? {
        val t = " " + raw.lowercase().replace('أ', 'ا').replace('إ', 'ا') + " "
        val wantMovies = Regex("\\b(film|films|movie|movies)\\b|افلام|فيلم|افلم").containsMatchIn(t)
        val wantSeries = Regex("\\b(series|show|shows|tv shows)\\b|مسلسل|مسلسلات").containsMatchIn(t)
        // Latin words must match whole words ("war" not in "warrior"); Arabic may carry "ال" etc.
        fun has(w: String): Boolean {
            val x = w.lowercase().replace('أ', 'ا')
            return if (x.first() in 'a'..'z') t.contains(" $x ") else t.contains(x)
        }
        val genres = genreWords.filter { (_, words) -> words.any { has(it) } }.keys
        val fresh = Regex("\\b(new|newest|latest|recent)\\b|جديد|جديدة|جديده|احدث|حديث").containsMatchIn(t)
        val rating = Regex("(above|over|more than|at least|فوق|اكثر من|اعلى من|من)\\s*(\\d+(?:\\.\\d)?)|(\\d+(?:\\.\\d)?)\\s*\\+|(\\d+(?:\\.\\d)?)\\s*(stars|نجوم)")
            .find(t)?.groupValues?.drop(1)?.firstOrNull { it.toDoubleOrNull() != null }?.toDouble()?.takeIf { it in 1.0..10.0 }
        val year = Regex("\\b(19[5-9]\\d|20[0-4]\\d)\\b").find(t)?.value
        if (genres.isEmpty() && !fresh && rating == null && year == null) return null
        if (!wantMovies && !wantSeries && genres.isEmpty()) return null // plain titles with a year are normal searches
        val catNames = (Repo.vodCats + Repo.seriesCats).associate { it.id to it.name.lowercase() }
        fun genreOk(cat: String, genreText: String): Boolean {
            if (genres.isEmpty()) return true
            val hay = (catNames[cat].orEmpty() + " " + genreText.lowercase()).replace('أ', 'ا')
            return genres.any { g -> genreWords[g]!!.any { hay.contains(it.lowercase().replace('أ', 'ا')) } || hay.contains(g) }
        }
        fun rateOk(r: String) = rating == null || (r.toDoubleOrNull() ?: 0.0) >= rating
        fun yearOk(name: String) = year == null || com.streambox.tv.data.Tmdb.cleanTitle(name).second == year
        val since = System.currentTimeMillis() - 60L * 86_400_000L
        val out = ArrayList<Any>()
        if (wantMovies || !wantSeries) {
            var mv = Repo.collapseMovies(Repo.moviesIn(Repo.CAT_ALL)).filter { genreOk(it.categoryId, "") && rateOk(it.rating) && yearOk(it.name) }
            if (fresh) mv = mv.filter { it.added == 0L || it.added >= since }.sortedByDescending { it.added }
            else mv = mv.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            out.addAll(mv.take(if (wantSeries) 40 else 80))
        }
        if (wantSeries || !wantMovies) {
            var sr = Repo.seriesIn(Repo.CAT_ALL).filter { genreOk(it.categoryId, it.genre) && rateOk(it.rating) && yearOk(it.name + " " + it.releaseDate) }
            if (fresh) sr = sr.filter { it.added == 0L || it.added >= since }.sortedByDescending { it.added }
            else sr = sr.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            out.addAll(sr.take(if (wantMovies) 40 else 80))
        }
        val parts = ArrayList<String>()
        if (fresh) parts.add(Tr.s("New"))
        parts.addAll(genres.map { g -> genreWords[g]!!.first().replaceFirstChar { it.uppercase() } })
        parts.add(Tr.s(if (wantMovies && !wantSeries) "films" else if (wantSeries && !wantMovies) "series" else "films and series"))
        if (rating != null) parts.add("★ ${rating.toString().removeSuffix(".0")}+")
        if (year != null) parts.add(year)
        return parts.joinToString(" ") to out
    }

    /**
     * Sound-alike key that works across Arabic and Latin spelling: consonants only, with similar
     * sounds merged ("Saqr" and "صقر" both give "skr", "Mohamed" and "محمد" give "mhmd").
     */
    private fun skeleton(s: String): String {
        val ar = mapOf(
            'ا' to "", 'أ' to "", 'إ' to "", 'آ' to "", 'ء' to "", 'ئ' to "", 'ؤ' to "", 'ى' to "", 'ي' to "y", 'و' to "w",
            'ب' to "b", 'ت' to "t", 'ث' to "t", 'ج' to "j", 'ح' to "h", 'خ' to "k", 'د' to "d", 'ذ' to "d", 'ر' to "r",
            'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "", 'غ' to "g",
            'ف' to "f", 'ق' to "k", 'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'ة' to "", 'پ' to "b", 'گ' to "g"
        )
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            val a = ar[ch]
            if (a != null) sb.append(a) else if (ch in 'a'..'z' || ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
            .replace("sh", "s").replace("kh", "k").replace("gh", "g").replace("th", "t").replace("dh", "d").replace("ph", "f")
            .replace('q', 'k').replace('c', 'k').replace('x', 'k').replace('v', 'f').replace('p', 'b').replace('j', 'g').replace('e', 'a')
            .replace(Regex("[aeiouyw]"), "")
            .replace(Regex("(.)\\1+"), "$1")
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
        const val EXTRA_QUERY = "query"
    }
}
