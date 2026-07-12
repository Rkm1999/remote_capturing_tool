package com.ryu.sonyremote.network

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

object PrivateNetworkPolicy {
    fun requireCameraUri(uri: URI, expectedHost: String? = null): URI {
        require(uri.scheme.equals("http", ignoreCase = true)) {
            "Camera endpoint must use local HTTP"
        }
        require(uri.userInfo == null && uri.fragment == null) {
            "Camera endpoint contains unsupported URI components"
        }
        val host = requireNotNull(uri.host) { "Camera endpoint has no host" }
        if (expectedHost != null) {
            require(host.equals(expectedHost, ignoreCase = true)) {
                "Camera redirected to a different host"
            }
        }
        val address = parseLiteralAddress(host)
        require(isPrivateOrLinkLocal(address)) {
            "Camera endpoint is not a private or link-local address"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Camera endpoint has an invalid port" }
        return uri
    }

    private fun parseLiteralAddress(host: String): InetAddress {
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4) {
            require(ipv4Parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
            }) { "Camera host must be a numeric IP address" }
            return InetAddress.getByAddress(ipv4Parts.map(String::toInt).map(Int::toByte).toByteArray())
        }

        require(':' in host && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' }) {
            "Camera host must be a numeric IP address"
        }
        return InetAddress.getByName(host)
    }

    private fun isPrivateOrLinkLocal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) {
            return false
        }
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        if (address is Inet6Address) {
            val first = address.address.first().toInt() and 0xff
            return first and 0xfe == 0xfc // RFC 4193 unique-local address.
        }
        return false
    }
}

