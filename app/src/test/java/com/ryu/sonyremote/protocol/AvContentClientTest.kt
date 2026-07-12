package com.ryu.sonyremote.protocol

import java.net.URI
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvContentClientTest {
    @Test
    fun selectsNearestJpegOriginalInsteadOfRawOrUnrelatedStill() = runBlocking {
        val captured = OffsetDateTime.parse("2026-07-11T12:00:05-07:00").toInstant().toEpochMilli()
        var request = ""
        val transport = JsonRpcTransport { _, body, _ ->
            request = body
            """{"id":1,"result":[[
              {"uri":"storage:old","contentKind":"still","createdTime":"2026-07-11T11:40:00-07:00","content":{"original":[{"url":"http://camera/old.jpg","fileName":"OLD.JPG","stillObject":"jpeg"}]}},
              {"uri":"storage:new","contentKind":"still","createdTime":"2026-07-11T12:00:04-07:00","content":{"original":[{"url":"http://camera/new.arw","fileName":"NEW.ARW","stillObject":"raw"},{"url":"http://camera/new.jpg","fileName":"NEW.JPG","stillObject":"jpeg"}]}}
            ]]}"""
        }
        val client = AvContentClient(URI("http://192.168.122.1/sony/avContent"), transport)

        val result = client.findOriginalNear(captured)

        assertEquals("storage:new", result.contentUri)
        assertEquals(URI("http://camera/new.jpg"), result.originalUrl)
        assertTrue(request.contains("\"view\":\"flat\""))
        assertTrue(request.contains("\"sort\":\"descending\""))
    }
}
