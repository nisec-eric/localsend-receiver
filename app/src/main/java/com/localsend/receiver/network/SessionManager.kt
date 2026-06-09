package com.localsend.receiver.network

import com.localsend.receiver.network.dto.FileDto
import com.localsend.receiver.network.dto.RegisterDto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FileTransfer(
    val token: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    var savedPath: String? = null,
    var bytesReceived: Long = 0,
    var completed: Boolean = false
)

data class TransferSession(
    val sessionId: String,
    val senderInfo: RegisterDto,
    val files: ConcurrentHashMap<String, FileTransfer>,
    var accepted: Boolean = false,
    var cancelled: Boolean = false
)

class SessionManager {

    private val sessions = ConcurrentHashMap<String, TransferSession>()

    fun createSession(
        senderInfo: RegisterDto,
        files: Map<String, FileDto>
    ): TransferSession {
        val sessionId = UUID.randomUUID().toString()
        val fileTransfers = ConcurrentHashMap<String, FileTransfer>()
        for ((fileId, dto) in files) {
            fileTransfers[fileId] = FileTransfer(
                token = UUID.randomUUID().toString(),
                fileName = dto.fileName,
                size = dto.size,
                fileType = dto.fileType
            )
        }
        val session = TransferSession(
            sessionId = sessionId,
            senderInfo = senderInfo,
            files = fileTransfers
        )
        sessions[sessionId] = session
        return session
    }

    fun getSession(sessionId: String): TransferSession? = sessions[sessionId]

    fun acceptSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.accepted = true
        return true
    }

    fun validateUpload(sessionId: String, fileId: String, token: String): FileTransfer? {
        val session = sessions[sessionId] ?: return null
        if (!session.accepted || session.cancelled) return null
        val transfer = session.files[fileId] ?: return null
        if (transfer.token != token) return null
        if (transfer.completed) return null
        return transfer
    }

    fun cancelSession(sessionId: String): TransferSession? {
        val session = sessions.remove(sessionId) ?: return null
        session.cancelled = true
        return session
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun isSessionCancelled(sessionId: String): Boolean {
        return sessions[sessionId]?.cancelled ?: true
    }

    fun markFileCompleted(sessionId: String, fileId: String) {
        val session = sessions[sessionId] ?: return
        session.files[fileId]?.completed = true
    }

    fun isSessionComplete(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return true
        return session.files.values.all { it.completed }
    }

    fun getTokensForSession(sessionId: String): Map<String, String> {
        val session = sessions[sessionId] ?: return emptyMap()
        val result = HashMap<String, String>()
        for ((fileId, transfer) in session.files) {
            result[fileId] = transfer.token
        }
        return result
    }
}
