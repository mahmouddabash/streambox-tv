package com.streambox.tv.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streambox.tv.data.FreeVpn
import com.streambox.tv.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Turns the free VPN on/off from any screen (asks Android's VPN permission the first time). Create it as a property. */
class VpnSwitch(private val a: AppCompatActivity) {
    private var pending: ((Boolean) -> Unit)? = null
    private val ask = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (FreeVpn.permissionIntent(a) == null) connect() else { toast("The VPN wasn't allowed"); finish(false) }
    }

    val label: String get() = Tr.s(if (Prefs.vpnOn) "🛡  Free VPN: on" else "🛡  Free VPN: off")

    fun toggle(done: (Boolean) -> Unit = {}) = if (Prefs.vpnOn) turnOff(done) else turnOn(done)

    fun turnOn(done: (Boolean) -> Unit = {}) {
        pending = done
        val intent = FreeVpn.permissionIntent(a)
        if (intent != null) {
            try { ask.launch(intent) } catch (_: Exception) { toast("This TV doesn't allow VPN apps"); finish(false) }
        } else connect()
    }

    fun turnOff(done: (Boolean) -> Unit = {}) {
        Prefs.vpnOn = false
        a.lifecycleScope.launch {
            withContext(Dispatchers.IO) { FreeVpn.stop(a) }
            toast("Free VPN off")
            done(false)
        }
    }

    private fun connect() {
        toast("Connecting the free VPN…")
        Prefs.vpnOn = true
        a.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                FreeVpn.start(a) && (FreeVpn.quickCheck() != null || FreeVpn.pickFastest(a).any { it.ms != null })
            }
            Prefs.vpnOn = ok
            if (!ok) withContext(Dispatchers.IO) { FreeVpn.stop(a) }
            toast(if (ok) Tr.s("Free VPN on: StreamBox now goes through Cloudflare") else Tr.s("Free VPN failed") + ": " + FreeVpn.lastError.ifEmpty { Tr.s("no entry point works on this network") })
            finish(ok)
        }
    }

    private fun finish(ok: Boolean) { pending?.invoke(ok); pending = null }

    private fun toast(s: String) = android.widget.Toast.makeText(a, Tr.s(s), android.widget.Toast.LENGTH_LONG).show()
}
