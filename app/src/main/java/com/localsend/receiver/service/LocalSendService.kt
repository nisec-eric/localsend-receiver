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
        const val NOTIFICATION_CHANNEL_ID = "localsend_service"
        const val NOTIFICATION_ID = 1
    }

    private var httpServer: LocalSendHttpServer? = null
    private var multicastService: MulticastDiscoveryService? = null
    private var activityRef: WeakReference<MainActivity>? = null

    private val eventListener = object : ServerEventListener {
        override fun onDeviceDiscovered(device: RegisterDto, ip: String) {
            broadcastStatus("Device discovered", "${device.alias} ($ip)")
        }

        override fun onPrepareUploadReceived(
            senderInfo: RegisterDto,
            files: Map<String, FileDto>
        ): Boolean {
            val activity = activityRef?.get()
            if (activity != null) {
                return activity.showAcceptDialog(senderInfo.alias, files)
            }
            // No activity attached — auto-reject for safety
            broadcastStatus("Transfer rejected", "No UI available")
            return false
        }

        override fun onFileReceived(
            sessionId: String, fileId: String,
            fileName: String, size: Long, savedPath: String
        ) {
            broadcastStatus("File received", "$fileName (${formatSize(size)})")
            activityRef?.get()?.addReceivedFile(fileName, size, savedPath)
        }

        override fun onFileReceiveProgress(
            sessionId: String, fileId: String,
            bytesReceived: Long, totalBytes: Long
        ) {
            val percent = (bytesReceived * 100 / totalBytes).toInt()
            activityRef?.get()?.updateStatus("Receiving: $percent%")
        }

        override fun onTransferCancelled(sessionId: String) {
            broadcastStatus("Transfer cancelled", sessionId)
        }

        override fun onError(sessionId: String?, message: String) {
            broadcastStatus("Error", message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAll()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }

    private fun startAll() {
        Log.i(TAG, "Starting LocalSend service...")

        if (httpServer != null || multicastService != null) {
            Log.i(TAG, "Stopping previous instance before restart")
            stopAll()
        }

        val ip = NetworkUtils.getLocalIpAddress(this)
        activityRef?.get()?.updateIpAddress(ip)

        if (ip == null) {
            broadcastStatus("No WiFi", "Waiting for WiFi connection...")
            return
        }

        startForegroundNotification()

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

        activityRef?.get()?.updateStatus("Ready - $ip:${DeviceConfig.port}")
    }

    private fun stopAll() {
        Log.i(TAG, "Stopping LocalSend service...")
        multicastService?.stop()
        multicastService = null
        httpServer?.stop()
        httpServer = null
        @Suppress("DEPRECATION")
        stopForeground(true)
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
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("LocalSend Receiver")
            .setContentText("Listening on port ${DeviceConfig.port}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
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
