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

    private data class Row(val title: String, val value: String, val action: (() -> Unit)? = null)

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
        rows.add(Row("DNS", dnsNames[Prefs.dnsMode.coerceIn(0, Net.dnsChoices.size - 1)] + "   •   " + dns) { chooseDns() })
        rows.add(Row("Find the fastest DNS", Tr.s("Tests every DNS with your provider's address and lets you pick")) { fastestDns() })
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
        Net.clearDnsCache()
        dns = Tr.s("Checking…")
        quickChecks()
    }

    /** Times a lookup of the provider's address with every DNS choice and offers the results, fastest first. */
    private fun fastestDns() {
        val host = Repo.profile?.server?.toHttpHostOrNull() ?: "www.google.com"
        toast("Testing every DNS… (a few seconds)")
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                Net.dnsChoices.indices.filter { it != 0 && (it != Net.DNS_CUSTOM_IP || Prefs.customDns.isNotBlank()) &&
                    (it != Net.DNS_CUSTOM_DOH || Prefs.customDoh.isNotBlank()) }
                    .map { i -> i to Net.testDns(i, host) }
            }
            val sorted = results.sortedBy { it.second ?: Long.MAX_VALUE }
            val names = dnsNames
            val labels = sorted.map { (i, ms) -> (if (ms == null) "✕  " + Tr.s("failed") else "$ms ms") + "   •   " + names[i] +
                if (i == Prefs.dnsMode) "   ✓" else "" }.toTypedArray()
            AlertDialog.Builder(this@NetworkActivity)
                .setTitle(Tr.s("Fastest DNS for") + " $host")
                .setItems(labels) { _, w -> if (sorted[w].second != null) applyDns(sorted[w].first) else toast("That DNS didn't answer") }
                .setNegativeButton(Tr.s("Close"), null)
                .show()
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
