package com.streambox.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Category
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo

/** Poster grid for Movies and Series. */
class BrowseActivity : AppCompatActivity() {

    /** Common shape for a movie or a series in the grid. */
    private data class Card(val id: String, val name: String, val poster: String, val rating: String, val added: Long = 0L) {
        val year: String get() = com.streambox.tv.data.Tmdb.cleanTitle(name).second
    }

    /** 0 provider order, 1 newest added, 2 highest rated, 3 A–Z, 4 newest year (remembered). */
    private var sortMode: Int
        get() = com.streambox.tv.data.Prefs.sortMode
        set(v) { com.streambox.tv.data.Prefs.sortMode = v }
    private val sortNames = arrayOf("Provider order", "Newest added", "Highest rated", "A–Z", "Newest year")

    private var section = Repo.SECTION_VOD
    private val favType: String get() = if (section == Repo.SECTION_VOD) Prefs.FAV_VOD else Prefs.FAV_SERIES
    private lateinit var catAdapter: SimpleAdapter<Category>
    private lateinit var gridAdapter: SimpleAdapter<Card>
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var currentCat = Repo.CAT_ALL
    private var cards: List<Card> = emptyList()
    private var favs: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.loaded) {
            finish()
            return
        }
        setContentView(R.layout.activity_browse)
        section = intent.getStringExtra(EXTRA_SECTION) ?: Repo.SECTION_VOD
        findViewById<TextView>(R.id.title).text = if (section == Repo.SECTION_VOD) "Movies" else "Series"
        search = findViewById(R.id.search)
        (search.parent as? android.view.ViewGroup)?.let { parent ->
            val b = android.widget.Button(this, null, 0, R.style.Btn)
            b.text = Tr.s("⇅  " + sortNames[sortMode.coerceIn(0, sortNames.size - 1)])
            b.setOnClickListener { chooseSort() }
            parent.addView(b, parent.indexOfChild(search) + 1, android.view.ViewGroup.MarginLayoutParams(-2, -2).apply {
                marginStart = (10 * resources.displayMetrics.density).toInt()
            })
            sortButton = b
        }
        empty = findViewById(R.id.empty)

        catAdapter = SimpleAdapter(
            R.layout.item_text,
            bind = { v, c, _ -> (v as TextView).text = if (Repo.isLocked(section, c)) "🔒  ${c.name}" else c.name },
            onClick = { c, pos ->
                Ui.unlock(this, section, c) {
                    catAdapter.select(pos)
                    showCategory(c.id)
                }
            },
            onLongClick = { c, _ ->
                Ui.categoryMenu(this, section, c) { catAdapter.submit(categories(), catAdapter.selected) }
                true
            }
        )
        val catView = findViewById<RecyclerView>(R.id.categories)
        catView.layoutManager = LinearLayoutManager(this)
        catView.adapter = catAdapter

        gridAdapter = SimpleAdapter(
            R.layout.item_poster,
            bind = { v, c, _ ->
                v.findViewById<TextView>(R.id.title).text = c.name
                v.findViewById<ImageView>(R.id.poster).loadUrl(c.poster)
                val r = c.rating.toFloatOrNull()
                val badge = (if (c.id in favs) "★ " else "") + (if (r != null && r > 0f) "★ " + c.rating.take(3) else "") +
                    (if (c.year.isNotEmpty()) "  ${c.year}" else "")
                val b = v.findViewById<TextView>(R.id.badge)
                b.text = badge.trim()
                b.visibility = if (badge.isBlank()) View.GONE else View.VISIBLE
            },
            onClick = { c, _ -> open(c) },
            onLongClick = { c, pos ->
                if (currentCat == Repo.CAT_CONTINUE) {
                    // In Continue watching, long-press offers to drop the title from the list.
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(c.name)
                        .setItems(arrayOf("Remove from Continue watching")) { _, _ ->
                            if (section == Repo.SECTION_VOD) Repo.forgetProgress(Repo.movie(c.id), null)
                            else Repo.forgetProgress(null, Repo.seriesItem(c.id))
                            showCategory(currentCat)
                        }
                        .show()
                } else {
                    val now = Prefs.toggleFavourite(Repo.userKey, favType, c.id)
                    favs = Prefs.favourites(Repo.userKey, favType)
                    toast(if (now) "Added to favourites" else "Removed from favourites")
                    if (currentCat == Repo.CAT_FAV) showCategory(currentCat) else gridAdapter.refresh(pos)
                }
                true
            }
        )
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, when (com.streambox.tv.data.Prefs.posterSize) { 0 -> 7; 2 -> 4; else -> 5 })
        grid.itemAnimator = null
        grid.adapter = gridAdapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        // Reopen the category and title the user was on last time.
        val cats = categories()
        val savedCat = Prefs.lastCategory(Repo.profileId, section)
        var catIdx = cats.indexOfFirst { it.id == savedCat && !Repo.isLocked(section, it) }
        if (catIdx < 0) catIdx = 0
        catAdapter.submit(cats, catIdx)
        showCategory(cats[catIdx].id)
        val lastItem = Prefs.lastItem(Repo.profileId, section)
        val itemIdx = gridAdapter.items.indexOfFirst { it.id == lastItem }
        catView.post {
            if (catIdx > 0) (catView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(catIdx, 120)
            if (itemIdx < 0) {
                catView.requestFocus()
            } else {
                val lm = grid.layoutManager as GridLayoutManager
                lm.scrollToPositionWithOffset(itemIdx, 60)
                grid.post { lm.findViewByPosition(itemIdx)?.requestFocus() ?: catView.requestFocus() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Repo.loaded) Prefs.lastScreen = section
    }

    private fun categories(): List<Category> {
        val list = ArrayList<Category>()
        list.add(Category(Repo.CAT_ALL, if (section == Repo.SECTION_VOD) "All movies" else "All series"))
        list.add(Category(Repo.CAT_CONTINUE, "▶ Continue watching"))
        list.add(Category(Repo.CAT_FAV, "★ Favourites"))
        list.addAll(if (section == Repo.SECTION_VOD) Repo.vodCats else Repo.seriesCats)
        return list
    }

    private fun showCategory(id: String) {
        currentCat = id
        Prefs.setLastCategory(Repo.profileId, section, id)
        favs = Prefs.favourites(Repo.userKey, favType)
        cards = if (section == Repo.SECTION_VOD) {
            Repo.collapseMovies(Repo.moviesIn(id)).map { Card(it.id, it.name, it.poster, it.rating, it.added) }
        } else {
            Repo.seriesIn(id).map { Card(it.id, it.name, it.poster, it.rating, it.added) }
        }
        applyFilter()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        val filtered = if (q.isEmpty()) cards else cards.filter { it.name.contains(q, ignoreCase = true) }
        val list = when (sortMode) {
            1 -> filtered.sortedByDescending { it.added }
            2 -> filtered.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            3 -> filtered.sortedBy { it.name.lowercase() }
            4 -> filtered.sortedByDescending { it.year.toIntOrNull() ?: 0 }
            else -> filtered
        }
        gridAdapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            chooseSort()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun chooseSort() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Tr.s("Sort by"))
            .setSingleChoiceItems(sortNames.map { Tr.s(it) }.toTypedArray(), sortMode) { d, w ->
                sortMode = w
                sortButton?.text = Tr.s("⇅  " + sortNames[w])
                applyFilter()
                d.dismiss()
            }
            .show()
    }

    private var sortButton: android.widget.Button? = null

    private fun open(c: Card) {
        Prefs.setLastItem(Repo.profileId, section, c.id)
        if (section == Repo.SECTION_VOD) {
            startActivity(Intent(this, MovieDetailActivity::class.java).putExtra(MovieDetailActivity.EXTRA_ID, c.id))
        } else {
            startActivity(Intent(this, SeriesDetailActivity::class.java).putExtra(SeriesDetailActivity.EXTRA_ID, c.id))
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Favourites and Continue watching may have changed while a title was open.
        if (::gridAdapter.isInitialized && (currentCat == Repo.CAT_FAV || currentCat == Repo.CAT_CONTINUE)) showCategory(currentCat)
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }
}
