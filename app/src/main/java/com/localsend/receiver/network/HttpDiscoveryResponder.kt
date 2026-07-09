package com.localsend.receiver.network

import android.util.Log
import com.google.gson.Gson
import com.localsend.receiver.util.DeviceConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object HttpDiscoveryResponder {

    private const val TAG = "HttpDiscovery"
    private val gson = Gson()

    private val executor = ThreadPoolExecutor(
        0, 2, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(16),
        { r -> Thread(r, "ls-http-register").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    fun registerWithSender(senderIp: String, senderPort: Int) {
        executor.execute {
            try {
                doRegister(senderIp, senderPort)
            } catch (_: Throwable) { }
        }
    }

    private fun doRegister(senderIp: String, senderPort: Int) {
        val url = URL("http://$senderIp:$senderPort/api/localsend/v2/register")
        var conn: HttpURLConnection? = null
        try {
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.doInput = true

            val body = buildRegisterBody()
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body); it.flush() }
            conn.responseCode
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildRegisterBody(): String {
        val info = LinkedHashMap<String, Any>()
        info["alias"] = DeviceConfig.alias
        info["version"] = "2.1"
        info["fingerprint"] = DeviceConfig.fingerprint
        info["port"] = DeviceConfig.port
        info["protocol"] = "http"
        info["download"] = false
        info["announce"] = false
        return gson.toJson(info)
    }
}
