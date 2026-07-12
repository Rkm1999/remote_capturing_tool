package com.ryu.sonyremote.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JpegValidatorTest {
    @Test
    fun acceptsCompleteJpeg() {
        JpegValidator.requireComplete(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()),
        )
    }

    @Test
    fun rejectsTruncatedJpegThatHasValidStartMarker() {
        assertThrows(IllegalArgumentException::class.java) {
            JpegValidator.requireComplete(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2),
            )
        }
    }

    @Test
    fun acceptsAndRemovesSonyZeroPadding() {
        val canonical = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val padded = canonical + ByteArray(6_444)

        assertArrayEquals(canonical, JpegValidator.normalize(padded))
    }

    @Test
    fun rejectsNonZeroDataAfterEndMarker() {
        assertThrows(IllegalArgumentException::class.java) {
            JpegValidator.normalize(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte(), 1),
            )
        }
    }
}
