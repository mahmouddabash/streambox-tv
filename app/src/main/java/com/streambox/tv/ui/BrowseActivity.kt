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
    private data class Card(val id: String, val name: String, val poster: String, val rating: String)

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
                Ui.categoryLockMenu(this, section, c) { catAdapter.submit(categories(), catAdapter.selected) }
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
                val badge = (if (c.id in favs) "★ " else "") + (if (r != null && r > 0f) c.rating else "")
                val b = v.findViewById<TextView>(R.id.badge)
                b.text = badge.trim()
                b.visibility = if (badge.isBlank()) View.GONE else View.VISIBLE
            },
            onClick = { c, _ -> open(c) },
            onLongClick = { c, pos ->
                val now = Prefs.toggleFavourite(Repo.profileId, favType, c.id)
                favs = Prefs.favourites(Repo.profileId, favType)
                toast(if (now) "Added to favourites" else "Removed from favourites")
                if (currentCat == Repo.CAT_FAV) showCategory(currentCat) else gridAdapter.refresh(pos)
                true
            }
        )
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.itemAnimator = null
        grid.adapter = gridAdapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        catAdapter.submit(categories(), 0)
        showCategory(Repo.CAT_ALL)
        catView.post { catView.requestFocus() }
    }

    private fun categories(): List<Category> {
        val list = ArrayList<Category>()
        list.add(Category(Repo.CAT_ALL, if (section == Repo.SECTION_VOD) "All movies" else "All series"))
        list.add(Category(Repo.CAT_FAV, "★ Favourites"))
        list.addAll(if (section == Repo.SECTION_VOD) Repo.vodCats else Repo.seriesCats)
        return list
    }

    private fun showCategory(id: String) {
        currentCat = id
        favs = Prefs.favourites(Repo.profileId, favType)
        cards = if (section == Repo.SECTION_VOD) {
            Repo.moviesIn(id).map { Card(it.id, it.name, it.poster, it.rating) }
        } else {
            Repo.seriesIn(id).map { Card(it.id, it.name, it.poster, it.rating) }
        }
        applyFilter()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        val list = if (q.isEmpty()) cards else cards.filter { it.name.contains(q, ignoreCase = true) }
        gridAdapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(c: Card) {
        if (section == Repo.SECTION_VOD) {
            startActivity(Intent(this, MovieDetailActivity::class.java).putExtra(MovieDetailActivity.EXTRA_ID, c.id))
        } else {
            startActivity(Intent(this, SeriesDetailActivity::class.java).putExtra(SeriesDetailActivity.EXTRA_ID, c.id))
        }
    }

    override fun onRestart() {
        super.onRestart()
        if (::gridAdapter.isInitialized && currentCat == Repo.CAT_FAV) showCategory(currentCat)
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }
}
