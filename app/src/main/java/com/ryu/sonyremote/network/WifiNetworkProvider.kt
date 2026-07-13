package com.ryu.sonyremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class WifiNetworkProvider(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var requestedNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val observedSsids = ConcurrentHashMap<Network, String>()

    suspend fun requestCameraNetwork(ssid: String, password: String, timeoutMillis: Long = 30_000): Network? {
        releaseRequestedNetwork()
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        requestedNetworkCallback = this
                        observedSsids[network] = ssid
                        if (continuation.isActive) continuation.resume(network)
                    }

                    override fun onUnavailable() {
                        runCatching { connectivityManager.unregisterNetworkCallback(this) }
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                connectivityManager.requestNetwork(request, callback)
                continuation.invokeOnCancellation {
                    if (requestedNetworkCallback !== callback) {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    }
                }
            }
        }
    }

    fun releaseRequestedNetwork() {
        requestedNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        requestedNetworkCallback = null
    }

    suspend fun awaitWifiNetwork(timeoutMillis: Long = 10_000): Network? {
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                lateinit var callback: ConnectivityManager.NetworkCallback
                fun finish(network: Network, capabilities: NetworkCapabilities? = null) {
                    capabilities?.wifiSsid()?.let { observedSsids[network] = it }
                    if (continuation.isActive) {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                        continuation.resume(network)
                    }
                }
                callback = if (Build.VERSION.SDK_INT >= 31) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onAvailable(network: Network) = Unit

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) = finish(network, networkCapabilities)
                    }
                } else object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        finish(network)
                    }
                }
                connectivityManager.registerNetworkCallback(request, callback)
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    fun isAvailable(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun ssid(network: Network): String? {
        observedSsids[network]?.let { return it }
        val wifiInfo = connectivityManager.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
        return (wifiInfo?.ssid ?: runCatching { wifiManager.connectionInfo.ssid }.getOrNull())
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }

    fun currentSsid(): String? {
        val active = connectivityManager.activeNetwork
        return active?.let(::ssid) ?: runCatching { wifiManager.connectionInfo.ssid }.getOrNull()
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }

    suspend fun awaitCurrentSsid(timeoutMillis: Long = 3_000): String? {
        currentSsid()?.let { return it }
        if (Build.VERSION.SDK_INT < 31) return null
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val value = networkCapabilities.wifiSsid() ?: return
                        observedSsids[network] = value
                        if (continuation.isActive) {
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            continuation.resume(value)
                        }
                    }
                }
                connectivityManager.registerNetworkCallback(request, callback)
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }
        }
    }


    private fun NetworkCapabilities.wifiSsid(): String? = (transportInfo as? WifiInfo)?.ssid
        ?.removeSurrounding("\"")
        ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }

}
