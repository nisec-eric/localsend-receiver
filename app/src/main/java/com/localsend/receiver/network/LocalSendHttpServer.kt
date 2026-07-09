package com.localsend.receiver.network

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import com.localsend.receiver.network.dto.*
import com.localsend.receiver.util.DeviceConfig
import java.io.File
import java.nio.charset.Charset

class LocalSendHttpServer(
    port: Int,
    private val listener: ServerEventListener
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val sessionManager = SessionManager()
    private val fileReceiver = FileReceiver(listener)

    companion object {
        private const val PATH_INFO = "/api/localsend/v2/info"
        private const val PATH_REGISTER = "/api/localsend/v2/register"
        private const val PATH_PREPARE_UPLOAD = "/api/localsend/v2/prepare-upload"
        private const val PATH_UPLOAD = "/api/localsend/v2/upload"
        private const val PATH_CANCEL = "/api/localsend/v2/cancel"
        private val UTF8: Charset = Charset.forName("UTF-8")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return errorResponse(Response.Status.NOT_FOUND, "Not found")
        val method = session.method

        return try {
            when {
                uri == PATH_INFO && method == Method.GET -> handleInfo()
                uri == PATH_REGISTER && method == Method.POST -> handleRegister(session)
                uri == PATH_PREPARE_UPLOAD && method == Method.POST -> handlePrepareUpload(session)
                uri.startsWith(PATH_UPLOAD) && method == Method.POST -> handleUpload(session)
                uri.startsWith(PATH_CANCEL) && method == Method.POST -> handleCancel(session)
                else -> errorResponse(Response.Status.NOT_FOUND, "Not found")
            }
        } catch (e: Throwable) {
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Error")
        }
    }

    private fun handleInfo(): Response {
        return jsonResponse(buildInfoResponse())
    }

    private fun handleRegister(session: IHTTPSession): Response {
        val body = readBody(session)
        val dto = gson.fromJson(body, RegisterDto::class.java)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "Empty body")
        listener.onDeviceDiscovered(dto, session.remoteIpAddress)
        return jsonResponse(buildInfoResponse())
    }

    private fun handlePrepareUpload(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = gson.fromJson(body, PrepareUploadRequest::class.java)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "Empty body")

        if (request.files.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")
        }

        val transferSession = sessionManager.createSession(request.info, request.files)
        val accepted = listener.onPrepareUploadReceived(request.info, request.files)

        if (!accepted) {
            sessionManager.removeSession(transferSession.sessionId)
            return errorResponse(Response.Status.FORBIDDEN, "Rejected by user")
        }

        sessionManager.acceptSession(transferSession.sessionId)
        val tokens = sessionManager.getTokensForSession(transferSession.sessionId)
        return jsonResponse(PrepareUploadResponse(transferSession.sessionId, tokens))
    }

    @Suppress("DEPRECATION")
    private fun handleUpload(session: IHTTPSession): Response {
        val params = session.parms ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing params")
        val sessionId = params["sessionId"] ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing sessionId")
        val fileId = params["fileId"] ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing fileId")
        val token = params["token"] ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing token")

        val transfer = sessionManager.validateUpload(sessionId, fileId, token)
            ?: return errorResponse(Response.Status.FORBIDDEN, "Invalid session/token")

        if (sessionManager.isSessionCancelled(sessionId)) {
            return errorResponse(Response.Status.FORBIDDEN, "Cancelled")
        }

        val contentLength = parseContentLength(session)

        return try {
            fileReceiver.saveFile(session.inputStream, sessionId, fileId, transfer, contentLength)
            sessionManager.markFileCompleted(sessionId, fileId)
            if (sessionManager.isSessionComplete(sessionId)) {
                sessionManager.removeSession(sessionId)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
        } catch (e: FileReceiver.TransferCancelledException) {
            fileReceiver.deletePartialFile(transfer.savedPath)
            listener.onTransferCancelled(sessionId)
            errorResponse(Response.Status.FORBIDDEN, "Cancelled")
        } catch (e: Exception) {
            fileReceiver.deletePartialFile(transfer.savedPath)
            listener.onError(sessionId, "Receive error: ${e.message}")
            errorResponse(Response.Status.INTERNAL_ERROR, "Failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleCancel(session: IHTTPSession): Response {
        val body = readBody(session)
        val sessionId = if (body.isNotBlank()) {
            try { gson.fromJson(body, CancelRequest::class.java)?.sessionId } catch (_: Exception) { null }
        } else null

        val sid = sessionId ?: session.parms?.get("sessionId")
            ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing sessionId")

        val cancelled = sessionManager.cancelSession(sid)
        if (cancelled != null) {
            for ((fileId, t) in cancelled.files) {
                fileReceiver.cancelFile("$sid:$fileId")
                fileReceiver.deletePartialFile(t.savedPath)
            }
            listener.onTransferCancelled(sid)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
    }

    private fun buildInfoResponse() = InfoResponse(
        alias = DeviceConfig.alias,
        version = DeviceConfig.version,
        deviceModel = DeviceConfig.deviceModel,
        deviceType = DeviceConfig.deviceType,
        fingerprint = DeviceConfig.fingerprint,
        download = false
    )

    private fun readBody(session: IHTTPSession): String {
        val contentLength = parseContentLength(session)
        if (contentLength <= 0) return ""

        val size = contentLength.toInt()
        val bytes = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val read = session.inputStream.read(bytes, totalRead, size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return String(bytes, 0, totalRead, UTF8)
    }

    private fun parseContentLength(session: IHTTPSession): Long {
        return session.headers?.get("content-length")?.toLongOrNull() ?: -1L
    }

    private fun jsonResponse(data: Any): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(data))
    }

    private fun errorResponse(status: Response.Status, message: String): Response {
        return newFixedLengthResponse(status, "application/json", gson.toJson(ErrorResponse(message)))
    }
}
