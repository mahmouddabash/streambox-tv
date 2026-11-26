package com.streambox.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Pause live TV on any channel: while paused, StreamBox keeps receiving the channel into a file on
 * the device (one connection, so it works with a 1-connection subscription), and Resume plays it
 * from that file, behind live. Kept for up to 3 hours; deleted when you go back to live or leave.
 */
object LocalShift {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val client by lazy { Net.client.newBuilder().cache(null).readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build() }

    @Volatile var file: File? = null
        private set
    @Volatile var receiving = false
        private set
    @Volatile var startedAt = 0L
        private set

    val active get() = file != null

    fun start(context: Context, url: String): File {
        stop()
        val f = File(context.cacheDir, "live_pause.ts")
        f.delete()
        file = f
        receiving = true
        startedAt = System.currentTimeMillis()
        val app = context.applicationContext
        job = scope.launch {
            var failures = 0
            val buf = ByteArray(128 * 1024)
            try {
                FileOutputStream(f, true).use { out ->
                    while (isActive && receiving && failures < 15) {
                        try {
                            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                                val ins = r.body?.byteStream() ?: throw java.io.IOException("empty")
                                while (isActive && receiving) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    Prefs.addDataUsed(n.toLong(), Net.isMobileData(app))
                                    failures = 0
                                    if (System.currentTimeMillis() - startedAt > 3 * 3_600_000L) receiving = false
                                }
                            }
                        } catch (_: Exception) {
                            failures++
                            delay(2000)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            receiving = false
        }
        return f
    }

    fun stop() {
        receiving = false
        job?.cancel()
        job = null
        file?.delete()
        file = null
    }
}
