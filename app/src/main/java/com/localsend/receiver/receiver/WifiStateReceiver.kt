package com.localsend.receiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.localsend.receiver.service.LocalSendService

class WifiStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED)
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                val serviceIntent = Intent(context, LocalSendService::class.java)
                serviceIntent.action = LocalSendService.ACTION_START
                context.startService(serviceIntent)
            }
        }
    }
}
