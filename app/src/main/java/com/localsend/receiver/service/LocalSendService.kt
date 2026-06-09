package com.localsend.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.localsend.receiver.ui.MainActivity
import com.localsend.receiver.network.LocalSendHttpServer
import com.localsend.receiver.network.MulticastDiscoveryService
import com.localsend.receiver.network.ServerEventListener
import com.localsend.receiver.network.dto.FileDto
import com.localsend.receiver.network.dto.RegisterDto
import com.localsend.receiver.util.DeviceConfig
import com.localsend.receiver.util.NetworkUtils
import java.lang.ref.WeakReference

class LocalSendService : Service() {

    companion object {
        private const val TAG = "LocalSendService"
        const val ACTION_START = "com.localsend.receiver.START"
        const val ACTION_STOP = "com.localsend.receiver.STOP"
        const val ACTION_STATUS_UPDATE = "com.localsend.receiver.STATUS_UPDATE"
        private const val NOTIFICATION_CHANNEL_ID = "localsend_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: LocalSendService? = null

        private var pendingActivity: WeakReference<MainActivity>? = null

        @JvmStatic
        fun registerActivity(activity: MainActivity) {
            pendingActivity = WeakReference(activity)
            instance?.attachActivity(activity)
        }

        @JvmStatic
        fun unregisterActivity() {
            pendingActivity?.clear()
            pendingActivity = null
            instance?.detachActivity()
        }
    }

    private var httpServer: LocalSendHttpServer? = null
    private var multicastService: MulticastDiscoveryService? = null
    private var activityRef: WeakReference<MainActivity>? = null
    private var isForegroundStarted = false

    private val eventListener = object : ServerEventListener {
        override fun onDeviceDiscovered(device: RegisterDto, ip: String) {
            broadcastStatus("Device discovered", "${device.alias} ($ip)")
        }

        override fun onPrepareUploadReceived(
            senderInfo: RegisterDto,
            files: Map<String, FileDto>
        ): Boolean {
            val activity = activityRef?.get()
            if (activity != null && !activity.isFinishing) {
                return activity.showAcceptDialog(senderInfo.alias, files)
            }
            broadcastStatus("Transfer rejected", "No UI available")
            return false
        }

        override fun onFileReceived(
            sessionId: String, fileId: String,
            fileName: String, size: Long, savedPath: String
        ) {
            broadcastStatus("File received", "$fileName (${formatSize(size)})")
            val activity = activityRef?.get()
            if (activity != null && !activity.isFinishing) {
                activity.addReceivedFile(fileName, size, savedPath)
                val ip = NetworkUtils.getLocalIpAddress(this@LocalSendService)
                if (ip != null) {
                    activity.updateStatus("Ready - $ip:${DeviceConfig.port}")
                }
            }
        }

        override fun onFileReceiveProgress(
            sessionId: String, fileId: String,
            bytesReceived: Long, totalBytes: Long
        ) {
            val percent = (bytesReceived * 100 / totalBytes).toInt()
            val activity = activityRef?.get()
            if (activity != null && !activity.isFinishing) {
                activity.updateStatus("Receiving: $percent%")
            }
        }

        override fun onTransferCancelled(sessionId: String) {
            broadcastStatus("Transfer cancelled", sessionId)
        }

        override fun onError(sessionId: String?, message: String) {
            broadcastStatus("Error", message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        pendingActivity?.get()?.let { attachActivity(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAll()
            ACTION_STOP -> {
                stopAll()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAll()
        instance = null
        detachActivity()
        super.onDestroy()
    }

    private fun startAll() {
        Log.i(TAG, "Starting LocalSend service...")

        if (httpServer != null || multicastService != null) {
            Log.i(TAG, "Stopping previous instance before restart")
            stopServers()
        }

        startForegroundNotification()

        val ip = NetworkUtils.getLocalIpAddress(this)
        val activity = activityRef?.get()
        if (activity != null && !activity.isFinishing) {
            activity.updateIpAddress(ip)
        }

        if (ip == null) {
            broadcastStatus("No WiFi", "Waiting for WiFi connection...")
            return
        }

        try {
            httpServer = LocalSendHttpServer(DeviceConfig.port, eventListener)
            httpServer!!.start(5000, true)
            broadcastStatus("HTTP server started", "Port: ${DeviceConfig.port}, IP: $ip")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            broadcastStatus("HTTP server failed", e.message)
        }

        try {
            multicastService = MulticastDiscoveryService(this)
            multicastService!!.start()
            broadcastStatus("Discovery started", "Multicast 224.0.0.167:${DeviceConfig.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start multicast discovery", e)
            broadcastStatus("Discovery failed", e.message)
        }

        if (activity != null && !activity.isFinishing) {
            activity.updateStatus("Ready - $ip:${DeviceConfig.port}")
        }
    }

    private fun stopServers() {
        multicastService?.stop()
        multicastService = null
        httpServer?.stop()
        httpServer = null
    }

    private fun stopAll() {
        Log.i(TAG, "Stopping LocalSend service...")
        stopServers()
        if (isForegroundStarted) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            isForegroundStarted = false
        }
        broadcastStatus("Service stopped", null)
    }

    fun attachActivity(activity: MainActivity) {
        activityRef = WeakReference(activity)
        val ip = NetworkUtils.getLocalIpAddress(this)
        activity.updateIpAddress(ip)
        if (httpServer != null) {
            activity.updateStatus("Ready - $ip:${DeviceConfig.port}")
        }
    }

    fun detachActivity() {
        activityRef?.clear()
        activityRef = null
    }

    @Suppress("DEPRECATION")
    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "LocalSend Service",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("LocalSend Receiver")
            .setContentText("Listening on port ${DeviceConfig.port}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        isForegroundStarted = true
    }

    private fun broadcastStatus(status: String, details: String?) {
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra("status", status)
        intent.putExtra("details", details)
        sendBroadcast(intent)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
