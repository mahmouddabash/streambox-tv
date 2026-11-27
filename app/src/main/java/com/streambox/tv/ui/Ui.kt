package com.streambox.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import coil.load
import com.streambox.tv.R
import com.streambox.tv.data.Category
import com.streambox.tv.data.PlayItem
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.toast(msg: String) = Toast.makeText(this, Tr.s(msg), Toast.LENGTH_SHORT).show()

fun ImageView.loadUrl(url: String?, onFail: (() -> Unit)? = null) {
    if (background != null && !clipToOutline) {
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
    if (url.isNullOrBlank()) {
        setImageResource(R.drawable.placeholder)
    } else {
        load(url) {
            crossfade(!com.streambox.tv.data.Prefs.lightMode)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
            if (onFail != null) listener(onError = { _, _ -> onFail() })
        }
    }
}

/** Channel logo, or a coloured circle with the channel's initials when there is none / it fails to load. */
fun ImageView.loadLogo(url: String?, name: String) {
    val fallback = InitialsDrawable(name)
    if (url.isNullOrBlank()) {
        setImageDrawable(fallback)
        return
    }
    load(url) {
        crossfade(true)
        placeholder(fallback)
        error(fallback)
    }
}

class InitialsDrawable(name: String) : android.graphics.drawable.Drawable() {
    private val text = name.replace(Regex("^[A-Za-z]{2,3}\\s*[|:-]\\s*"), "")
        .split(' ', '-', '_').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        val colors = intArrayOf(0xFF5A55D6.toInt(), 0xFFB0446A.toInt(), 0xFF17807F.toInt(), 0xFFB5702A.toInt(), 0xFF3F7D3A.toInt(), 0xFF2F6BFF.toInt())
        color = colors[(name.hashCode() and 0x7fffffff) % colors.size]
    }
    private val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, bg)
        fg.textSize = r * (if (text.length > 1) 0.8f else 1f)
        c.drawText(text, b.exactCenterX(), b.exactCenterY() - (fg.descent() + fg.ascent()) / 2, fg)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

