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

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun ImageView.loadUrl(url: String?) {
    if (url.isNullOrBlank()) {
        setImageResource(R.drawable.placeholder)
    } else {
        load(url) {
            crossfade(true)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
        }
    }
}

object Ui {
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

    private const val PIN_TYPE = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

    fun askPin(activity: Activity, onOk: () -> Unit) {
        if (!Prefs.parentalEnabled) {
            onOk()
            return
        }
        input(activity, "Enter parental PIN", "", PIN_TYPE) { entered ->
            if (entered == Prefs.pin) onOk() else activity.toast("Wrong PIN")
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
