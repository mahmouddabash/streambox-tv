package com.streambox.tv

import android.app.Application
import com.streambox.tv.data.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        com.streambox.tv.data.Reminders.init(this)
        com.streambox.tv.ui.Tr.init(this)
    }
}