object Ui {
    /** Opens a YouTube trailer in the YouTube app (or browser). */
    fun openTrailer(activity: Activity, youtubeId: String) {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeId")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeId"))
        )
        for (i in tries) {
            try {
                activity.startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        activity.toast("Install the YouTube app to watch trailers")
    }

    /** Loads a full-screen backdrop and fades it in (skips reloading the same URL). */
    fun backdrop(view: ImageView, url: String?) {
        if (url.isNullOrBlank() || view.tag == url) return
        view.tag = url
        view.load(url) {
            crossfade(false)
            listener(onSuccess = { _, _ -> view.animate().alpha(0.75f).setDuration(600).start() })
        }
    }

    /** Bold title with a smaller, dimmer second line (used on tiles). */
    fun twoLine(title: String, sub: String, subScale: Float = 0.62f): CharSequence {
        if (sub.isEmpty()) return title
        val s = android.text.SpannableString("$title\n$sub")
        val start = title.length + 1
        s.setSpan(android.text.style.RelativeSizeSpan(subScale), start, s.length, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(0xCCFFFFFF.toInt()), start, s.length, 0)
        s.setSpan(android.text.style.TypefaceSpan("sans-serif"), start, s.length, 0)
        return s
    }

    fun time(ms: Long): String =
        SimpleDateFormat(if (Prefs.use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

    fun day(ms: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

    fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Text input dialog that works with a TV remote. */
    fun input(
        activity: Activity,
        title: String,
        initial: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onOk: (String) -> Unit
    ) {
        val edit = EditText(activity)
        edit.inputType = inputType
        edit.setText(initial)
        edit.setSelection(edit.text.length)
        val box = FrameLayout(activity)
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(edit)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
        edit.requestFocus()
    }

    /** Asks for the built-in profile's password; runs [onOk] only when it is right. */
    fun askProfilePassword(activity: Activity, onOk: () -> Unit) {
        input(activity, "Profile password", "", PIN_TYPE) {
            if (com.streambox.tv.data.Secret.checkPassword(it)) {
                com.streambox.tv.data.Prefs.protectedUnlocked = true
                onOk()
            } else {
                activity.toast("Wrong password")
            }
        }
    }

    /** Removes the hidden login (server, user name, password) from messages shown on screen. */
    fun mask(text: String): String {
        val p = com.streambox.tv.data.Repo.profile ?: return text
        if (!com.streambox.tv.data.Prefs.isProtected(p)) return text
        var t = text
        val host = try { android.net.Uri.parse(p.server).host ?: "" } catch (_: Exception) { "" }
        for (secret in listOf(p.server, host, p.username, p.password)) {
            if (secret.length >= 3) t = t.replace(secret, "•••")
        }
        return t
    }

    private const val PIN_TYPE = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

    fun askPin(activity: Activity, onOk: () -> Unit) {
        if (!Prefs.parentalEnabled) {
            onOk()
            return
        }
        // After 3 wrong tries, PIN entry pauses: 30 s, then 2 min, then 10 min.
        val wait = Prefs.pinLockedUntil - System.currentTimeMillis()
        if (wait > 0) {
            activity.toast(Tr.s("Too many wrong PINs. Try again in {} s").replace("{}", "${wait / 1000 + 1}"))
            return
        }
        input(activity, "Enter parental PIN", "", PIN_TYPE) { entered ->
            if (entered == Prefs.pin) {
                Prefs.pinFails = 0
                onOk()
            } else {
                val fails = Prefs.pinFails + 1
                Prefs.pinFails = fails
                if (fails >= 3) {
                    val lock = when { fails >= 5 -> 600_000L; fails == 4 -> 120_000L; else -> 30_000L }
                    Prefs.pinLockedUntil = System.currentTimeMillis() + lock
                    activity.toast(Tr.s("Too many wrong PINs. Try again in {} s").replace("{}", "${lock / 1000}"))
                } else activity.toast("Wrong PIN")
            }
        }
    }

    fun newPin(activity: Activity, onDone: () -> Unit) {
        input(activity, "New PIN (4 digits or more)", "", PIN_TYPE) { a ->
            if (a.length < 4) {
                activity.toast("PIN must have at least 4 digits")
            } else {
                input(activity, "Repeat the new PIN", "", PIN_TYPE) { b ->
                    if (a == b) {
                        Prefs.pin = a
                        activity.toast("Parental PIN set")
                        onDone()
                    } else {
                        activity.toast("PINs do not match")
                    }
                }
            }
        }
    }

    /** Runs [action] directly, or after a correct PIN when the category is locked. */
    fun unlock(activity: Activity, section: String, cat: Category, action: () -> Unit) {
        if (Repo.isLocked(section, cat)) askPin(activity, action) else action()
    }

    /** Long-press menu on a category: lock / unlock it. */
    // ------------------------------------------------------------------ Viewers

    /** Runs [action] directly, or after the PIN when a Kids profile is active. */
    /**
     * One "⋯ More" button after [anchor] instead of a row of extra buttons: Watch later, Hide and
     * whatever [extras] holds when it's pressed (other copies, collection…). With [absorbAnchor]
     * the anchor (My list) moves into the menu too, to save space in narrow columns.
     */
    fun addMoreButton(
        activity: Activity, anchor: android.widget.Button, item: String,
        extras: () -> List<Pair<String, () -> Unit>> = { emptyList() }, absorbAnchor: Boolean = false
    ): android.widget.Button? {
        val parent = anchor.parent as? android.view.ViewGroup ?: return null
        val b = android.widget.Button(activity, null, 0, com.streambox.tv.R.style.Btn)
        b.text = Tr.s("⋯  More")
        b.setOnClickListener {
            val user = com.streambox.tv.data.Repo.userKey
            val later = item in com.streambox.tv.data.Prefs.watchLater(user)
            val opts = ArrayList<Pair<String, () -> Unit>>()
            if (absorbAnchor) opts.add(anchor.text.toString() to { anchor.performClick() })
            opts.addAll(extras())
            opts.add(Tr.s(if (later) "✓  Watch later (remove)" else "🕒  Watch later") to {
                com.streambox.tv.data.Prefs.setWatchLater(user, item, !later)
                activity.toast(if (later) "Removed from Watch later" else "Added to Watch later")
            })
            opts.add(Tr.s("🚫  Hide") to {
                android.app.AlertDialog.Builder(activity)
                    .setTitle(Tr.s("Hide this title?"))
                    .setMessage(Tr.s("It won't appear in rows, lists or search for this viewer. Undo in Settings → Hidden titles."))
                    .setPositiveButton(Tr.s("🚫  Hide")) { _, _ ->
                        com.streambox.tv.data.Prefs.setHidden(user, item, true)
                        activity.toast("Hidden")
                        activity.finish()
                    }
                    .setNegativeButton(Tr.s("Cancel"), null)
                    .show()
            })
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
                .show()
        }
        parent.addView(b, parent.indexOfChild(anchor) + 1, android.view.ViewGroup.LayoutParams(anchor.layoutParams))
        if (absorbAnchor) anchor.visibility = android.view.View.GONE
        return b
    }

    /** Adds "Hide" after [anchor]: the title disappears from every row, list and search for this viewer. */
    fun addHideButton(activity: Activity, anchor: android.widget.Button, item: String) {
        val parent = anchor.parent as? android.view.ViewGroup ?: return
        val b = android.widget.Button(activity, null, 0, com.streambox.tv.R.style.Btn)
        b.text = Tr.s("🚫  Hide")
        b.setOnClickListener {
            android.app.AlertDialog.Builder(activity)
                .setTitle(Tr.s("Hide this title?"))
                .setMessage(Tr.s("It won't appear in rows, lists or search for this viewer. Undo in Settings → Hidden titles."))
                .setPositiveButton(Tr.s("🚫  Hide")) { _, _ ->
                    com.streambox.tv.data.Prefs.setHidden(com.streambox.tv.data.Repo.userKey, item, true)
                    activity.toast("Hidden")
                    activity.finish()
                }
                .setNegativeButton(Tr.s("Cancel"), null)
                .show()
        }
        val lp = android.view.ViewGroup.LayoutParams(anchor.layoutParams)
        parent.addView(b, parent.childCount, lp)
    }

    /** Adds a "Watch later" toggle right after [anchor] (the My list button). [item] = "m:<id>" or "s:<id>". */
    fun addWatchLaterButton(activity: Activity, anchor: android.widget.Button, item: String) {
        val parent = anchor.parent as? android.view.ViewGroup ?: return
        val b = android.widget.Button(activity, null, 0, com.streambox.tv.R.style.Btn)
        fun label() {
            val on = item in com.streambox.tv.data.Prefs.watchLater(com.streambox.tv.data.Repo.userKey)
            b.text = Tr.s(if (on) "✓  Watch later" else "🕒  Watch later")
        }
        label()
        b.setOnClickListener {
            val on = item in com.streambox.tv.data.Prefs.watchLater(com.streambox.tv.data.Repo.userKey)
            com.streambox.tv.data.Prefs.setWatchLater(com.streambox.tv.data.Repo.userKey, item, !on)
            activity.toast(if (on) "Removed from Watch later" else "Added to Watch later")
            label()
        }
        parent.addView(b, parent.indexOfChild(anchor) + 1, android.view.ViewGroup.LayoutParams(anchor.layoutParams))
    }

    fun guardKids(activity: Activity, action: () -> Unit) {
        if (Repo.kidsMode) askPin(activity, action) else action()
    }

    /** "Who's watching?": picks the viewer, then calls [onChanged] if it changed. */
    fun chooseViewer(activity: Activity, onChanged: () -> Unit) {
        val viewers = Prefs.viewers(Repo.profileId)
        val ids = listOf("") + viewers.map { it.id }
        val names = listOf("👤  ${Prefs.mainViewerName}") + viewers.map { (if (it.kids) "🧒  " else "👤  ") + it.name }
        val current = ids.indexOf(Prefs.currentViewer(Repo.profileId)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Who's watching?")
            .setSingleChoiceItems(names.toTypedArray(), current) { d, w ->
                d.dismiss()
                if (w == current) return@setSingleChoiceItems
                val switch = {
                    Repo.selectViewer(ids[w])
                    onChanged()
                }
                // Leaving a Kids profile needs the PIN.
                if (Repo.kidsMode) askPin(activity, switch) else switch()
            }
            .show()
    }

    /** Long-press on a category: move it, hide it, or lock it with the PIN. */
    fun categoryMenu(activity: Activity, section: String, cat: Category, onChanged: () -> Unit) {
        if (cat.id.startsWith("__")) return
        if (Repo.kidsMode) return
        val kids = Repo.isKidsCategory(section, cat)
        val options = arrayOf("▲  Move up", "▼  Move down", "Hide this category",
            if (Repo.isUserLocked(section, cat)) "Unlock this category" else "Lock this category with PIN",
            if (kids) "🧒  Remove from Kids profiles" else "🧒  Show in Kids profiles")
        AlertDialog.Builder(activity)
            .setTitle(cat.name)
            .setItems(options) { _, w ->
                when (w) {
                    0 -> { Repo.moveCategory(section, cat.id, -1); onChanged() }
                    1 -> { Repo.moveCategory(section, cat.id, 1); onChanged() }
                    2 -> {
                        Prefs.setCategoryHidden(Repo.profileId, "$section:${cat.id}", true)
                        Repo.applyCustomisations()
                        activity.toast("Hidden. Show it again in Settings → Channel manager")
                        onChanged()
                    }
                    3 -> categoryLockMenu(activity, section, cat, onChanged)
                    else -> {
                        Prefs.setKidsCategory(Repo.profileId, "$section:${cat.id}", !kids)
                        Repo.applyCustomisations()
                        activity.toast(if (kids) "Removed from Kids profiles" else "Kids profiles can now see ${cat.name}")
                        onChanged()
                    }
                }
            }
            .show()
    }

    fun categoryLockMenu(activity: Activity, section: String, cat: Category, onChanged: () -> Unit) {
        if (cat.id.startsWith("__")) return
        if (!Prefs.parentalEnabled) {
            activity.toast("Set a parental PIN in Settings to lock categories")
            return
        }
        val locked = Repo.isUserLocked(section, cat)
        AlertDialog.Builder(activity)
            .setTitle(cat.name)
            .setItems(arrayOf(if (locked) "Unlock this category" else "Lock this category with PIN")) { _, _ ->
                askPin(activity) {
                    Prefs.toggleLocked(Repo.profileId, "$section:${cat.id}")
                    onChanged()
                }
            }
            .show()
    }

    /** Starts playback of [items] at [index], in the built-in or an external player. */
    fun play(activity: Activity, items: List<PlayItem>, index: Int, fromStart: Boolean = false) {
        if (items.isEmpty() || index !in items.indices) return
        if (Prefs.externalPlayer) {
            try {
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(Uri.parse(items[index].url), "video/*")
                i.putExtra("title", items[index].title)
                activity.startActivity(i)
                return
            } catch (_: Exception) {
                activity.toast("No external player found, using the built-in one")
            }
        }
        Repo.queue = items
        Repo.queueIndex = index
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
        )
    }
}
