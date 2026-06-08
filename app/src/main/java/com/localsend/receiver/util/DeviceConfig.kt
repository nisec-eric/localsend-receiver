package com.localsend.receiver.util

import android.content.Context
import java.util.UUID

object DeviceConfig {

    lateinit var alias: String
        private set
    lateinit var fingerprint: String
        private set
    const val version: String = "2.1"
    const val port: Int = 53317
    const val protocol: String = "http"
    const val deviceModel: String = "Android"
    const val deviceType: String = "HEADLESS"

    fun init(context: Context) {
        alias = loadAlias(context)
        fingerprint = loadFingerprint(context)
    }

    private fun loadAlias(context: Context): String {
        val prefs = context.getSharedPreferences("localsend", Context.MODE_PRIVATE)
        var name = prefs.getString("alias", null)
        if (name == null) {
            val deviceName = android.os.Build.MODEL ?: "Device"
            name = "Car-$deviceName"
            prefs.edit().putString("alias", name).apply()
        }
        return name
    }

    private fun loadFingerprint(context: Context): String {
        val prefs = context.getSharedPreferences("localsend", Context.MODE_PRIVATE)
        var fp = prefs.getString("fingerprint", null)
        if (fp == null) {
            fp = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("fingerprint", fp).apply()
        }
        return fp
    }

    fun registerResponseJson(): Map<String, Any?> {
        return mapOf(
            "alias" to alias,
            "version" to version,
            "deviceModel" to deviceModel,
            "deviceType" to deviceType,
            "fingerprint" to fingerprint,
            "download" to false
        )
    }
}
