package com.streambox.tv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streambox.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Movies and episodes saved for offline viewing. Downloads wait in a queue and use one provider
 * connection at a time; an interrupted download continues where it stopped.
 */
object Downloads {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"

    data class Item(
        val id: String,
        val profileId: String,
        /** Resume key, shared with streaming so Continue watching works both ways. */
        val key: String,
        val title: String,
        val poster: String,
        val url: String,
        val file: String,
        val status: String,
        val total: Long = 0,
        val note: String = "",
        val addedAt: Long = System.currentTimeMillis()
    )

    /** Live progress (bytes) of running downloads, kept in memory. */
    val progress = ConcurrentHashMap<String, Long>()

    fun all(): List<Item> {
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(Prefs.downloadsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Item(o.getString("id"), o.optString("p"), o.optString("k"), o.optString("t"), o.optString("img"),
                    o.optString("u"), o.optString("f"), o.optString("s", QUEUED), o.optLong("tot"), o.optString("n"),
                    o.optLong("at")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("p", it.profileId).put("k", it.key).put("t", it.title)
                .put("img", it.poster).put("u", it.url).put("f", it.file).put("s", it.status).put("tot", it.total)
                .put("n", it.note).put("at", it.addedAt))
        }
        Prefs.downloadsJson = arr.toString()
    }

    @Synchronized
    fun update(id: String, f: (Item) -> Item) = save(all().map { if (it.id == id) f(it) else it })

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun forKey(key: String) = all().firstOrNull { it.key == key && it.profileId == Repo.profileId }

    /** Local file to play instead of streaming, when the download has finished. */
    fun offlineFile(key: String): File? =
        forKey(key)?.takeIf { it.status == DONE }?.let { File(it.file) }?.takeIf { it.exists() && it.length() > 0 }

    fun bytes(item: Item): Long = progress[item.id] ?: File(item.file).length()

    fun dir(context: Context): File {
        val base = Recorder.storageDir(context).parentFile ?: context.filesDir
        return File(base, "Downloads").apply { mkdirs() }
    }

    /** Adds a title to the queue (ignored if it is already downloaded or queued). */
    fun add(context: Context, key: String, title: String, poster: String, url: String): Boolean {
        if (all().any { it.key == key && it.profileId == Repo.profileId && it.status != FAILED }) return false
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").take(5).ifBlank { "mp4" }
        val safe = title.replace(Regex("[^\\p{L}\\p{N} ._-]+"), "_").take(70).trim()
        val file = File(dir(context), "$safe.$ext")
        val item = Item("dl${System.nanoTime()}", Repo.profileId, key, title, poster, url, file.absolutePath, QUEUED)
        save(all().filterNot { it.key == key && it.profileId == Repo.profileId } + item)
        DownloadService.start(context)
        return true
    }

    fun pause(context: Context, id: String) {
        update(id) { it.copy(status = PAUSED) }
        DownloadService.start(context)
    }

    fun resume(context: Context, id: String) {
        update(id) { it.copy(status = QUEUED, note = "") }
        DownloadService.start(context)
    }

    fun delete(context: Context, id: String) {
        val item = get(id) ?: return
        save(all().filterNot { it.id == id })
        progress.remove(id)
        File(item.file).delete()
        DownloadService.start(context)
    }

    /** After a reboot or crash nothing is "running": put it back in the queue. */
    fun recover(context: Context) {
        if (all().any { it.status == RUNNING }) {
            save(all().map { if (it.status == RUNNING) it.copy(status = QUEUED) else it })
        }
        if (all().any { it.status == QUEUED }) DownloadService.start(context)
    }
}

/** Works through the download queue one item at a time. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val client by lazy {
        Net.client.newBuilder().readTimeout(45, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(applicationContext)
        startForegroundCompat()
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        while (scope.isActive) {
            val next = Downloads.all().firstOrNull { it.status == Downloads.QUEUED } ?: break
            download(next)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun download(item: Downloads.Item) {
        Downloads.update(item.id) { it.copy(status = Downloads.RUNNING, note = "") }
        val file = File(item.file).apply { parentFile?.mkdirs() }
        var failures = 0
        var total = item.total
        while (scope.isActive && failures < 8) {
            // Stop if the user paused or deleted it meanwhile.
            val now = Downloads.get(item.id)
            if (now == null || now.status != Downloads.RUNNING) return
            val have = if (file.exists()) file.length() else 0L
            if (total > 0 && have >= total) break
            try {
                val req = Request.Builder().url(item.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 416) return@use // already complete
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("Empty response")
                    val partial = resp.code == 206
                    if (!partial && have > 0) file.delete() // server ignored Range: start over
                    val start = if (partial) have else 0L
                    if (body.contentLength() > 0) total = start + body.contentLength()
                    Downloads.update(item.id) { it.copy(total = total) }
                    RandomAccessFile(file, "rw").use { out ->
                        out.seek(start)
                        val input = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var done = start
                        var lastCheck = System.currentTimeMillis()
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            Downloads.progress[item.id] = done
                            failures = 0
                            if (System.currentTimeMillis() - lastCheck > 2000) {
                                lastCheck = System.currentTimeMillis()
                                updateNotification(item.title, done, total)
                                if (Downloads.get(item.id)?.status != Downloads.RUNNING) return
                            }
                        }
                    }
                }
                break
            } catch (e: Exception) {
                failures++
                Downloads.update(item.id) { it.copy(note = "Retrying: ${e.message}") }
                delay(4000L * failures)
            }
        }
        Downloads.progress.remove(item.id)
        val size = file.length()
        val ok = size > 0 && (total <= 0 || size >= total)
        Downloads.update(item.id) {
            if (it.status != Downloads.RUNNING) it
            else it.copy(status = if (ok) Downloads.DONE else Downloads.FAILED, total = if (ok) size else total,
                note = if (ok) "" else it.note.ifBlank { "Download failed" })
        }
    }

    private fun notification(text: String, done: Long = 0, total: Long = 0): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setOngoing(true)
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false)
        return b.build()
    }

    private fun startForegroundCompat() {
        val n = notification("Preparing…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun updateNotification(title: String, done: Long, total: Long) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(title, done, total))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 7702

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
