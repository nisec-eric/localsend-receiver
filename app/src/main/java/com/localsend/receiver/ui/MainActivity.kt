package com.localsend.receiver.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localsend.receiver.R
import com.localsend.receiver.network.dto.FileDto
import com.localsend.receiver.service.LocalSendService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val MAX_LOG_ENTRIES = 50
        private const val MAX_HISTORY_ENTRIES = 100
    }

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var logScrollView: ScrollView
    private lateinit var historyContainer: LinearLayout

    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Suppress("DEPRECATION")
    private fun getColorCompat(id: Int): Int {
        return if (Build.VERSION.SDK_INT >= 23) getColor(id) else resources.getColor(id)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocalSendService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra("status") ?: return
                val details = intent.getStringExtra("details")
                runOnUiThread { addLogEntry(status, details) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        logContainer = findViewById(R.id.logContainer)
        logScrollView = findViewById(R.id.logScrollView)
        historyContainer = findViewById(R.id.historyContainer)

        requestRequiredPermissions()
        ensureDownloadDirectory()
        addLogEntry("App started", "Waiting for WiFi connection...")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LocalSendService.ACTION_STATUS_UPDATE)
        try {
            registerReceiver(statusReceiver, filter)
        } catch (_: Exception) {}
        startLocalSendService()
        LocalSendService.registerActivity(this)
    }

    override fun onStop() {
        LocalSendService.unregisterActivity()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val intent = Intent(this, LocalSendService::class.java)
        intent.action = LocalSendService.ACTION_STOP
        startService(intent)
        finish()
    }

    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
            }
        }
    }

    private fun ensureDownloadDirectory() {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalSend")
            if (!dir.exists()) dir.mkdirs()
            if (!dir.canWrite()) {
                addLogEntry("Warning", "Cannot write to download directory")
            }
        } catch (e: Exception) {
            addLogEntry("Error", "Cannot create download directory: ${e.message}")
        }
    }

    private fun startLocalSendService() {
        val intent = Intent(this, LocalSendService::class.java)
        intent.action = LocalSendService.ACTION_START
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = getString(R.string.status_waiting)
    }

    fun updateIpAddress(ip: String?) {
        runOnUiThread {
            if (ip != null) {
                ipText.text = getString(R.string.device_ip, ip)
                ipText.setTextColor(getColorCompat(R.color.success))
            } else {
                ipText.text = getString(R.string.no_wifi)
                ipText.setTextColor(getColorCompat(R.color.error))
            }
        }
    }

    fun updateStatus(status: String) {
        runOnUiThread { statusText.text = status }
    }

    fun showAcceptDialog(senderAlias: String, files: Map<String, FileDto>): Boolean {
        if (isFinishing) return false

        val accepted = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        runOnUiThread {
            if (isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }

            val fileCount = files.size
            val totalSize = files.values.sumOf { it.size }
            val fileNames = files.values.take(3).joinToString(", ") { it.fileName }
            val moreText = if (fileCount > 3) " and ${fileCount - 3} more" else ""

            val title = if (fileCount == 1) getString(R.string.incoming_file)
                        else getString(R.string.incoming_files, fileCount)

            val message = buildString {
                append(getString(R.string.from_device, senderAlias))
                append("\n\n")
                append(fileNames)
                append(moreText)
                append("\n\n")
                append("Total: ${formatFileSize(totalSize)}")
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.btn_accept) { _, _ ->
                    accepted.set(true); latch.countDown()
                }
                .setNegativeButton(R.string.btn_reject) { _, _ ->
                    accepted.set(false); latch.countDown()
                }
                .setOnCancelListener {
                    accepted.set(false); latch.countDown()
                }
                .setCancelable(false)
                .create()

            dialog.show()
        }

        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return accepted.get()
    }

    fun addLogEntry(status: String, details: String? = null) {
        runOnUiThread {
            trimLogEntries()
            val timeStr = logDateFormat.format(Date())
            val tv = TextView(this).apply {
                text = if (details != null) "[$timeStr] $status\n$details" else "[$timeStr] $status"
                textSize = 13f
                setTextColor(getColorCompat(R.color.textSecondary))
                setPadding(0, 4, 0, 4)
            }
            logContainer.addView(tv)
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun addReceivedFile(fileName: String, size: Long, savedPath: String) {
        runOnUiThread {
            trimHistoryEntries()
            val tv = TextView(this).apply {
                text = "\u2713 $fileName (${formatFileSize(size)})"
                textSize = 14f
                setTextColor(getColorCompat(R.color.success))
                setPadding(8, 8, 8, 8)
            }

            @Suppress("DEPRECATION")
            tv.setOnClickListener {
                try {
                    val file = File(savedPath)
                    val uri = if (Build.VERSION.SDK_INT >= 24) {
                        androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            file
                        )
                    } else {
                        android.net.Uri.fromFile(file)
                    }
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setDataAndType(uri, "*/*")
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } catch (e: Exception) {
                    addLogEntry("Cannot open file", e.message)
                }
            }

            historyContainer.addView(tv, 0)
        }
    }

    private fun trimLogEntries() {
        while (logContainer.childCount > MAX_LOG_ENTRIES) {
            logContainer.removeViewAt(0)
        }
    }

    private fun trimHistoryEntries() {
        while (historyContainer.childCount > MAX_HISTORY_ENTRIES) {
            historyContainer.removeViewAt(historyContainer.childCount - 1)
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
