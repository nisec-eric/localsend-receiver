package com.localsend.receiver.network

import android.content.Context
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.localsend.receiver.util.DeviceConfig
import com.localsend.receiver.util.NetworkUtils
import java.net.BindException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.Charset

class MulticastDiscoveryService(
    private val context: Context
) {

    private val ourAlias: String get() = DeviceConfig.alias
    private val ourFingerprint: String get() = DeviceConfig.fingerprint
    private val ourPort: Int get() = DeviceConfig.port

    companion object {
        private const val MULTICAST_GROUP = "224.0.0.167"
        private const val MULTICAST_PORT = 53317
        private const val SOCKET_TIMEOUT_MS = 1000
        private const val MAX_PACKET_SIZE = 4096
        private const val LOCK_TAG = "localsend_multicast"
        private val UTF8: Charset = Charset.forName("UTF-8")
    }

    private val gson = Gson()

    private var listener: DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var socket: MulticastSocket? = null
    private var listenThread: Thread? = null

    @Volatile
    private var running = false

    fun setListener(listener: DiscoveryListener?) {
        this.listener = listener
    }

    fun start() {
        if (running) return
        running = true
        acquireLocks()
        listenThread = Thread(Runnable { listenLoop() }, "localsend-multicast").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        closeSocket()
        listenThread?.let {
            try { it.interrupt() } catch (_: Exception) { /* ignored */ }
        }
        listenThread = null
        releaseLocks()
    }

    private fun acquireLocks() {
        try {
            multicastLock = NetworkUtils.getMulticastLock(context, LOCK_TAG)
        } catch (_: Exception) {
            notifyError("Failed to acquire multicast lock")
        }
        try {
            wifiLock = NetworkUtils.getWifiLock(context)
        } catch (_: Exception) {
            notifyError("Failed to acquire wifi lock")
        }
    }

    private fun releaseLocks() {
        multicastLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) { /* ignored */ }
        }
        multicastLock = null
        wifiLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) { /* ignored */ }
        }
        wifiLock = null
    }

    private fun listenLoop() {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        while (running) {
            var currentSocket: MulticastSocket? = null
            try {
                currentSocket = createAndJoinSocket()
                socket = currentSocket

                while (running) {
                    try {
                        currentSocket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    if (!running) break

                    val data = String(packet.data, 0, packet.length, UTF8)
                    val senderAddress = packet.address.hostAddress ?: continue
                    handleAnnouncement(data, senderAddress)
                }
            } catch (_: BindException) {
                notifyError("Port $MULTICAST_PORT already in use")
                sleepBeforeRetry()
            } catch (_: SocketException) {
                if (running) {
                    sleepBeforeRetry()
                }
            } catch (e: Exception) {
                if (running) {
                    notifyError("Discovery error: ${e.message}")
                    sleepBeforeRetry()
                }
            } finally {
                leaveAndClose(currentSocket)
                socket = null
            }
        }
    }

    private fun createAndJoinSocket(): MulticastSocket {
        val sock = MulticastSocket(MULTICAST_PORT)
        sock.reuseAddress = true
        sock.soTimeout = SOCKET_TIMEOUT_MS
        val localIp = NetworkUtils.getLocalIpAddress(context)
        if (localIp != null) {
            val localAddr = InetAddress.getByName(localIp)
            val ni = java.net.NetworkInterface.getByInetAddress(localAddr)
            if (ni != null) {
                sock.networkInterface = ni
            }
        }
        val group = InetAddress.getByName(MULTICAST_GROUP)
        @Suppress("DEPRECATION")
        sock.joinGroup(group)
        return sock
    }

    private fun handleAnnouncement(data: String, senderIp: String) {
        val obj = try {
            gson.fromJson(data, JsonObject::class.java)
        } catch (_: Exception) {
            return
        } ?: return

        if (!obj.has("announce") || !obj.get("announce").isJsonPrimitive) return
        if (!obj.get("announce").asBoolean) return

        val alias = obj.get("alias")?.asString ?: return
        val fingerprint = obj.get("fingerprint")?.asString ?: return
        val version = obj.get("version")?.asString ?: return
        val port = obj.get("port")?.asInt ?: return

        if (fingerprint == ourFingerprint) return

        listener?.onDeviceFound(alias, senderIp, port, fingerprint, version)

        HttpDiscoveryResponder.registerWithSender(senderIp, port)

        sendUdpResponse(senderIp, port)
    }

    private fun sendUdpResponse(targetIp: String, targetPort: Int) {
        try {
            val response = buildResponseJson()
            val responseBytes = response.toByteArray(UTF8)
            val responsePacket = DatagramPacket(
                responseBytes,
                responseBytes.size,
                InetAddress.getByName(targetIp),
                targetPort
            )
            val sock = socket ?: return
            sock.send(responsePacket)
        } catch (_: Exception) {
            // best-effort UDP response; HTTP is the primary channel
        }
    }

    private fun buildResponseJson(): String {
        val info = HashMap<String, Any>()
        info["alias"] = ourAlias
        info["version"] = "2.1"
        info["fingerprint"] = ourFingerprint
        info["port"] = ourPort
        info["protocol"] = "http"
        info["download"] = false
        info["announce"] = false
        return gson.toJson(info)
    }

    private fun closeSocket() {
        val sock = socket ?: return
        try {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            @Suppress("DEPRECATION")
            sock.leaveGroup(group)
        } catch (_: Exception) { /* socket closing anyway */ }
        try { sock.close() } catch (_: Exception) { /* ignored */ }
    }

    private fun leaveAndClose(sock: MulticastSocket?) {
        if (sock == null) return
        try {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            @Suppress("DEPRECATION")
            sock.leaveGroup(group)
        } catch (_: Exception) { /* ignored */ }
        try { sock.close() } catch (_: Exception) { /* ignored */ }
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun notifyError(message: String) {
        listener?.onError(message)
    }
}
