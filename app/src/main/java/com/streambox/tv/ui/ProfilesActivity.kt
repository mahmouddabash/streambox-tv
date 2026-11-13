package com.streambox.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Profile
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo

class ProfilesActivity : AppCompatActivity() {

    companion object {
        /** Set once per app process so Back from Home returns here instead of relaunching the profile. */
        var autoOpened = false
    }


    private lateinit var adapter: SimpleAdapter<Profile>

    private lateinit var empty: TextView
    private lateinit var btnAdd: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        empty = findViewById(R.id.empty)
        btnAdd = findViewById(R.id.btnAdd)
        btnAdd.setOnClickListener { startActivity(Intent(this, AddProfileActivity::class.java)) }

        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, p, _ ->
                v.findViewById<TextView>(R.id.line1).text = p.name
                v.findViewById<TextView>(R.id.line2).text =
                    if (p.isXtream) "Xtream Codes  •  ${p.server}  •  ${p.username}" else "M3U playlist  •  ${p.m3uUrl}"
            },
            onClick = { p, _ -> open(p) },
            onLongClick = { p, _ -> confirmDelete(p); true }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
        maybeOpenLast()
    }

    /** Jumps straight into the last-used profile on a fresh launch, so the remote starts one step in. */
    private fun maybeOpenLast() {
        if (autoOpened) return
        autoOpened = true
        if (!Prefs.openLastProfile) return
        val last = Prefs.lastProfileId
        val p = Prefs.profiles().firstOrNull { it.id == last } ?: return
        open(p)
    }

    private fun reload() {
        val profiles = Prefs.profiles()
        adapter.submit(profiles)
        empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        if (profiles.isEmpty()) btnAdd.requestFocus()
    }

    private fun open(p: Profile) {
        startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_PROFILE, p.id))
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${p.name}\"?")
            .setMessage("Its favourites and locks are removed too.")
            .setPositiveButton("Delete") { _, _ ->
                if (Repo.profileId == p.id) Repo.close()
                Prefs.deleteProfile(p.id)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
