package com.scrollcounter.app

import android.app.Application
import com.scrollcounter.app.data.PreferencesManager

class ScrollCounterApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)
    }

    companion object {
        lateinit var instance: ScrollCounterApp
            private set
    }
}
