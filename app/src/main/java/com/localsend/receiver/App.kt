package com.localsend.receiver

import androidx.multidex.MultiDexApplication
import com.localsend.receiver.util.DeviceConfig

class App : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        DeviceConfig.init(this)
    }
}
