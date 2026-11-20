package com.streambox.tv.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Records live channels to the TV's storage (or a USB drive) as .ts files.
 * Scheduled recordings start from an alarm; a foreground service does the download.
 */
object Recorder {
    const val SCHEDULED = "scheduled"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"

    data class Recording(
        val id: String,
        val profileId: String,
        val channelId: String,
        val channelName: String,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val url: String,
        val file: String,
        val status: String,
        val note: String = ""
    )

    fun all(): List<Recording> {
        val out = ArrayList<Recording>()
        try {
            val arr = JSONArray(Prefs.recordingsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Recording(o.getString("id"), o.optString("p"), o.optString("c"), o.optString("cn"),
                    o.optString("t"), o.getLong("s"), o.getLong("e"), o.optString("u"), o.optString("f"),
                    o.optString("st", SCHEDULED), o.optString("n")))
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.startMs }
    }

    @Synchronized
    private fun save(list: List<Recording>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("p", it.profileId).put("c", it.channelId).put("cn", it.channelName)
                .put("t", it.title).put("s", it.startMs).put("e", it.endMs).put("u", it.url).put("f", it.file)
                .put("st", it.status).put("n", it.note))
        }
        Prefs.recordingsJson = arr.toString()
    }

    @Synchronized
    fun update(id: String, f: (Recording) -> Recording) {
        save(all().map { if (it.id == id) f(it) else it })
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    /** Folders the app may write to: internal storage first, then any USB drive / SD card. */
    fun storageDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "Recordings") }

    fun storageDir(context: Context): File {
        val dirs = storageDirs(context)
        val dir = dirs.getOrNull(Prefs.recordingStorage) ?: dirs.firstOrNull() ?: File(context.filesDir, "Recordings")
        dir.mkdirs()
        return dir
    }

    /** Recording needs a direct MPEG-TS stream, so Xtream channels are always recorded as .ts. */
    private fun recordUrl(c: Channel): String {
        val p = Repo.profile ?: return ""
        return if (c.url.isNotEmpty()) c.url else "${p.server}/live/${p.username}/${p.password}/${c.id}.ts"
    }

    fun schedule(context: Context, c: Channel, title: String, startMs: Long, endMs: Long): Recording {
        val safe = (title.ifBlank { c.name }).replace(Regex("[^\\p{L}\\p{N} ._-]+"), "_").take(60)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(startMs))
        val file = File(storageDir(context), "${stamp}_${safe}.ts")
        val r = Recording("rec${System.currentTimeMillis()}", Repo.profileId, c.id, c.name, title.ifBlank { c.name },
            startMs, endMs, recordUrl(c), file.absolutePath, SCHEDULED)
        save(all() + r)
        arm(context, r)
        return r
    }

    fun delete(context: Context, r: Recording) {
        disarm(context, r)
        if (r.status == RECORDING) RecordingService.stop(context, r.id)
        File(r.file).delete()
        save(all().filterNot { it.id == r.id })
    }

    private fun pending(context: Context, r: Recording): PendingIntent =
        PendingIntent.getBroadcast(context, r.id.hashCode(),
            Intent(context, RecordReceiver::class.java).putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(context: Context, r: Recording) {
        if (r.startMs <= System.currentTimeMillis() + 5_000L) {
            RecordingService.start(context, r.id)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, r.startMs - 30_000L, 30_000L, pending(context, r))
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.startMs - 15_000L, pending(context, r))
            }
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, r.startMs - 30_000L, pending(context, r))
        }
    }

    private fun disarm(context: Context, r: Recording) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, r))
    }

    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (r in all()) {
            when {
                r.status == SCHEDULED && r.endMs > now -> arm(context, r)
                r.status == SCHEDULED || r.status == RECORDING -> if (r.endMs <= now) update(r.id) {
                    it.copy(status = if (File(it.file).length() > 0) DONE else FAILED, note = "Interrupted")
                }
            }
        }
    }
}

class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context.applicationContext)
        RecordingService.start(context, intent.getStringExtra("id") ?: return)
    }
}

/** Foreground service that downloads one or more live streams until their end time. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val client by lazy {
        Net.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(applicationContext)
        startForegroundCompat()
        val id = intent?.getStringExtra("id")
        if (intent?.action == ACTION_STOP && id != null) {
            jobs.remove(id)?.cancel()
            finishIfIdle()
            return START_NOT_STICKY
        }
        if (id != null && !jobs.containsKey(id)) {
            val r = Recorder.get(id)
            if (r != null && r.endMs > System.currentTimeMillis()) jobs[id] = scope.launch { record(r) }
        }
        finishIfIdle()
        return START_NOT_STICKY
    }

    private suspend fun record(r: Recorder.Recording) {
        Recorder.update(r.id) { it.copy(status = Recorder.RECORDING, note = "") }
        updateNotification()
        val wait = r.startMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val file = File(r.file)
        file.parentFile?.mkdirs()
        var failures = 0
        try {
            FileOutputStream(file, true).use { out ->
                val buf = ByteArray(128 * 1024)
                while (scope.isActive && System.currentTimeMillis() < r.endMs && failures < 30) {
                    try {
                        client.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                            val input = resp.body?.byteStream() ?: throw java.io.IOException("Empty stream")
                            while (System.currentTimeMillis() < r.endMs) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                failures = 0
                            }
                        }
                    } catch (e: Exception) {
                        failures++
                        Recorder.update(r.id) { it.copy(note = "Reconnecting: ${e.message}") }
                        delay(3_000)
                    }
                }
            }
        } catch (e: Exception) {
            Recorder.update(r.id) { it.copy(note = e.message ?: "Write error") }
        }
        val ok = file.length() > 100_000
        Recorder.update(r.id) {
            it.copy(status = if (ok) Recorder.DONE else Recorder.FAILED,
                note = if (ok) "" else it.note.ifBlank { "No data received" })
        }
        jobs.remove(r.id)
        updateNotification()
        finishIfIdle()
    }

    private fun finishIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
        }
        val active = Recorder.all().filter { it.status == Recorder.RECORDING }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(if (active.size > 1) "Recording ${active.size} programmes" else "Recording")
            .setContentText(active.joinToString { "${it.title} (${it.channelName})" }.ifBlank { "Preparing…" })
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 7701
        private const val ACTION_STOP = "stop"

        fun start(context: Context, id: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).putExtra("id", id))
            } catch (e: Exception) {
                Recorder.update(id) { it.copy(status = Recorder.FAILED, note = "Android blocked the recording from starting: ${e.message}") }
            }
        }

        fun stop(context: Context, id: String) {
            try {
                context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra("id", id))
            } catch (_: Exception) {
            }
        }
    }
}
