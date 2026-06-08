package com.localsend.receiver

import android.app.Application
import com.localsend.receiver.util.DeviceConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceConfig.init(this)
    }
}
