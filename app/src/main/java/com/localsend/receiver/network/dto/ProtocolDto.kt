package com.localsend.receiver.network.dto

import com.google.gson.annotations.SerializedName

enum class DeviceType {
    @SerializedName("MOBILE")   MOBILE,
    @SerializedName("DESKTOP")  DESKTOP,
    @SerializedName("WEB")      WEB,
    @SerializedName("HEADLESS") HEADLESS,
    @SerializedName("SERVER")   SERVER;

    fun toWire(): String = name
}

enum class ProtocolType {
    @SerializedName("http")  HTTP,
    @SerializedName("https") HTTPS;

    fun toWire(): String = name.lowercase()
}

data class RegisterDto(
    val alias: String,
    val version: String = "1.0",
    val deviceModel: String? = null,
    val deviceType: DeviceType? = null,
    val fingerprint: String,
    val port: Int = 53317,
    val protocol: ProtocolType = ProtocolType.HTTP,
    val download: Boolean? = null
)

data class InfoResponse(
    val alias: String,
    val version: String,
    val deviceModel: String,
    val deviceType: String,
    val fingerprint: String,
    val download: Boolean
)

data class FileMetadata(
    val modified: String? = null,
    val accessed: String? = null
)

data class FileDto(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: FileMetadata? = null
)

data class PrepareUploadRequest(
    val info: RegisterDto,
    val files: Map<String, FileDto>
)

data class PrepareUploadResponse(
    val sessionId: String,
    val files: Map<String, String>
)

data class CancelRequest(
    val sessionId: String
)

data class ErrorResponse(
    val message: String
)

data class MulticastMessage(
    val alias: String,
    val version: String,
    val fingerprint: String,
    val port: Int,
    val protocol: ProtocolType = ProtocolType.HTTP,
    val download: Boolean? = null,
    val announce: Boolean = true
)
