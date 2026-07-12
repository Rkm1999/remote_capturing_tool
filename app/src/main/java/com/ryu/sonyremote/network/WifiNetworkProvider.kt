package com.ryu.sonyremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class WifiNetworkProvider(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    suspend fun awaitWifiNetwork(timeoutMillis: Long = 10_000): Network? {
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) {
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            continuation.resume(network)
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

    fun isAvailable(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

}
