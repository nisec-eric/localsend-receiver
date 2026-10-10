package com.localsend.receiver.network

import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class FileReceiver(private val listener: ServerEventListener) {

    private val cancelledFiles = HashSet<String>()

    @Synchronized
    fun cancelFile(key: String) {
        cancelledFiles.add(key)
    }

    @Synchronized
    private fun isCancelled(key: String): Boolean = cancelledFiles.contains(key)

    @Synchronized
    private fun removeCancelled(key: String) {
        cancelledFiles.remove(key)
    }

    fun saveFile(
        inputStream: InputStream,
        sessionId: String,
        fileId: String,
        transfer: FileTransfer,
        contentLength: Long,
        chunked: Boolean = false
    ): File {
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LocalSend"
        )
        if (!destDir.exists()) destDir.mkdirs()

        val destFile = resolveUniqueFile(destDir, transfer.fileName)
        val fileKey = "$sessionId:$fileId"
        val expectedSize = if (contentLength > 0) contentLength else transfer.size

        try {
            val totalRead: Long
            FileOutputStream(destFile).use { output ->
                totalRead = if (chunked) {
                    readChunkedBody(inputStream, output, sessionId, fileId, fileKey, expectedSize)
                } else {
                    readFixedLengthBody(inputStream, output, sessionId, fileId, fileKey, expectedSize)
                }
                output.flush()
            }

            transfer.savedPath = destFile.absolutePath
            transfer.bytesReceived = totalRead
            transfer.completed = true
            listener.onFileReceived(sessionId, fileId, transfer.fileName, totalRead, destFile.absolutePath)
        } catch (e: Throwable) {
            destFile.delete()
            throw e
        } finally {
            removeCancelled(fileKey)
        }

        return destFile
    }

    private fun readFixedLengthBody(
        inputStream: InputStream,
        output: FileOutputStream,
        sessionId: String,
        fileId: String,
        fileKey: String,
        expectedSize: Long
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalRead: Long = 0
        var lastProgressReport: Long = 0

        while (totalRead < expectedSize) {
            if (isCancelled(fileKey)) {
                throw TransferCancelledException(fileId)
            }

            val toRead = if (expectedSize - totalRead < buffer.size) {
                (expectedSize - totalRead).toInt()
            } else {
                buffer.size
            }
            val read = inputStream.read(buffer, 0, toRead)
            if (read == -1) break

            output.write(buffer, 0, read)
            totalRead += read.toLong()

            val now = System.currentTimeMillis()
            if (now - lastProgressReport >= PROGRESS_INTERVAL_MS) {
                lastProgressReport = now
                listener.onFileReceiveProgress(sessionId, fileId, totalRead, expectedSize)
            }
        }
        return totalRead
    }

    private fun readChunkedBody(
        inputStream: InputStream,
        output: FileOutputStream,
        sessionId: String,
        fileId: String,
        fileKey: String,
        expectedSize: Long
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalRead: Long = 0
        var lastProgressReport: Long = 0

        while (true) {
            if (isCancelled(fileKey)) {
                throw TransferCancelledException(fileId)
            }

            val sizeLine = readLine(inputStream)
            val chunkSize = parseChunkSize(sizeLine)
                ?: throw java.io.IOException("Invalid chunk size: $sizeLine")

            if (chunkSize == 0L) {
                while (true) {
                    if (readLine(inputStream).isEmpty()) break
                }
                break
            }

            var remaining = chunkSize
            while (remaining > 0) {
                if (isCancelled(fileKey)) {
                    throw TransferCancelledException(fileId)
                }
                val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                val read = inputStream.read(buffer, 0, toRead)
                if (read == -1) throw java.io.IOException("Unexpected EOF in chunk")

                output.write(buffer, 0, read)
                remaining -= read
                totalRead += read.toLong()

                val now = System.currentTimeMillis()
                if (now - lastProgressReport >= PROGRESS_INTERVAL_MS) {
                    lastProgressReport = now
                    listener.onFileReceiveProgress(sessionId, fileId, totalRead, expectedSize)
                }
            }

            readLine(inputStream)
        }
        return totalRead
    }

    private fun readLine(inputStream: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) throw java.io.IOException("Unexpected EOF while reading line")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun parseChunkSize(line: String): Long? {
        val sizePart = line.substringBefore(';').trim()
        return try {
            java.lang.Long.parseLong(sizePart, 16)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun resolveUniqueFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate

        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        var counter = 1
        while (true) {
            candidate = File(directory, "$baseName ($counter)$extension")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    fun deletePartialFile(path: String?) {
        if (path != null) {
            val f = File(path)
            if (f.exists()) f.delete()
        }
    }

    class TransferCancelledException(fileId: String) : Exception("Cancelled: $fileId")

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}
