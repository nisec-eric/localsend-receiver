package com.localsend.receiver.network

interface DiscoveryListener {

    fun onDeviceFound(alias: String, ip: String, port: Int, fingerprint: String, version: String)

    fun onError(message: String)
}
