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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** Folders downloads can go to: TV storage first, then any USB drive / SD card. */
    fun dirs(context: Context): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "Downloads") }

    /** Where new downloads are saved (Settings → Download location). Existing ones stay where they are. */
    fun dir(context: Context): File {
        val all = dirs(context)
        val d = all.getOrNull(Prefs.downloadStorage) ?: all.firstOrNull() ?: File(context.filesDir, "Downloads")
        return d.apply { mkdirs() }
    }

    /** Free space on the drive holding [f], for labels like "120 GB free". */
    fun freeSpace(f: File): String = try {
        val b = android.os.StatFs((f.parentFile ?: f).absolutePath).availableBytes
        if (b >= 1_073_741_824L) String.format(java.util.Locale.US, "%.0f GB free", b / 1_073_741_824.0)
        else String.format(java.util.Locale.US, "%.0f MB free", b / 1_048_576.0)
    } catch (_: Exception) {
        ""
    }

    /** Adds a title to the queue (ignored if it is already downloaded or queued). */
    fun add(context: Context, key: String, title: String, poster: String, url: String): Boolean {
        if (all().any { it.key == key && it.profileId == Repo.profileId && it.status != FAILED }) return false
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").take(5).lowercase()
            .let { if (it.isBlank() || it in setOf("m3u8", "m3u", "php", "mpd")) "ts" else it }
        val safe = title.ifBlank { "Video" }.replace(Regex("[^\\p{L}\\p{N} ._-]+"), "_").take(70).trim()
        val file = File(dir(context), "$safe.$ext")
        val item = Item("dl${System.nanoTime()}", Repo.profileId, key, title.ifBlank { file.nameWithoutExtension }, poster, url, file.absolutePath, QUEUED)
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
        File(item.file + ".hls").delete()
        DownloadService.start(context)
    }

    /**
     * Checks that a finished file is really a video. IPTV servers often answer with a short text
     * ("max connections reached", an HTML error page) or an HLS playlist instead of the film.
     * Returns why the file is unusable, or null when it looks fine.
     */
    fun badFileReason(f: File): String? {
        if (!f.exists() || f.length() == 0L) return "The server sent nothing"
        val head = ByteArray(minOf(f.length(), 1024L).toInt())
        try {
            f.inputStream().use { var off = 0; while (off < head.size) { val n = it.read(head, off, head.size - off); if (n < 0) break; off += n } }
        } catch (_: Exception) {
            return "The file can't be read"
        }
        val txt = String(head, Charsets.ISO_8859_1)
        if (txt.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U"))
            return "The download didn't finish. Choose Try again"
        val printable = head.count { b -> val c = b.toInt() and 0xff; c == 9 || c == 10 || c == 13 || c in 32..126 }
        if (f.length() < 2_000_000 && printable > head.size * 0.9) {
            val msg = txt.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            return "Your provider refused" + (if (msg.isNotEmpty()) " (\"$msg\")" else "") +
                ". It may allow only one connection: stop watching, then choose Try again"
        }
        if (f.length() < 1_000_000) return "The server sent an incomplete file. Choose Try again"
        return null
    }

    /** After a reboot or crash nothing is "running": put it back in the queue. */
    fun recover(context: Context) {
        // Earlier builds could mark a server error reply as "downloaded": flag those so they can be retried.
        val broken = all().filter { it.status == DONE && badFileReason(File(it.file)) != null }
        if (broken.isNotEmpty()) save(all().map { d ->
            if (broken.any { it.id == d.id }) {
                val why = badFileReason(File(d.file)) ?: ""
                File(d.file).delete()
                d.copy(status = FAILED, total = 0, note = why)
            } else d
        })
        if (all().any { it.status == RUNNING }) {
            save(all().map { if (it.status == RUNNING) it.copy(status = QUEUED) else it })
        }
        val hls = all().filter { it.status == FAILED && it.note.startsWith("Your server sends this title as a live stream playlist") }
        if (hls.isNotEmpty()) save(all().map { d -> if (hls.any { it.id == d.id }) d.copy(status = QUEUED, note = "", total = 0) else d })
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
        if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private suspend fun download(item: Downloads.Item) {
        Downloads.update(item.id) { it.copy(status = Downloads.RUNNING, note = "") }
        val file = File(item.file).apply { parentFile?.mkdirs() }
        // A paused HLS download carries on from its last piece.
        if (File(file.path + ".hls").exists()) {
            finishHls(item, file, null, item.url)
            return
        }
        var failures = 0
        var total = item.total
        var finalUrl = item.url
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
                    finalUrl = resp.request.url.toString()
                    if (resp.code in listOf(401, 403, 429, 456, 458, 509, 512, 513)) {
                        failures = 99 // retrying straight away won't help
                        throw java.io.IOException("Your provider refused (HTTP ${resp.code}). It may allow only one connection: stop watching, then choose Try again")
                    }
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
                val msg = e.message ?: "network error"
                Downloads.update(item.id) { it.copy(note = if (failures >= 8) msg else "Retrying: $msg") }
                if (failures < 8) delay(4000L * failures)
            }
        }
        // Many providers serve films and episodes as HLS: a playlist of short video pieces.
        if (file.exists() && file.length() in 1..2_000_000 && isPlaylist(file)) {
            val text = file.readText()
            file.delete()
            finishHls(item, file, text, finalUrl)
            return
        }
        Downloads.progress.remove(item.id)
        val size = file.length()
        val complete = size > 0 && (total <= 0 || size >= total)
        val bad = if (complete) Downloads.badFileReason(file) else null
        val ok = complete && bad == null
        if (bad != null) file.delete()
        Downloads.update(item.id) {
            if (it.status != Downloads.RUNNING) it
            else it.copy(status = if (ok) Downloads.DONE else Downloads.FAILED, total = if (ok) size else 0,
                note = if (ok) "" else bad ?: it.note.removePrefix("Retrying: ").ifBlank { "Download failed" })
        }
    }

    private fun isPlaylist(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(64); val n = it.read(b); n > 0 && String(b, 0, n, Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U") }
    } catch (_: Exception) {
        false
    }

    private class Seg(val url: String, val keyUrl: String?, val iv: ByteArray?, val seq: Long)

    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    private fun resolve(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref.trim())?.toString() ?: ref.trim()

    private fun fetch(url: String): okhttp3.Response {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw java.io.IOException(
                if (code in listOf(401, 403, 429, 456, 458, 509, 512, 513))
                    "Your provider refused (HTTP $code). It may allow only one connection: stop watching, then choose Try again"
                else "HTTP $code"
            )
        }
        return resp
    }

    private fun fetchText(url: String): Pair<String, String> =
        fetch(url).use { (it.body?.string() ?: "") to it.request.url.toString() }

    /**
     * Downloads an HLS title: picks the best quality, fetches every piece in order (decrypting
     * AES-128 if used) and joins them into one file that plays offline. Progress is kept in
     * "<file>.hls" so Pause / a reboot continue from the last piece.
     */
    private suspend fun finishHls(item: Downloads.Item, file: File, firstText: String?, firstUrl: String) {
        val side = File(file.path + ".hls")
        try {
            var (text, url) = if (firstText != null) firstText to firstUrl else fetchText(item.url)
            // Master playlist: choose the highest-quality variant.
            if (text.contains("#EXT-X-STREAM-INF")) {
                val lines = text.lines()
                var best = -1L
                var bestUrl = ""
                for (i in lines.indices) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                    val bw = attrs(lines[i])["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                    if (bw > best) { best = bw; bestUrl = resolve(url, next) }
                }
                if (bestUrl.isEmpty()) throw java.io.IOException("No video in the playlist")
                val r = fetchText(bestUrl)
                text = r.first
                url = r.second
            }
            if (!text.contains("#EXT-X-ENDLIST") && !text.contains("#EXTINF"))
                throw java.io.IOException("This title is a live stream and can't be saved")
            // Media playlist.
            val segs = ArrayList<Seg>()
            var seq = 0L
            var keyUrl: String? = null
            var iv: ByteArray? = null
            var mapUrl: String? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = attrs(line)
                        when (a["METHOD"]) {
                            "NONE", null -> { keyUrl = null; iv = null }
                            "AES-128" -> {
                                keyUrl = a["URI"]?.let { resolve(url, it) }
                                iv = a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let { h ->
                                    ByteArray(16) { i -> h.padStart(32, '0').substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                                }
                            }
                            else -> throw java.io.IOException("This title is copy-protected and can't be saved")
                        }
                    }
                    line.startsWith("#EXT-X-MAP:") -> mapUrl = attrs(line)["URI"]?.let { resolve(url, it) }
                    line.isNotEmpty() && !line.startsWith("#") -> segs.add(Seg(resolve(url, line), keyUrl, iv, seq++))
                }
            }
            if (segs.isEmpty()) throw java.io.IOException("The playlist is empty")

            // Where we stopped last time: "<next piece> <bytes written>".
            var index = 0
            if (side.exists() && file.exists()) {
                val parts = side.readText().trim().split(' ')
                index = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val len = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                RandomAccessFile(file, "rw").use { it.setLength(len) }
            } else {
                file.delete()
                side.writeText("0 0")
            }
            val keys = HashMap<String, ByteArray>()
            java.io.FileOutputStream(file, true).use { out ->
                if (index == 0 && mapUrl != null) fetch(mapUrl!!).use { r -> r.body?.byteStream()?.copyTo(out) }
                while (index < segs.size) {
                    val now = Downloads.get(item.id)
                    if (now == null || now.status != Downloads.RUNNING) return // paused or deleted: progress is kept
                    val s = segs[index]
                    var data: ByteArray? = null
                    var tries = 0
                    while (data == null) {
                        try {
                            data = fetch(s.url).use { it.body?.bytes() ?: ByteArray(0) }
                        } catch (e: Exception) {
                            if (++tries >= 6) throw e
                            Downloads.update(item.id) { it.copy(note = "Retrying: ${e.message}") }
                            delay(3000L * tries)
                        }
                    }
                    var bytes: ByteArray = data!!
                    val ku = s.keyUrl
                    if (ku != null) {
                        val k = keys.getOrPut(ku) { fetch(ku).use { it.body?.bytes() ?: ByteArray(0) } }
                        val ivBytes = s.iv ?: ByteArray(16) { i -> if (i < 8) 0 else (s.seq shr (8 * (15 - i))).toByte() }
                        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(k, "AES"), javax.crypto.spec.IvParameterSpec(ivBytes))
                        bytes = c.doFinal(bytes)
                    }
                    out.write(bytes)
                    out.flush()
                    index++
                    val len = file.length()
                    side.writeText("$index $len")
                    Downloads.progress[item.id] = len
                    val estimate = len * segs.size / index
                    Downloads.update(item.id) { it.copy(total = estimate, note = "") }
                    updateNotification(item.title, len, estimate)
                }
            }
            side.delete()
            Downloads.progress.remove(item.id)
            val size = file.length()
            val bad = if (size < 1_000_000) "The server sent an incomplete file. Choose Try again" else null
            if (bad != null) file.delete()
            Downloads.update(item.id) {
                if (it.status != Downloads.RUNNING) it
                else it.copy(status = if (bad == null) Downloads.DONE else Downloads.FAILED,
                    total = if (bad == null) size else 0, note = bad ?: "")
            }
        } catch (e: Exception) {
            Downloads.progress.remove(item.id)
            Downloads.update(item.id) {
                if (it.status != Downloads.RUNNING) it
                else it.copy(status = Downloads.FAILED, note = e.message ?: "Download failed")
            }
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
