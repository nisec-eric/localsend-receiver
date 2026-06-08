package com.localsend.receiver.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    @JvmStatic
    fun getLocalIpAddress(context: Context): String? {
        val wifiIp = getWifiIpAddress(context)
        if (wifiIp != null) return wifiIp
        return getInterfaceIpAddress()
    }

    @JvmStatic
    fun getWifiIpAddress(context: Context): String? {
        val wifiManager = getWifiManager(context) ?: return null
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt == 0) return null
        return intToIpAddress(ipInt)
    }

    @JvmStatic
    fun getMulticastLock(context: Context, tag: String): WifiManager.MulticastLock {
        val wifiManager = getWifiManager(context)
            ?: throw IllegalStateException("WifiManager not available")
        val lock = wifiManager.createMulticastLock(tag)
        lock.setReferenceCounted(true)
        lock.acquire()
        return lock
    }

    @JvmStatic
    fun getWifiLock(context: Context): WifiManager.WifiLock {
        val wifiManager = getWifiManager(context)
            ?: throw IllegalStateException("WifiManager not available")
        val lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "localsend_wifi_lock")
        lock.setReferenceCounted(true)
        lock.acquire()
        return lock
    }

    @JvmStatic
    fun isWifiConnected(context: Context): Boolean {
        val wifiManager = getWifiManager(context) ?: return false
        return wifiManager.connectionInfo != null &&
                wifiManager.connectionInfo.ipAddress != 0
    }

    @JvmStatic
    fun getWifiManager(context: Context): WifiManager? {
        return try {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (_: Exception) {
            null
        }
    }

    private fun intToIpAddress(ipInt: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }

    private fun getInterfaceIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }
}
