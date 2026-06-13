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
import android.os.FileObserver
import android.webkit.MimeTypeMap
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
        private const val MAX_FILES_DISPLAY = 200
    }

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var logScrollView: ScrollView
    private lateinit var historyContainer: LinearLayout

    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var fileObserver: FileObserver? = null

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
        refreshFileList()
    }

    override fun onResume() {
        super.onResume()
        refreshFileList()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LocalSendService.ACTION_STATUS_UPDATE)
        try {
            registerReceiver(statusReceiver, filter)
        } catch (_: Exception) {}
        startLocalSendService()
        LocalSendService.registerActivity(this)
        setupFileObserver()
    }

    override fun onStop() {
        LocalSendService.unregisterActivity()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        fileObserver?.stopWatching()
        fileObserver = null
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

    private fun getDownloadDir(): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalSend")
    }

    private fun setupFileObserver() {
        val dir = getDownloadDir()
        if (!dir.exists()) dir.mkdirs()

        fileObserver = object : FileObserver(dir.absolutePath, CREATE or MOVED_TO or DELETE or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    runOnUiThread { refreshFileList() }
                }
            }
        }
        fileObserver?.startWatching()
    }

    fun refreshFileList() {
        runOnUiThread {
            historyContainer.removeAllViews()
            val dir = getDownloadDir()
            val files = dir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()

            if (files.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "No files yet"
                    textSize = 13f
                    setTextColor(getColorCompat(R.color.textSecondary))
                    setPadding(12, 16, 12, 16)
                }
                historyContainer.addView(empty)
                return@runOnUiThread
            }

            for (file in files.take(MAX_FILES_DISPLAY)) {
                val fileView = createFileItem(file)
                historyContainer.addView(fileView)
            }
        }
    }

    private fun createFileItem(file: File): View {
        val isDir = file.isDirectory
        val sizeText = if (isDir) "folder" else formatFileSize(file.length())
        val dateStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))

        val tv = TextView(this).apply {
            text = if (isDir) "\uD83D\uDCC1 ${file.name}" else getFileIcon(file.name) + " " + file.name +
                   "\n  $sizeText  \u00B7  $dateStr"
            textSize = 13f
            setTextColor(if (isDir) getColorCompat(R.color.accent) else getColorCompat(R.color.textPrimary))
            setPadding(12, 10, 12, 10)
            isFocusable = true
            isClickable = true
            isLongClickable = true
            setBackgroundResource(R.drawable.focusable_item_background)
        }

        tv.setOnClickListener { openFile(file.absolutePath) }
        tv.setOnLongClickListener {
            showDeleteConfirm(file)
            true
        }

        return tv
    }

    private fun getFileIcon(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return when (ext) {
            "apk" -> "\uD83D\uDCE6"
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "\uD83D\uDDBC\uFE0F"
            "mp4", "avi", "mkv", "mov" -> "\uD83C\uDFAC"
            "mp3", "wav", "ogg", "flac", "m4a" -> "\uD83C\uDFB5"
            "txt", "log" -> "\uD83D\uDCC4"
            "pdf" -> "\uD83D\uDCC3"
            "zip", "rar", "7z", "tar", "gz" -> "\uD83D\uDCE6"
            "doc", "docx" -> "\uD83D\uDCC4"
            "xls", "xlsx" -> "\uD83D\uDCCA"
            else -> "\uD83D\uDCC1"
        }
    }

    private fun showDeleteConfirm(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete file?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                    refreshFileList()
                    addLogEntry("Deleted", file.name)
                } catch (e: Exception) {
                    addLogEntry("Delete failed", e.message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFile(savedPath: String) {
        try {
            val file = File(savedPath)
            if (!file.exists()) {
                addLogEntry("Cannot open file", "File not found")
                return
            }

            val uri = if (Build.VERSION.SDK_INT >= 24) {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            } else {
                @Suppress("DEPRECATION")
                android.net.Uri.fromFile(file)
            }

            val mimeType = getMimeType(file.name)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                addLogEntry("Cannot open", "No app found for: $mimeType")
            }
        } catch (e: Exception) {
            addLogEntry("Cannot open file", e.message)
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> {
                val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                mapped ?: "application/octet-stream"
            }
        }
    }

    private fun trimLogEntries() {
        while (logContainer.childCount > MAX_LOG_ENTRIES) {
            logContainer.removeViewAt(0)
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
