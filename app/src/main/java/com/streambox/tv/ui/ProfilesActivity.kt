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
    private val vpnSwitch = VpnSwitch(this)
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private lateinit var adapter: SimpleAdapter<Profile>

    private lateinit var empty: TextView
    private lateinit var btnAdd: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        com.streambox.tv.data.CrashReporter.offerIfCrashed(this)
        empty = findViewById(R.id.empty)
        btnAdd = findViewById(R.id.btnAdd)
        btnAdd.setOnClickListener { startActivity(Intent(this, AddProfileActivity::class.java)) }
        val btnVpn = findViewById<android.widget.Button>(R.id.btnVpn)
        btnVpn.text = vpnSwitch.label
        btnVpn.setOnClickListener { vpnSwitch.toggle { btnVpn.text = vpnSwitch.label } }

        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, p, _ ->
                v.findViewById<TextView>(R.id.line1).text = p.name
                v.findViewById<TextView>(R.id.line2).text = when {
                    Prefs.isProtected(p) -> "Built-in profile  •  🔒 login hidden"
                    p.isXtream -> "Xtream Codes  •  ${p.server}  •  ${p.username}"
                    else -> "M3U playlist  •  ${p.m3uUrl}"
                }
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
    }

    private fun reload() {
        val profiles = Prefs.profiles()
        adapter.submit(profiles)
        empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        // Saved profiles that couldn't be read this time: say so (they are kept), and try again shortly.
        if (Prefs.profilesUnreadable) {
            empty.text = Tr.s("Your saved profiles couldn't be opened just now. They are kept safe — trying again…")
            empty.postDelayed({ if (!isFinishing) reload() }, 2_000)
        } else empty.text = Tr.s("No profiles yet. Add your provider's Xtream Codes login or an M3U playlist URL to begin.")
        if (profiles.isEmpty()) btnAdd.requestFocus()
    }

    private fun open(p: Profile) {
        if (Prefs.isProtected(p) && !Prefs.protectedUnlocked) {
            Ui.askProfilePassword(this) { open(p) }
            return
        }
        startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_PROFILE, p.id))
    }

    private fun confirmDelete(p: Profile) {
        if (Prefs.isProtected(p)) {
            Ui.askProfilePassword(this) { reallyDelete(p) }
            return
        }
        reallyDelete(p)
    }

    private fun reallyDelete(p: Profile) {
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
