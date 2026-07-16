package com.ryu.sonyremote.network

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.ryu.sonyremote.protocol.SsdpResponseParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class SsdpCameraDiscovery(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    suspend fun discover(network: Network, timeoutMillis: Long = 4_000): List<URI> =
        withContext(Dispatchers.IO) {
            val multicastLock = wifiManager.createMulticastLock("sony-camera-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(0))
                    network.bindSocket(socket)
                    val request = SEARCH_REQUEST.toByteArray(Charsets.US_ASCII)
                    val target = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
                    repeat(3) {
                        socket.send(DatagramPacket(request, request.size, target))
                        delay(100)
                    }

                    val deadline = System.currentTimeMillis() + timeoutMillis
                    val responses = linkedMapOf<String, URI>()
                    while (System.currentTimeMillis() < deadline) {
                        socket.soTimeout = min(800, (deadline - System.currentTimeMillis()).toInt()).coerceAtLeast(1)
                        val buffer = ByteArray(8 * 1024)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                            val body = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                            SsdpResponseParser.parse(body)?.let { response ->
                                Log.i(LOG_TAG, "Sony camera SSDP location: ${response.location}")
                                responses[response.location.toString()] = response.location
                            }
                        } catch (_: SocketTimeoutException) {
                            // Keep listening until the overall discovery deadline.
                        }
                    }
                    responses.values.toList()
                }
            } finally {
                if (multicastLock.isHeld) multicastLock.release()
            }
        }

    private companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val LOG_TAG = "SonyCameraDiscovery"
        val SEARCH_REQUEST = listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: $SSDP_HOST:$SSDP_PORT",
            "MAN: \"ssdp:discover\"",
            "MX: 1",
            "ST: urn:schemas-sony-com:service:ScalarWebAPI:1",
            "",
            "",
        ).joinToString("\r\n")
    }
}
