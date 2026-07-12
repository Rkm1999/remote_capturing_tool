package com.ryu.sonyremote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveViewFrameParserTest {
    @Test
    fun skipsFrameInfoAndReadsFragmentedJpegPacket() {
        val frameInfo = packet(type = 0x02, sequence = 7, timestamp = 100, payload = byteArrayOf(1, 2, 3))
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val image = packet(type = 0x01, sequence = 8, timestamp = 135, payload = jpeg, padding = 3)
        val parser = LiveViewFrameParser(FragmentedInputStream(frameInfo + image, 2))

        val result = parser.nextJpeg()

        assertArrayEquals(jpeg, result?.jpeg)
        assertEquals(8, result?.sequenceNumber)
        assertEquals(135L, result?.timestampMillis)
        assertNull(parser.nextJpeg())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPayloadLargerThanConfiguredLimit() {
        val data = packet(type = 0x01, sequence = 1, timestamp = 0, payload = ByteArray(20))
        LiveViewFrameParser(ByteArrayInputStream(data), maxPayloadBytes = 10).nextJpeg()
    }

    private fun packet(
        type: Int,
        sequence: Int,
        timestamp: Int,
        payload: ByteArray,
        padding: Int = 0,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(0xff)
        write(type)
        write(sequence shr 8)
        write(sequence)
        write(timestamp shr 24)
        write(timestamp shr 16)
        write(timestamp shr 8)
        write(timestamp)
        write(byteArrayOf(0x24, 0x35, 0x68, 0x79))
        write(payload.size shr 16)
        write(payload.size shr 8)
        write(payload.size)
        write(padding)
        write(ByteArray(120))
        write(payload)
        write(ByteArray(padding) { 0x55 })
    }.toByteArray()

    private class FragmentedInputStream(
        bytes: ByteArray,
        private val maxChunk: Int,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, minOf(length, maxChunk))

        override fun skip(count: Long): Long = delegate.skip(minOf(count, 1))
    }
}

