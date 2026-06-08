package com.localsend.receiver.network

import com.localsend.receiver.network.dto.FileDto
import com.localsend.receiver.network.dto.RegisterDto

/**
 * Callback interface for events produced by [LocalSendHttpServer].
 *
 * All methods are invoked on NanoHTTPD worker threads. Implementations must be
 * thread-safe. [onPrepareUploadReceived] intentionally blocks the HTTP response
 * thread until the user accepts or rejects the transfer.
 */
interface ServerEventListener {

    fun onDeviceDiscovered(device: RegisterDto, ip: String)

    /**
     * Blocks the HTTP worker thread until the user decides.
     * @return true to accept, false to reject
     */
    fun onPrepareUploadReceived(senderInfo: RegisterDto, files: Map<String, FileDto>): Boolean

    fun onFileReceived(sessionId: String, fileId: String, fileName: String, size: Long, savedPath: String)

    fun onFileReceiveProgress(sessionId: String, fileId: String, bytesReceived: Long, totalBytes: Long)

    fun onTransferCancelled(sessionId: String)

    fun onError(sessionId: String?, message: String)
}
