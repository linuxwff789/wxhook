package com.nous.wxhook

import android.app.Application

class App : Application() {
    override fun onCreate() {
        android.util.Log.e("wxhook:TEST", "0-App.onCreate")
        super.onCreate()
        instance = this
        android.util.Log.e("wxhook:TEST", "0-App.onCreate done")
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
