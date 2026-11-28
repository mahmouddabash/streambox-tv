package com.streambox.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streambox.tv.R
import com.streambox.tv.data.Net
import com.streambox.tv.data.PairingServer
import com.streambox.tv.data.Profile
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import kotlinx.coroutines.launch
import java.util.UUID

class AddProfileActivity : AppCompatActivity() {
    private val vpnSwitch = VpnSwitch(this)
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private var xtream = true
    private lateinit var status: TextView
    private lateinit var btnSave: Button
    private var showTab: (Boolean) -> Unit = {}
    private var pairing: PairingServer? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_profile)
        findViewById<android.widget.CheckBox>(R.id.useVpn).isChecked = false
        // Sample provider address; type your own username and password.
        if (text(R.id.server).isBlank()) setText(R.id.server, Prefs.sampleServer)
        status = findViewById(R.id.status)
        btnSave = findViewById(R.id.btnSave)

        val tabX = findViewById<Button>(R.id.tabXtream)
        val tabM = findViewById<Button>(R.id.tabM3u)
        val xFields = findViewById<View>(R.id.xtreamFields)
        val mFields = findViewById<View>(R.id.m3uFields)

        showTab = { isXtream ->
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

    override fun onStart() {
        super.onStart()
        startPairing()
    }

    override fun onStop() {
        super.onStop()
        pairing?.stop()
        pairing = null
    }

    /** Shows a QR code that opens a form on the phone; submitted details are filled in and saved here. */
    private fun startPairing() {
        val image = findViewById<ImageView>(R.id.qrImage)
        val help = findViewById<TextView>(R.id.qrHelp)
        val server = PairingServer(onSubmit = { form -> runOnUiThread { onPhoneDetails(form) } }, prefill = { formSnapshot() })
        val url = server.start()
        val qr = if (url != null) Qr.bitmap(url) else null
        if (url == null || qr == null) {
            server.stop()
            image.visibility = View.GONE
            help.text = "Connect the TV to Wi-Fi or Ethernet to add a profile from your phone."
            return
        }
        pairing = server
        image.visibility = View.VISIBLE
        image.setImageBitmap(qr)
        help.text = "Scan with your phone camera.\nThe phone must be on the same Wi-Fi as the TV.\n\n$url"
    }

    private fun setText(id: Int, value: String?) {
        findViewById<EditText>(id).setText(value ?: "")
    }

    /** The TV form's current values (read on the screen's thread), for the phone page. */
    private fun formSnapshot(): Map<String, String> {
        val out = HashMap<String, String>()
        val done = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try {
                out["type"] = if (findViewById<View>(R.id.m3uFields).visibility == View.VISIBLE) "m3u" else "xtream"
                out["name"] = text(R.id.name)
                out["server"] = text(R.id.server)
                out["username"] = text(R.id.username)
                out["password"] = text(R.id.password)
                out["m3u"] = text(R.id.m3uUrl)
                out["epg"] = text(R.id.epgUrl)
            } catch (_: Exception) {
            }
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    /** Fills in what the phone sent; a field left empty on the phone keeps what's on the TV. */
    private fun keepOrSet(id: Int, value: String?) {
        if (!value.isNullOrBlank()) setText(id, value.trim())
    }

    private fun onPhoneDetails(form: Map<String, String>) {
        if (saving || isFinishing) return
        val isXtream = form["type"] != "m3u"
        showTab(isXtream)
        keepOrSet(R.id.name, form["name"])
        if (isXtream) {
            keepOrSet(R.id.server, form["server"])
            keepOrSet(R.id.username, form["username"])
            keepOrSet(R.id.password, form["password"])
        } else {
            keepOrSet(R.id.m3uUrl, form["m3u"])
            keepOrSet(R.id.epgUrl, form["epg"])
        }
        toast("Details received from your phone")
        save()
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

        val wantVpn = findViewById<android.widget.CheckBox>(R.id.useVpn).isChecked
        if (wantVpn && !Prefs.vpnOn) {
            btnSave.isEnabled = false
            status.text = Tr.s("Connecting the free VPN…")
            vpnSwitch.turnOn { ok ->
                btnSave.isEnabled = true
                if (ok) save() else status.text = Tr.s("Free VPN failed") + ": " + com.streambox.tv.data.FreeVpn.lastError
            }
            return
        }
        if (!wantVpn && Prefs.vpnOn) vpnSwitch.turnOff()
        btnSave.isEnabled = false
        saving = true
        status.text = if (xtream) "Checking login…" else "Saving…"
        lifecycleScope.launch {
            try {
                Repo.authenticate(profile)
                Prefs.addProfile(profile)
                toast(if (Prefs.profilesNotSaved) "This TV can't store logins securely, so it will be asked again next time" else "Profile saved")
                finish()
            } catch (e: Exception) {
                status.text = "Could not sign in: ${Net.explain(e)}"
                btnSave.isEnabled = true
                saving = false
            }
        }
    }
}
