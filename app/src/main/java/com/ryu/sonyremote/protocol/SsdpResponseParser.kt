package com.ryu.sonyremote.protocol

import com.ryu.sonyremote.network.PrivateNetworkPolicy
import java.net.URI

data class SsdpResponse(
    val location: URI,
    val usn: String?,
)

object SsdpResponseParser {
    fun parse(packet: String): SsdpResponse? {
        val lines = packet.lineSequence().map(String::trimEnd).toList()
        if (lines.firstOrNull()?.startsWith("HTTP/1.1 200", ignoreCase = true) != true) return null
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()
        val locationText = headers["location"] ?: return null
        return runCatching {
            val location = URI(locationText)
            SsdpResponse(
                location = PrivateNetworkPolicy.requireCameraUri(location),
                usn = headers["usn"],
            )
        }.getOrNull()
    }
}
