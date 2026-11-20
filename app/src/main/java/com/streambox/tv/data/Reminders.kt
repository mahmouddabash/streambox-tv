package com.streambox.tv.data

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.streambox.tv.R
import com.streambox.tv.ui.SplashActivity
import com.streambox.tv.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/** "Remind me" for upcoming programmes: an alarm + notification, and a pop-up with Watch now while the app is open. */
object Reminders {
    data class Reminder(
        val id: String,
        val profileId: String,
        val channelId: String,
        val channelName: String,
        val title: String,
        val startMs: Long
    )

    private const val CHANNEL = "reminders"
    private val handler = Handler(Looper.getMainLooper())
    private var top: WeakReference<Activity>? = null
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a); check() }
            override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        tick()
        rescheduleAll(application)
    }

    private fun tick() {
        check()
        handler.postDelayed({ tick() }, 15_000)
    }

    fun all(): List<Reminder> {
        val out = ArrayList<Reminder>()
        try {
            val arr = JSONArray(Prefs.remindersJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Reminder(o.getString("id"), o.getString("p"), o.getString("c"), o.optString("cn"), o.optString("t"), o.getLong("s")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("p", it.profileId).put("c", it.channelId)
                .put("cn", it.channelName).put("t", it.title).put("s", it.startMs))
        }
        Prefs.remindersJson = arr.toString()
    }

    private fun idFor(channelId: String, startMs: Long) = "$channelId@$startMs"

    fun has(channelId: String, startMs: Long) = all().any { it.id == idFor(channelId, startMs) }

    /** Adds the reminder, or removes it if it already exists. Returns true when now set. */
    fun toggle(context: Context, c: Channel, p: Programme): Boolean {
        val id = idFor(c.id, p.startMs)
        val list = all().toMutableList()
        val existing = list.firstOrNull { it.id == id }
        return if (existing != null) {
            list.remove(existing)
            save(list)
            cancelAlarm(context, existing)
            false
        } else {
            val r = Reminder(id, Repo.profileId, c.id, c.name, p.title, p.startMs)
            list.add(r)
            save(list)
            schedule(context, r)
            true
        }
    }

    private fun pending(context: Context, r: Reminder): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).putExtra("id", r.id)
        return PendingIntent.getBroadcast(context, r.id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = r.startMs - 60_000L
        if (at < System.currentTimeMillis()) return
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pending(context, r))
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, r))
            }
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, at, pending(context, r))
        }
    }

    private fun cancelAlarm(context: Context, r: Reminder) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, r))
    }

    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val list = all().filter { it.startMs > now - 3_600_000L }
        save(list)
        list.forEach { schedule(context, it) }
    }

    /** Shows due reminders over whatever screen is open. */
    private fun check() {
        val a = top?.get() ?: return
        if (a.isFinishing) return
        val now = System.currentTimeMillis()
        val due = all().filter { it.startMs - 60_000L <= now && it.startMs + 30 * 60_000L > now }
        if (due.isEmpty()) return
        save(all().filterNot { r -> due.any { it.id == r.id } })
        val r = due.first()
        AlertDialog.Builder(a)
            .setTitle("⏰  Starting now")
            .setMessage("${r.title}\non ${r.channelName}")
            .setPositiveButton("Watch now") { _, _ -> watch(a, r) }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun watch(a: Activity, r: Reminder) {
        val c = Repo.channel(r.channelId)
        if (!Repo.loaded || Repo.profileId != r.profileId || c == null) {
            android.widget.Toast.makeText(a, "Open the profile with ${r.channelName} first", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Ui.play(a, listOf(PlayItem("", c.name, Repo.urlFor(c), true, c.id)), 0)
    }

    /** Notification for when the app is not on screen. */
    fun notify(context: Context, id: String) {
        val r = all().firstOrNull { it.id == id } ?: return
        if (top?.get() != null) {
            handler.post { check() }
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Programme reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, SplashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Starting now: ${r.title}")
            .setContentText("on ${r.channelName}. Open StreamBox to watch")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.notify(context, intent.getStringExtra("id") ?: return)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Prefs.init(context.applicationContext)
            Reminders.rescheduleAll(context)
            Recorder.rescheduleAll(context)
        }
    }
}
