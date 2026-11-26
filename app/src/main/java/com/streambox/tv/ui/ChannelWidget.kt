package com.streambox.tv.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.streambox.tv.R
import com.streambox.tv.data.Channel
import com.streambox.tv.data.Prefs

/** Home-screen widget with up to six favourite channels: tap one to start watching it. */
class ChannelWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        private val CELLS = intArrayOf(R.id.w0, R.id.w1, R.id.w2, R.id.w3, R.id.w4, R.id.w5)

        /** Remembers the favourites for the widget (it can't read the channel list itself) and redraws it. */
        fun save(context: Context, favs: List<Channel>) {
            val text = favs.take(6).joinToString("\n") { "${it.id}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" }
            if (text == Prefs.widgetChannels) return
            Prefs.widgetChannels = text
            try {
                val m = AppWidgetManager.getInstance(context) ?: return
                val ids = m.getAppWidgetIds(ComponentName(context, ChannelWidget::class.java))
                if (ids.isNotEmpty()) ids.forEach { m.updateAppWidget(it, views(context)) }
            } catch (_: Exception) {
            }
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_channels)
            val list = Prefs.widgetChannels.lines().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t') }
            for (i in CELLS.indices) {
                val cell = CELLS[i]
                val ch = list.getOrNull(i)
                if (ch == null) {
                    v.setTextViewText(cell, if (i == 0 && list.isEmpty()) Tr.s("Add favourite channels in StreamBox") else "")
                    v.setViewVisibility(cell, if (i == 0 && list.isEmpty()) View.VISIBLE else View.INVISIBLE)
                    if (i == 0) v.setOnClickPendingIntent(cell, open(context, null, 99))
                    continue
                }
                v.setViewVisibility(cell, View.VISIBLE)
                v.setTextViewText(cell, ch.second)
                v.setOnClickPendingIntent(cell, open(context, ch.first, i))
            }
            v.setOnClickPendingIntent(R.id.wTitle, open(context, null, 98))
            return v
        }

        private fun open(context: Context, channelId: String?, code: Int): PendingIntent {
            val i = Intent(context, SplashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (channelId != null) i.putExtra(HomeActivity.EXTRA_PLAY_CHANNEL, channelId)
            return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
