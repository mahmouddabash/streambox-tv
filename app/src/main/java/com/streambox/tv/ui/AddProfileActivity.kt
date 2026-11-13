package com.streambox.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streambox.tv.R
import com.streambox.tv.data.Profile
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import kotlinx.coroutines.launch
import java.util.UUID

class AddProfileActivity : AppCompatActivity() {

    private var xtream = true
    private lateinit var status: TextView
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_profile)
        status = findViewById(R.id.status)
        btnSave = findViewById(R.id.btnSave)

        val tabX = findViewById<Button>(R.id.tabXtream)
        val tabM = findViewById<Button>(R.id.tabM3u)
        val xFields = findViewById<View>(R.id.xtreamFields)
        val mFields = findViewById<View>(R.id.m3uFields)

        fun showTab(isXtream: Boolean) {
            xtream = isXtream
            tabX.isActivated = isXtream
            tabM.isActivated = !isXtream
            xFields.visibility = if (isXtream) View.VISIBLE else View.GONE
            mFields.visibility = if (isXtream) View.GONE else View.VISIBLE
        }
        tabX.setOnClickListener { showTab(true) }
        tabM.setOnClickListener { showTab(false) }
        showTab(true)

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        btnSave.setOnClickListener { save() }
    }

    private fun text(id: Int): String = findViewById<EditText>(id).text.toString().trim()

    private fun save() {
        val name = text(R.id.name)
        val profile = if (xtream) {
            val server = Repo.normalizeServer(text(R.id.server))
            val user = text(R.id.username)
            val pass = text(R.id.password)
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                status.text = "Server URL, username and password are all required."
                return
            }
            Profile(
                id = UUID.randomUUID().toString().take(8),
                name = name.ifEmpty { user },
                type = Profile.TYPE_XTREAM,
                server = server,
                username = user,
                password = pass
            )
        } else {
            val url = text(R.id.m3uUrl)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                status.text = "Enter a playlist URL starting with http:// or https://"
                return
            }
            Profile(
                id = UUID.randomUUID().toString().take(8),
                name = name.ifEmpty { "My playlist" },
                type = Profile.TYPE_M3U,
                m3uUrl = url,
                epgUrl = text(R.id.epgUrl)
            )
        }

        btnSave.isEnabled = false
        status.text = if (xtream) "Checking login…" else "Saving…"
        lifecycleScope.launch {
            try {
                Repo.authenticate(profile)
                Prefs.addProfile(profile)
                toast("Profile saved")
                finish()
            } catch (e: Exception) {
                status.text = "Could not sign in: ${e.message ?: "network error"}"
                btnSave.isEnabled = true
            }
        }
    }
}
