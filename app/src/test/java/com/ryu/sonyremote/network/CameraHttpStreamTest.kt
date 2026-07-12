package com.ryu.sonyremote.network

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraHttpStreamTest {
    @Test
    fun closesUnderlyingRequestExactlyOnce() {
        var closeCalls = 0
        val stream = CameraHttpStream(ByteArrayInputStream(byteArrayOf(1))) { closeCalls++ }

        stream.close()
        stream.close()

        assertEquals(1, closeCalls)
    }
}
