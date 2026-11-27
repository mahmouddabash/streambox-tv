package com.streambox.tv.ui

import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streambox.tv.BuildConfig
import com.streambox.tv.R
import com.streambox.tv.data.CrashReporter
import com.streambox.tv.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Version, device, connection and account status, problem reports, licences and privacy. */
class AboutActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(zoomed(newBase))


    private data class Row(val title: String, val value: String, val action: (() -> Unit)? = null)

    private lateinit var adapter: SimpleAdapter<Row>
    private var network = "Checking…"
    private var server = "Checking…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.title).text = "About & diagnostics"
        findViewById<TextView>(R.id.status).text = "StreamBox TV ${BuildConfig.VERSION_NAME}"
        adapter = SimpleAdapter(
            R.layout.item_two_line,
            bind = { v, r, _ ->
                v.findViewById<TextView>(R.id.line1).text = r.title
                v.findViewById<TextView>(R.id.line2).text = r.value
            },
            onClick = { r, _ -> r.action?.invoke() }
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        refresh()
        list.post { list.getChildAt(0)?.requestFocus() }
        checkConnection()
    }

    private fun refresh() {
        val rows = ArrayList<Row>()
        rows.add(Row("Version", "${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})"))
        rows.add(Row("Device", "${Build.MANUFACTURER} ${Build.MODEL}  •  Android ${Build.VERSION.RELEASE}"))
        rows.add(Row("Internet", network))
        rows.add(Row("Provider server", server))
        val a = Repo.account
        if (Repo.loaded) {
            val parts = ArrayList<String>()
            if (a.status.isNotEmpty()) parts.add(a.status)
            if (a.expiryMs > 0) parts.add("Expires ${Ui.date(a.expiryMs)}")
            if (a.maxConnections.isNotEmpty()) parts.add("Connections ${a.activeConnections.ifEmpty { "0" }}/${a.maxConnections}")
            parts.add("${Repo.channels.size} channels, ${Repo.movies.size} movies, ${Repo.series.size} series")
            rows.add(Row("Subscription", parts.joinToString("   •   ")))
        }
        val free = try {
            StatFs(filesDir.absolutePath).availableBytes / 1_073_741_824.0
        } catch (_: Exception) { -1.0 }
        if (free >= 0) rows.add(Row("Free storage", String.format(java.util.Locale.US, "%.1f GB", free)))
        val report = CrashReporter.lastReport()
        rows.add(
            if (report != null) Row("Problem report", "A report from the last crash is saved. Press OK to send it") {
                CrashReporter.showReportQr(this, report)
            } else Row("Problem report", "No crashes recorded")
        )
        rows.add(Row("Privacy", "Press OK to read how your data is handled") { privacy() })
        rows.add(Row("Open-source licences", "Press OK to view") { licences() })
        adapter.submit(rows)
    }

    private fun checkConnection() {
        lifecycleScope.launch {
            network = withContext(Dispatchers.IO) { ping("https://www.gstatic.com/generate_204") }
                ?.let { "Online  •  ${it} ms" } ?: "Offline or blocked"
            refresh()
            val p = Repo.profile
            server = if (p == null || p.server.isEmpty()) "No server (playlist profile)" else
                withContext(Dispatchers.IO) { ping("${p.server}/player_api.php") }?.let { "Reachable  •  ${it} ms" }
                    ?: "Not reachable right now"
            refresh()
        }
    }

    /** Round-trip time in ms, or null when unreachable. */
    private fun ping(url: String): Long? = try {
        val start = System.nanoTime()
        com.streambox.tv.data.Net.client.newCall(Request.Builder().url(url).head().build()).execute().use { }
        (System.nanoTime() - start) / 1_000_000
    } catch (_: Exception) {
        null
    }

    private fun privacy() {
        AlertDialog.Builder(this)
            .setTitle("Privacy")
            .setMessage(
                "StreamBox TV is a media player. It contains no channels or content; you add your own provider's login.\n\n" +
                    "• Your logins are stored only on this TV, encrypted with the TV's secure key store, and are excluded from backups.\n" +
                    "• The app has no ads, analytics or tracking.\n" +
                    "• It connects only to your provider, to GitHub to check for updates, and — only if you add their keys — to TMDB and OpenSubtitles.\n" +
                    "• Problem reports are sent only if you choose to, and never include your logins."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun licences() {
        AlertDialog.Builder(this)
            .setTitle("Open-source licences")
            .setMessage(
                "StreamBox TV uses these open-source libraries, all under the Apache License 2.0:\n\n" +
                    "• AndroidX (AppCompat, Core, RecyclerView, Lifecycle) — Google\n" +
                    "• AndroidX Media3 (ExoPlayer) — Google\n" +
                    "• Kotlin & kotlinx.coroutines — JetBrains\n" +
                    "• OkHttp — Square\n" +
                    "• Coil — Coil contributors\n" +
                    "• ZXing — ZXing authors\n\n" +
                    "Full text: apache.org/licenses/LICENSE-2.0"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
