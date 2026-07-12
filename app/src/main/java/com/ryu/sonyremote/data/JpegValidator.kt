package com.ryu.sonyremote.data

object JpegValidator {
    fun requireComplete(jpeg: ByteArray) {
        normalize(jpeg)
    }

    fun normalize(jpeg: ByteArray): ByteArray {
        require(
            jpeg.size >= 4 &&
                jpeg[0] == 0xff.toByte() &&
                jpeg[1] == 0xd8.toByte(),
        ) { "Camera response is not a JPEG image" }

        val eoiOffset = findLastEndMarker(jpeg)
        require(eoiOffset >= 2) { "Camera JPEG download is incomplete" }
        val trailingBytes = jpeg.size - (eoiOffset + 2)
        require(trailingBytes <= MAX_TRAILING_PADDING_BYTES) {
            "Camera JPEG has excessive trailing data"
        }
        require((eoiOffset + 2 until jpeg.size).all { jpeg[it] == 0.toByte() }) {
            "Camera JPEG has unexpected trailing data"
        }
        return if (trailingBytes == 0) jpeg else jpeg.copyOf(eoiOffset + 2)
    }

    private fun findLastEndMarker(jpeg: ByteArray): Int {
        for (index in jpeg.lastIndex - 1 downTo 1) {
            if (jpeg[index] == 0xff.toByte() && jpeg[index + 1] == 0xd9.toByte()) return index
        }
        return -1
    }

    private const val MAX_TRAILING_PADDING_BYTES = 64 * 1024
}
