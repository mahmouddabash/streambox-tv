package com.streambox.tv.data

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Free VPN for StreamBox only: a WireGuard tunnel to Cloudflare WARP's free service. Only this app's
 * traffic goes through it (the rest of the TV is untouched). The free account is created on this
 * device the first time it's switched on. WARP is used here without Cloudflare's own app, so it
 * may stop working if Cloudflare changes it.
 */
object FreeVpn {
    private const val WARP_PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private val ENDPOINTS = listOf("162.159.192.1:2408", "162.159.193.1:2408", "162.159.195.1:2408", "engage.cloudflareclient.com:2408")

    @Volatile private var backend: GoBackend? = null
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
        private set
    @Volatile var lastError: String = ""
        private set

    private val tunnel = object : Tunnel {
        override fun getName() = "streambox"
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    val isUp get() = state == Tunnel.State.UP

    /** Intent asking the user's permission for a VPN (Android shows it once), or null when already allowed. */
    fun permissionIntent(context: Context) = VpnService.prepare(context)

    private fun backend(context: Context) = backend ?: GoBackend(context.applicationContext).also { backend = it }

    /** Creates the free account (once) and starts the tunnel. Run off the main thread. */
    fun start(context: Context): Boolean {
        return try {
            if (permissionIntent(context) != null) { lastError = "VPN permission not given"; return false }
            val conf = config(context) ?: return false
            backend(context).setState(tunnel, Tunnel.State.UP, conf)
            state = Tunnel.State.UP
            lastError = ""
            Net.clearDnsCache()
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            state = Tunnel.State.DOWN
            false
        }
    }

    fun stop(context: Context) {
        try { backend(context).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        state = Tunnel.State.DOWN
        Net.clearDnsCache()
    }

    /** Switches to another Cloudflare entry point (when one is slow or blocked). */
    fun nextEndpoint(context: Context): Boolean {
        Prefs.vpnEndpoint = (Prefs.vpnEndpoint + 1) % ENDPOINTS.size
        stop(context)
        return start(context)
    }

    private fun config(context: Context): Config? {
        if (Prefs.vpnPrivateKey.isBlank() || Prefs.vpnAddress4.isBlank()) {
            if (!register()) return null
        }
        val text = """
            [Interface]
            PrivateKey = ${Prefs.vpnPrivateKey}
            Address = ${Prefs.vpnAddress4}/32${if (Prefs.vpnAddress6.isNotBlank()) ", ${Prefs.vpnAddress6}/128" else ""}
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280
            IncludedApplications = ${context.packageName}

            [Peer]
            PublicKey = ${Prefs.vpnPeerKey.ifBlank { WARP_PEER }}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = ${ENDPOINTS[Prefs.vpnEndpoint.coerceIn(0, ENDPOINTS.size - 1)]}
            PersistentKeepalive = 25
        """.trimIndent()
        return Config.parse(text.byteInputStream())
    }

    /** Free WARP registration: a new key pair for this device. */
    private fun register(): Boolean {
        return try {
            val keys = KeyPair()
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val body = JSONObject()
                .put("key", keys.publicKey.toBase64())
                .put("install_id", "").put("fcm_token", "")
                .put("tos", fmt.format(Date()))
                .put("model", "Android TV").put("type", "Android").put("locale", "en_US")
                .toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("https://api.cloudflareclient.com/v0a2158/reg").post(body)
                .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.30-3596").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = "Free VPN sign-up failed (HTTP ${r.code})"; return false }
                val o = JSONObject(r.body?.string() ?: "")
                val cfg = o.getJSONObject("config")
                val addr = cfg.getJSONObject("interface").getJSONObject("addresses")
                val peer = cfg.getJSONArray("peers").optJSONObject(0)
                Prefs.vpnPrivateKey = keys.privateKey.toBase64()
                Prefs.vpnAddress4 = addr.optString("v4", "172.16.0.2")
                Prefs.vpnAddress6 = addr.optString("v6", "")
                Prefs.vpnPeerKey = peer?.optString("public_key", WARP_PEER) ?: WARP_PEER
            }
            true
        } catch (e: Exception) {
            lastError = "Free VPN sign-up failed: " + (e.message ?: e.javaClass.simpleName)
            false
        }
    }
}
