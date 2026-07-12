package com.ryu.sonyremote.protocol

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SsdpResponseParserTest {
    @Test
    fun parsesHeadersCaseInsensitively() {
        val response = SsdpResponseParser.parse(
            listOf(
                "HTTP/1.1 200 OK",
                "Cache-Control: max-age=1800",
                "LOCATION: http://192.168.122.1:64321/dd.xml",
                "UsN: uuid:00000000-0000-0010-8000-104fa8fffffe",
                "",
                "",
            ).joinToString("\r\n"),
        )

        assertEquals(URI("http://192.168.122.1:64321/dd.xml"), response?.location)
        assertEquals("uuid:00000000-0000-0010-8000-104fa8fffffe", response?.usn)
    }

    @Test
    fun rejectsPublicLocations() {
        val response = SsdpResponseParser.parse(
            "HTTP/1.1 200 OK\r\nLOCATION: http://8.8.8.8/device.xml\r\n\r\n",
        )

        assertNull(response)
    }

    @Test
    fun ignoresMalformedLocation() {
        val response = SsdpResponseParser.parse(
            "HTTP/1.1 200 OK\r\nLOCATION: http://[not-an-ip/device.xml\r\n\r\n",
        )

        assertNull(response)
    }
}
