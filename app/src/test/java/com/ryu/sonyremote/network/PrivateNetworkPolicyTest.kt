package com.ryu.sonyremote.network

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateNetworkPolicyTest {
    @Test
    fun acceptsPrivateCameraAddress() {
        val uri = URI("http://192.168.122.1:10000/sony/camera")
        assertEquals(uri, PrivateNetworkPolicy.requireCameraUri(uri))
    }

    @Test
    fun rejectsDnsPublicAndHostSwitching() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivateNetworkPolicy.requireCameraUri(URI("http://camera.example/sony/camera"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateNetworkPolicy.requireCameraUri(URI("http://8.8.8.8/sony/camera"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateNetworkPolicy.requireCameraUri(
                URI("http://10.0.0.2/sony/camera"),
                expectedHost = "192.168.122.1",
            )
        }
    }
}
