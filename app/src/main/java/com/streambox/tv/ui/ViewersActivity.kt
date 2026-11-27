package com.streambox.tv.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.streambox.tv.R
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import com.streambox.tv.data.Viewer

/** Full-screen "Who's watching?" with avatar tiles; hold OK on a tile to edit it. */
class ViewersActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private lateinit var tiles: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.loaded) {
            finish()
            return
        }
        setContentView(R.layout.activity_viewers)
        tiles = findViewById(R.id.tiles)
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        tiles.removeAllViews()
        val current = Prefs.currentViewer(Repo.profileId)
        val main = Viewer("", Prefs.mainViewerName, false, Prefs.mainViewerAvatar)
        val all = listOf(main) + Prefs.viewers(Repo.profileId)
        var focusTile: View? = null
        for (v in all) {
            val t = tile(AVATARS[v.avatar % AVATARS.size], COLORS[v.avatar % COLORS.size], v.name, v.kids)
            t.setOnClickListener { choose(v) }
            t.setOnLongClickListener { edit(v); true }
            tiles.addView(t)
            if (v.id == current) focusTile = t
        }
        val add = tile("+", 0x00000000, "Add profile", false, outlined = true)
        add.setOnClickListener { addViewer() }
        tiles.addView(add)
        (focusTile ?: tiles.getChildAt(0)).post { (focusTile ?: tiles.getChildAt(0)).requestFocus() }
    }

    private fun tile(emoji: String, color: Int, name: String, kids: Boolean, outlined: Boolean = false): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_card)
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.focus_lift)
            setPadding(dp(6), dp(6), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(12), 0, dp(12), 0) }
        }
        val face = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                if (outlined) {
                    setColor(0x0FFFFFFF)
                    setStroke(dp(2), 0x40FFFFFF)
                } else {
                    setColor(color)
                }
            }
        }
        face.addView(TextView(this).apply {
            text = emoji
            textSize = if (outlined) 64f else 76f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }, FrameLayout.LayoutParams(dp(150), dp(150)))
        if (kids) face.addView(TextView(this).apply {
            text = "KIDS"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xCC000000.toInt()) }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        col.addView(face)
        col.addView(TextView(this).apply {
            text = name
            textSize = 18f
            setTextColor(0xFFF5F7FF.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
        return col
    }

    private fun choose(v: Viewer) {
        if (v.id == Prefs.currentViewer(Repo.profileId)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val go = {
            Repo.selectViewer(v.id)
            setResult(RESULT_OK)
            finish()
        }
        // Leaving a Kids profile needs the parental PIN.
        if (Repo.kidsMode) Ui.askPin(this, go) else go()
    }

    private fun addViewer() {
        Ui.guardKids(this) {
            Ui.input(this, "Name", "") { name ->
                if (name.isBlank()) return@input
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setItems(arrayOf("👤  Normal profile", "🧒  Kids profile")) { _, w ->
                        val list = Prefs.viewers(Repo.profileId)
                        val avatar = (list.size + 1) % AVATARS.size
                        Prefs.saveViewers(Repo.profileId, list + Viewer("v${System.currentTimeMillis()}", name, w == 1, avatar))
                        if (w == 1 && !Prefs.parentalEnabled) toast("Tip: set a parental PIN in Settings so kids can't leave their profile")
                        build()
                    }
                    .show()
            }
        }
    }

    private fun edit(v: Viewer) {
        Ui.guardKids(this) {
            val isMain = v.id.isEmpty()
            val opts = ArrayList<Pair<String, () -> Unit>>()
            opts.add("✎  Rename" to {
                Ui.input(this, "Name", v.name) { name ->
                    if (name.isBlank()) return@input
                    if (isMain) Prefs.mainViewerName = name else saveViewer(v.copy(name = name))
                    build()
                }
            })
            opts.add("🙂  Change picture" to { pickAvatar(v) })
            if (v.kids) {
                val lim = Prefs.kidsLimit(v.id)
                opts.add(("⏱  " + Tr.s("Daily limit") + ": " + if (lim == 0) Tr.s("none") else "${lim / 60}h ${lim % 60}m") to {
                    val mins = intArrayOf(0, 30, 60, 90, 120, 180)
                    val labels = mins.map { if (it == 0) Tr.s("No limit") else "${it / 60}h ${it % 60}m" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle(Tr.s("Daily limit"))
                        .setItems(labels) { _, w -> Prefs.setKidsLimit(v.id, mins[w]); build() }.show()
                })
                val bed = Prefs.kidsBedtime(v.id)
                opts.add(("🌙  " + Tr.s("Bedtime") + ": " + if (bed < 0) Tr.s("none") else "%02d:00 – 06:00".format(bed)) to {
                    val hours = intArrayOf(-1, 19, 20, 21, 22, 23)
                    val labels = hours.map { if (it < 0) Tr.s("No bedtime") else "%02d:00 – 06:00".format(it) }.toTypedArray()
                    AlertDialog.Builder(this).setTitle(Tr.s("Bedtime"))
                        .setItems(labels) { _, w -> Prefs.setKidsBedtime(v.id, hours[w]); build() }.show()
                })
            }
            if (!isMain) {
                opts.add((if (v.kids) "👤  Make a normal profile" else "🧒  Make a Kids profile") to {
                    saveViewer(v.copy(kids = !v.kids))
                    Repo.applyCustomisations()
                    build()
                })
                opts.add("🗑  Delete" to {
                    Prefs.saveViewers(Repo.profileId, Prefs.viewers(Repo.profileId).filterNot { it.id == v.id })
                    if (Prefs.currentViewer(Repo.profileId) == v.id) Repo.selectViewer("")
                    build()
                })
            }
            AlertDialog.Builder(this)
                .setTitle(v.name)
                .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
                .show()
        }
    }

    private fun pickAvatar(v: Viewer) {
        AlertDialog.Builder(this)
            .setTitle("Choose a picture")
            .setItems(AVATARS.mapIndexed { i, a -> "$a    ${AVATAR_NAMES[i]}" }.toTypedArray()) { _, w ->
                if (v.id.isEmpty()) Prefs.mainViewerAvatar = w else saveViewer(v.copy(avatar = w))
                build()
            }
            .show()
    }

    private fun saveViewer(v: Viewer) {
        Prefs.saveViewers(Repo.profileId, Prefs.viewers(Repo.profileId).map { if (it.id == v.id) v else it })
    }

    companion object {
        val AVATARS = listOf("🦁", "🐼", "🦊", "🐧", "🐨", "🐯", "🐸", "🐵", "🦄", "🐙", "🚀", "⭐")
        val AVATAR_NAMES = listOf("Lion", "Panda", "Fox", "Penguin", "Koala", "Tiger", "Frog", "Monkey", "Unicorn", "Octopus", "Rocket", "Star")
        val COLORS = listOf(
            0xFF5A55D6.toInt(), 0xFFB0446A.toInt(), 0xFF17807F.toInt(), 0xFFB5702A.toInt(), 0xFF3F7D3A.toInt(), 0xFF2F6BFF.toInt(),
            0xFF8E44AD.toInt(), 0xFFC0392B.toInt(), 0xFF16A085.toInt(), 0xFFD35400.toInt(), 0xFF34495E.toInt(), 0xFFB7950B.toInt()
        )
    }
}
