package com.streambox.tv

import android.app.Application
import com.streambox.tv.data.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
