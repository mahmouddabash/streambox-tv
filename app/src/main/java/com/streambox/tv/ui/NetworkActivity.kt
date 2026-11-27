package com.streambox.tv.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.R
import com.streambox.tv.data.Net
import com.streambox.tv.data.Prefs
import com.streambox.tv.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale

/**
 * Network Center: connection type, internet and provider response times, DNS, a speed test against
 * your own provider, the connection limit, this month's data use, and a plain-language verdict.
 * Nothing here contacts anything except your provider (and Google's connectivity check).
 */
class NetworkActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private data class Row(val title: String, val value: String, val action: (() -> Unit)? = null)

    private val vpnPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (com.streambox.tv.data.FreeVpn.permissionIntent(this) == null) startVpn() else toast("The VPN wasn't allowed")
    }

    private fun toggleVpn() {
        if (Prefs.vpnOn) {
            Prefs.vpnOn = false
            lifecycleScope.launch(Dispatchers.IO) { com.streambox.tv.data.FreeVpn.stop(this@NetworkActivity) }
            toast("Free VPN off")
            refresh()
            return
        }
        val ask = com.streambox.tv.data.FreeVpn.permissionIntent(this)
        if (ask != null) {
            try { vpnPermission.launch(ask) } catch (_: Exception) { toast("This TV doesn't allow VPN apps") }
        } else startVpn()
    }

    /** Tests every free VPN entry point and switches to the fastest (also done by itself once a day). */
    private fun fastestVpn() {
        val vpn = com.streambox.tv.data.FreeVpn
        toast("Testing every free VPN entry point… (about 20 s)")
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { vpn.pickFastest(this@NetworkActivity) }
            val labels = res.map { (if (it.ms == null) "✕  " + Tr.s("doesn't work here") else "✓ ${it.ms} ms") + "   •   " +
                vpn.ENDPOINTS[it.index] + if (it.index == Prefs.vpnEndpoint && it.ms != null) "   ★ " + Tr.s("in use") else "" }
            AlertDialog.Builder(this@NetworkActivity)
                .setTitle(Tr.s("Free VPN entry points"))
                .setItems(labels.toTypedArray(), null)
                .setPositiveButton("OK", null)
                .show()
            refresh(); quickChecks()
        }
    }

    private fun startVpn() {
        toast("Connecting the free VPN…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { com.streambox.tv.data.FreeVpn.start(this@NetworkActivity) }
            Prefs.vpnOn = ok
            toast(if (ok) Tr.s("Free VPN on: StreamBox now goes through Cloudflare") else
                Tr.s("Free VPN failed") + ": " + com.streambox.tv.data.FreeVpn.lastError)
            refresh()
            if (ok) quickChecks()
        }
    }

    private lateinit var adapter: SimpleAdapter<Row>
    private var internet = "Checking…"
    private var provider = "Checking…"
    private var dns = "Checking…"
    private var dnsServers = "Checking…"
    private var dnsResult = "Checking…"
    private var speed = "Press OK to test (about 10 seconds)"
    private var verdict = "Press OK to run every check"
    private var speedMbps = -1.0
    private var internetMs: Long? = null
    private var providerMs: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.title).text = Tr.s("Network Center")
        findViewById<TextView>(R.id.status).text = Tr.s("Why does it buffer? Check your connection and your provider")
        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, r, _ ->
                v.findViewById<TextView>(R.id.line1).text = Tr.s(r.title)
                v.findViewById<TextView>(R.id.line2).text = r.value
            },
            onClick = { r, _ -> r.action?.invoke() }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        refresh()
        list.post { list.getChildAt(0)?.requestFocus() }
        quickChecks()
    }

    private fun gb(b: Long) = if (b >= 1_073_741_824L) String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
    else String.format(Locale.US, "%.0f MB", b / 1_048_576.0)

    private fun connectionType(): String = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "No connection"
            else -> listOfNotNull(
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (cable)"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> if (cm.isActiveNetworkMetered) "Wi-Fi (phone hotspot / metered)" else "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    else -> "Connected"
                },
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "VPN on" else null,
                (caps.linkDownstreamBandwidthKbps / 1000).takeIf { it > 0 }?.let { "link up to $it Mbps" }
            ).joinToString("   •   ")
        }
    } catch (_: Exception) {
        "Unknown"
    }

    private val dnsNames get() = Net.dnsChoices.mapIndexed { i, c ->
        when (i) {
            Net.DNS_CUSTOM_IP -> if (Prefs.customDns.isNotBlank()) Tr.s("Custom DNS server") + ": " + Prefs.customDns else Tr.s(c.name)
            Net.DNS_CUSTOM_DOH -> if (Prefs.customDoh.isNotBlank()) Tr.s("Custom private DNS") + ": " + Prefs.customDoh.removePrefix("https://") else Tr.s(c.name)
            Net.DNS_BEST -> Tr.s(c.name) + if (Prefs.bestDns > 0) "  →  " + Tr.s(Net.dnsChoices[Prefs.bestDns.coerceIn(0, Net.dnsChoices.size - 1)].name) else ""
            else -> Tr.s(c.name)
        }
    }

    private fun refresh() {
        val rows = ArrayList<Row>()
        rows.add(Row("🩺  Full check", verdict) { fullCheck() })
        rows.add(Row("Connection", connectionType()))
        rows.add(Row("Internet", internet))
        rows.add(Row("Provider server", provider))
        val a = Repo.account
        if (a.maxConnections.isNotEmpty()) {
            val max = a.maxConnections.toIntOrNull() ?: 0
            val used = a.activeConnections.toIntOrNull() ?: 0
            rows.add(Row("Connection limit", "${a.activeConnections.ifEmpty { "0" }} of ${a.maxConnections} in use" +
                if (max in 1..used) "   •   ⚠ " + Tr.s("limit reached: another device is watching") else ""))
        }
        rows.add(Row("Speed test", speed) { speedTest() })
        val vpn = com.streambox.tv.data.FreeVpn
        rows.add(Row("Free VPN (Cloudflare WARP)", when {
            Prefs.vpnOn && vpn.isUp -> Tr.s("On   •   only StreamBox goes through it   •   OK = turn off")
            Prefs.vpnOn -> Tr.s("Connecting…   •   OK = turn off")
            vpn.lastError.isNotEmpty() -> Tr.s("Off") + "   •   " + Tr.s("last problem") + ": " + Tr.s(vpn.lastError)
            else -> Tr.s("Off   •   use it when your provider blocks your home internet (HTTP 403)")
        }) { toggleVpn() })
        if (Prefs.vpnOn) rows.add(Row("Free VPN entry point", vpn.ENDPOINTS[Prefs.vpnEndpoint.coerceIn(0, vpn.ENDPOINTS.size - 1)] +
            "   •   " + Tr.s("OK = test all and use the fastest")) { fastestVpn() })
        rows.add(Row("DNS", dnsNames[Prefs.dnsMode.coerceIn(0, Net.dnsChoices.size - 1)] + "   •   " + dns) { chooseDns() })
        rows.add(Row("Find the fastest DNS", Tr.s("Logs in to your provider through every DNS, fastest first")) { fastestDns() })
        rows.add(Row("DNS servers", dnsServers))
        rows.add(Row("Provider address (IP)", dnsResult + "   •   " + Tr.s("OK = check again")) { quickChecks() })
        val quota = Prefs.dataQuotaGb
        val mob = Prefs.dataUsed(true)
        rows.add(Row("Monthly mobile data limit", if (quota <= 0) Tr.s("None   •   OK = set a limit for mobile data / hotspot") else {
            val left = (quota * 1_073_741_824L - mob).coerceAtLeast(0)
            String.format(Locale.US, "%d GB   •   %.0f %% used   •   ", quota, mob * 100.0 / (quota * 1_073_741_824L)) +
                Tr.s("about {} h of HD left").replace("{}", String.format(Locale.US, "%.1f", left / 1_350_000_000.0))
        }) {
            val opts = intArrayOf(0, 5, 10, 20, 30, 50, 100)
            AlertDialog.Builder(this).setTitle(Tr.s("Monthly mobile data limit"))
                .setItems(opts.map { if (it == 0) Tr.s("No limit") else "$it GB" }.toTypedArray()) { _, w ->
                    Prefs.dataQuotaGb = opts[w]
                    Prefs.quotaAlerted = 0
                    refresh()
                }.show()
        })
        rows.add(Row("Data used this month", "Wi-Fi / cable ${gb(Prefs.dataUsed(false))}   •   mobile / hotspot ${gb(Prefs.dataUsed(true))}   •   OK = reset") {
            AlertDialog.Builder(this).setTitle(Tr.s("Reset this month's data count?"))
                .setPositiveButton("OK") { _, _ -> Prefs.resetDataUsed(); refresh() }
                .setNegativeButton(Tr.s("Cancel"), null).show()
        })
        adapter.submit(rows, adapter.selected.coerceAtLeast(0))
    }

    private fun ping(url: String): Long? = try {
        val t = System.nanoTime()
        Net.client.newCall(Request.Builder().url(url).head().build()).execute().use { }
        (System.nanoTime() - t) / 1_000_000
    } catch (_: Exception) {
        null
    }

    private fun quickChecks() {
        lifecycleScope.launch {
            internetMs = withContext(Dispatchers.IO) { ping("https://www.gstatic.com/generate_204") }
            internet = internetMs?.let { "Online   •   $it ms" + if (it > 400) "   •   " + Tr.s("slow to respond") else "" } ?: Tr.s("Offline or blocked")
            refresh()
            val p = Repo.profile
            val host = p?.server?.toHttpHostOrNull()
            providerMs = if (p == null || p.server.isEmpty()) null else withContext(Dispatchers.IO) { ping("${p.server}/player_api.php") }
            provider = when {
                p == null || p.server.isEmpty() -> "Playlist profile (no server to check)"
                providerMs == null -> Tr.s("Not reachable right now")
                else -> "Reachable   •   $providerMs ms" + if (providerMs!! > 600) "   •   " + Tr.s("slow to respond") else ""
            }
            refresh()
            dnsServers = deviceDnsServers()
            if (host != null) {
                val r = withContext(Dispatchers.IO) { Net.dnsLookup(host) }
                val sys = withContext(Dispatchers.IO) { Net.systemLookup(host) }
                dns = r?.let { "lookup ${it.first} ms" } ?: Tr.s("lookup failed")
                dnsResult = when {
                    r == null -> "$host → " + Tr.s("not found")
                    else -> "$host → " + r.second.take(3).joinToString(", ") +
                        when {
                            sys == null -> "   •   " + Tr.s("the device's own DNS couldn't find it")
                            sys.toSet() != r.second.toSet() && sys.isNotEmpty() -> "   •   " + Tr.s("device DNS says") + " " + sys.take(2).joinToString(", ")
                            else -> ""
                        }
                }
            } else {
                dnsResult = Tr.s("No server to check (playlist profile)")
            }
            refresh()
        }
    }

    /** DNS servers the device is using now, and Android's Private DNS when it's on. */
    private fun deviceDnsServers(): String = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val servers = lp?.dnsServers?.mapNotNull { it.hostAddress }?.distinct().orEmpty()
        val private = if (android.os.Build.VERSION.SDK_INT >= 28 && lp?.isPrivateDnsActive == true)
            "   •   " + Tr.s("Private DNS") + ": " + (lp.privateDnsServerName ?: Tr.s("automatic")) else ""
        (if (servers.isEmpty()) Tr.s("Unknown") else servers.joinToString(", ")) + private
    } catch (_: Exception) {
        Tr.s("Unknown")
    }

    private fun String.toHttpHostOrNull(): String? = try {
        java.net.URI(this).host
    } catch (_: Exception) {
        null
    }

    /** Downloads part of a film from your own provider for up to 10 s and measures the speed. */
    private fun speedTest() {
        val m = Repo.moviesIn(Repo.CAT_ALL).firstOrNull()
        val url = m?.let { Repo.urlFor(it) } ?: Repo.channelsIn(Repo.CAT_ALL).firstOrNull()?.let { Repo.urlFor(it) }
        if (url == null) {
            speed = Tr.s("Nothing to test with yet")
            refresh()
            return
        }
        speed = Tr.s("Testing… (about 10 seconds)")
        refresh()
        lifecycleScope.launch {
            val mbps = withContext(Dispatchers.IO) {
                try {
                    val client = Net.client.newBuilder().cache(null).build()
                    client.newCall(Request.Builder().url(url).header("Range", "bytes=0-").build()).execute().use { r ->
                        val ins = r.body?.byteStream() ?: return@use -1.0
                        val buf = ByteArray(64 * 1024)
                        val start = System.nanoTime()
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            val sec = (System.nanoTime() - start) / 1e9
                            if (sec > 10 || total > 120L * 1024 * 1024) break
                        }
                        val sec = ((System.nanoTime() - start) / 1e9).coerceAtLeast(0.2)
                        Prefs.addDataUsed(total, Net.isMobileData(this@NetworkActivity))
                        total * 8 / sec / 1e6
                    }
                } catch (_: Exception) {
                    -1.0
                }
            }
            speedMbps = mbps
            speed = if (mbps <= 0) Tr.s("The test couldn't download from your provider") else
                String.format(Locale.US, "%.1f Mbps", mbps) + "   •   " + Tr.s(when {
                    mbps >= 25 -> "enough for 4K"
                    mbps >= 10 -> "enough for Full HD"
                    mbps >= 5 -> "enough for HD"
                    mbps >= 2.5 -> "enough for SD only"
                    else -> "too slow for smooth video"
                })
            refresh()
        }
    }

    private fun chooseDns() {
        AlertDialog.Builder(this)
            .setTitle("DNS")
            .setSingleChoiceItems(dnsNames.toTypedArray(), Prefs.dnsMode.coerceIn(0, Net.dnsChoices.size - 1)) { d, w ->
                d.dismiss()
                when (w) {
                    Net.DNS_CUSTOM_IP -> Ui.input(this, Tr.s("DNS server IP address (e.g. 192.168.1.1)"), Prefs.customDns, android.text.InputType.TYPE_CLASS_TEXT) { ip ->
                        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip.trim())) { toast("That isn't an IP address like 192.168.1.1"); return@input }
                        Prefs.customDns = ip
                        applyDns(w)
                    }
                    Net.DNS_CUSTOM_DOH -> Ui.input(this, Tr.s("Private DNS address (https://…/dns-query)"), Prefs.customDoh.ifBlank { "https://" }, android.text.InputType.TYPE_CLASS_TEXT) { url ->
                        if (!url.trim().startsWith("https://") || url.trim().length < 12) { toast("The address must start with https://"); return@input }
                        Prefs.customDoh = url
                        applyDns(w)
                    }
                    else -> applyDns(w)
                }
            }
            .show()
    }

    private fun applyDns(mode: Int) {
        Prefs.dnsMode = mode
        if (mode == Net.DNS_BEST) { fastestDns(); return }
        Net.clearDnsCache()
        dns = Tr.s("Checking…")
        quickChecks()
    }

    /**
     * Tests every DNS against the provider (address lookup + a real connection to it) and lists them
     * fastest first. With "Best for my provider" the winner is switched on by itself.
     */
    private fun fastestDns() {
        val prof = Repo.profile ?: return toast("Add a provider first")
        val host = (if (prof.isXtream) prof.server else prof.m3uUrl).toHttpHostOrNull() ?: return toast("Add a provider first")
        toast("Logging in to your provider with every DNS… (a few seconds)")
        lifecycleScope.launch {
            val sorted = withContext(Dispatchers.IO) { Net.scoreDnsFor(prof) }
            val names = dnsNames
            val winner = sorted.firstOrNull { it.ok }
            if (Prefs.dnsMode == Net.DNS_BEST && winner != null) {
                Prefs.bestDns = winner.index
                Prefs.bestDnsAt = System.currentTimeMillis()
                Prefs.bestDnsNet = if (Net.isMobileData(this@NetworkActivity)) "m" else "w"
                Net.clearDnsCache()
            }
            val labels = sorted.map { r ->
                (if (!r.ok) "✕  " + Tr.s(r.why.ifEmpty { "failed" }).replace(Regex(" \\(HTTP \\d+\\)"), "")
                else "✓ " + Tr.s("login") + " ${r.total} ms") + "   •   " + names[r.index] + (if (r.ip.isNotEmpty()) "   →  ${r.ip}" else "") +
                    (if (r == winner) "   ★ " + Tr.s("best") else "") + if (r.index == Net.effectiveDns()) "   ✓" else ""
            }.toTypedArray()
            val refused = sorted.isNotEmpty() && winner == null && sorted.any { it.why.startsWith("provider refused") }
            if (refused) {
                AlertDialog.Builder(this@NetworkActivity)
                    .setTitle(Tr.s("Your provider is refusing this internet connection"))
                    .setMessage(Tr.s("Every DNS found your provider, but the provider answered \"forbidden\" (403). This isn't a DNS problem: the provider is blocking the internet address of this connection (your home internet). It usually works on mobile data / a phone hotspot because that's a different address.\n\nWhat helps:\n•  Restart your router (you usually get a new internet address).\n•  If it still fails, ask your provider to unblock your connection, or use a VPN (Network Center → Free VPN).\n•  Too many devices or logins at once can cause a temporary block: close the app on other devices and wait 30–60 minutes."))
                    .setPositiveButton(Tr.s("Show the results"), null)
                    .setNeutralButton(Tr.s("Turn on free VPN")) { _, _ -> if (!Prefs.vpnOn) toggleVpn() }
                    .show()
            }
            AlertDialog.Builder(this@NetworkActivity)
                .setTitle(Tr.s("Login test with") + " $host")
                .setItems(labels) { _, w -> if (sorted[w].ok) applyDns(sorted[w].index) else toast("Login through that DNS failed") }
                .setPositiveButton(Tr.s("Use the best automatically")) { _, _ -> Prefs.dnsMode = Net.DNS_BEST
                    winner?.let { Prefs.bestDns = it.index; Prefs.bestDnsAt = System.currentTimeMillis() }
                    Net.clearDnsCache(); quickChecks() }
                .setNegativeButton(Tr.s("Close"), null)
                .show()
            quickChecks()
        }
    }

    /** Runs everything and writes one plain verdict. */
    private fun fullCheck() {
        verdict = Tr.s("Checking…")
        refresh()
        quickChecks()
        speedTest()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(13_000)
            val a = Repo.account
            val max = a.maxConnections.toIntOrNull() ?: 0
            val used = a.activeConnections.toIntOrNull() ?: 0
            verdict = Tr.s(when {
                internetMs == null -> "No internet. Check the Wi-Fi or cable, or restart the router."
                Repo.profile?.server?.isNotEmpty() == true && providerMs == null ->
                    "The internet works but your provider doesn't answer. Their server may be down: try later, or change DNS above."
                max in 1..used -> "Your provider's connection limit is reached. Stop watching on the other device, then try again."
                speedMbps in 0.0..5.0 && speedMbps > 0 -> "Your connection to the provider is slow. Use HD/SD channels, move closer to the router, or use a cable."
                (providerMs ?: 0) > 800 -> "Your provider responds slowly. Buffering is most likely on their side; it usually passes."
                speedMbps > 0 -> "All good: your connection and provider look healthy."
                else -> "Mostly fine, but the speed test couldn't run. Try it again."
            })
            refresh()
        }
    }
}
