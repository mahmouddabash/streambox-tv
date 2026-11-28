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
    /** Cloudflare WARP entry points (different addresses and ports; some networks block some of them). */
    val ENDPOINTS = listOf(
        "162.159.192.1:2408", "162.159.193.1:2408", "162.159.195.1:2408", "engage.cloudflareclient.com:2408",
        "188.114.96.1:2408", "188.114.97.1:2408", "188.114.98.1:2408", "188.114.99.1:2408",
        "162.159.192.1:500", "162.159.192.1:1701", "162.159.192.1:4500", "162.159.193.1:854"
    )

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
    @Synchronized
    fun start(context: Context): Boolean {
        return try {
            if (permissionIntent(context) != null) { lastError = "VPN permission not given"; return false }
            val conf = config(context) ?: return false
            try {
                backend(context).setState(tunnel, Tunnel.State.UP, conf)
            } catch (e: Exception) {
                lastError = "tunnel didn't start: " + (e.message ?: e.javaClass.simpleName)
                state = Tunnel.State.DOWN
                return false
            }
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

    @Synchronized
    fun stop(context: Context) {
        try { backend(context).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        state = Tunnel.State.DOWN
        Net.clearDnsCache()
    }

    /** Switches to another Cloudflare entry point (when one is slow or blocked). */
    @Synchronized
    fun nextEndpoint(context: Context): Boolean {
        Prefs.vpnEndpoint = (Prefs.vpnEndpoint + 1) % ENDPOINTS.size
        stop(context)
        return start(context)
    }

    /** Round-trip time through the tunnel right now (best of 2 small requests), or null when it doesn't work. */
    fun probe(seconds: Long = 5, tries: Int = 2): Long? {
        val c = OkHttpClient.Builder().connectTimeout(seconds, TimeUnit.SECONDS).readTimeout(seconds, TimeUnit.SECONDS)
            .callTimeout(seconds + 1, TimeUnit.SECONDS).build()
        var best: Long? = null
        repeat(tries) {
            try {
                val t = System.nanoTime()
                c.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build()).execute().use { r ->
                    if (r.code == 204 || r.isSuccessful) {
                        val ms = (System.nanoTime() - t) / 1_000_000
                        if (best == null || ms < best!!) best = ms
                    }
                }
            } catch (_: Exception) {
            }
        }
        return best
    }

    data class EntryResult(val index: Int, val ms: Long?)

    /**
     * Tries every entry point through the running tunnel and keeps the fastest working one.
     * Only small test requests are used (nothing is sent to your provider).
     */
    @Synchronized
    fun pickFastest(context: Context, stopWhenGood: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<EntryResult> {
        if (!Prefs.vpnOn) return emptyList()
        val out = ArrayList<EntryResult>()
        val order = listOf(Prefs.vpnEndpoint.coerceIn(0, ENDPOINTS.size - 1)) + ENDPOINTS.indices.filter { it != Prefs.vpnEndpoint }
        for ((n, i) in order.withIndex()) {
            onProgress(n + 1, ENDPOINTS.size)
            if (stopWhenGood && out.any { (it.ms ?: Long.MAX_VALUE) < 250 }) break
            Prefs.vpnEndpoint = i
            val conf = try { config(context) } catch (_: Exception) { null } ?: break
            val ms = try {
                backend(context).setState(tunnel, Tunnel.State.UP, conf)
                quickCheck()
            } catch (_: Exception) { null }
            out.add(EntryResult(i, ms))
        }
        val best = out.filter { it.ms != null }.minByOrNull { it.ms!! }
        if (best == null) {
            // No entry point works on this network: never leave a dead tunnel (it would block the whole app).
            stop(context)
            Prefs.vpnOn = false
            lastError = "connected, but no Cloudflare entry point answers on this network (" + out.size + " tried)"
            return out
        }
        Prefs.vpnEndpoint = best.index
        Prefs.vpnPickedAt = System.currentTimeMillis()
        try { config(context)?.let { backend(context).setState(tunnel, Tunnel.State.UP, it) } } catch (_: Exception) {}
        state = Tunnel.State.UP
        Net.clearDnsCache()
        return out.sortedBy { it.ms ?: Long.MAX_VALUE }
    }

    /**
     * At app start: connect; if the saved entry point doesn't work, or it's been a day, find the
     * fastest one again.
     */
    fun autoStart(context: Context) {
        try {
            // By default every start is without the VPN; it's switched on only when you choose it.
            if (!Prefs.vpnAtStart) Prefs.vpnOn = false
            if (!Prefs.vpnOn) return
            if (!start(context)) { Prefs.vpnOn = false; return }
            // Check before the app goes on; if this entry point doesn't answer, look for another now.
            // Never switch entry points later in the background: restarting the tunnel while a video
            // is playing could close the app.
            if (quickCheck() == null) pickFastest(context)
        } finally {
            ready.countDown()
        }
    }

    /** First answer through a new tunnel: 2 short tries (the first can be lost while it shakes hands). */
    fun quickCheck(): Long? = probe(seconds = 2, tries = 1) ?: probe(seconds = 3, tries = 1)

    private val ready = java.util.concurrent.CountDownLatch(1)

    /** Waits (up to [ms]) until the VPN started at app start is ready, so the first logins don't fail. */
    fun awaitStart(ms: Long) { try { ready.await(ms, TimeUnit.MILLISECONDS) } catch (_: Exception) {} }

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
