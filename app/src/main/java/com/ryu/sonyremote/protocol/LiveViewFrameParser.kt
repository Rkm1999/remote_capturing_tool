package com.ryu.sonyremote.protocol

import com.ryu.sonyremote.model.LiveViewFrame
import java.io.EOFException
import java.io.InputStream

class LiveViewFrameParser(
    private val input: InputStream,
    private val maxPayloadBytes: Int = 20 * 1024 * 1024,
) {
    fun nextJpeg(): LiveViewFrame? {
        while (true) {
            val common = readCommonHeader() ?: return null
            val payloadHeader = ByteArray(PAYLOAD_HEADER_SIZE)
            input.readFully(payloadHeader)
            require(payloadHeader.copyOfRange(0, 4).contentEquals(PAYLOAD_MAGIC)) {
                "Invalid Sony live-view payload header"
            }
            val payloadSize =
                ((payloadHeader[4].toInt() and 0xff) shl 16) or
                    ((payloadHeader[5].toInt() and 0xff) shl 8) or
                    (payloadHeader[6].toInt() and 0xff)
            val paddingSize = payloadHeader[7].toInt() and 0xff
            require(payloadSize in 0..maxPayloadBytes) { "Live-view payload is too large: $payloadSize" }

            val payload = ByteArray(payloadSize)
            input.readFully(payload)
            input.skipFully(paddingSize)
            if (common.type == JPEG_TYPE) {
                return LiveViewFrame(
                    jpeg = payload,
                    sequenceNumber = common.sequenceNumber,
                    timestampMillis = common.timestampMillis,
                )
            }
        }
    }

    private fun readCommonHeader(): CommonHeader? {
        var first: Int
        do {
            first = input.read()
            if (first == -1) return null
        } while (first != START_BYTE)

        val remainder = ByteArray(COMMON_HEADER_SIZE - 1)
        input.readFully(remainder)
        return CommonHeader(
            type = remainder[0].toInt() and 0xff,
            sequenceNumber =
                ((remainder[1].toInt() and 0xff) shl 8) or (remainder[2].toInt() and 0xff),
            timestampMillis =
                ((remainder[3].toLong() and 0xff) shl 24) or
                    ((remainder[4].toLong() and 0xff) shl 16) or
                    ((remainder[5].toLong() and 0xff) shl 8) or
                    (remainder[6].toLong() and 0xff),
        )
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("Unexpected end of Sony live-view stream")
            if (count == 0) continue
            offset += count
        }
    }

    private fun InputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skip(remaining.toLong()).toInt()
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() == -1) {
                throw EOFException("Unexpected end of Sony live-view padding")
            } else {
                remaining--
            }
        }
    }

    private data class CommonHeader(
        val type: Int,
        val sequenceNumber: Int,
        val timestampMillis: Long,
    )

    private companion object {
        const val START_BYTE = 0xff
        const val JPEG_TYPE = 0x01
        const val COMMON_HEADER_SIZE = 8
        const val PAYLOAD_HEADER_SIZE = 128
        val PAYLOAD_MAGIC = byteArrayOf(0x24, 0x35, 0x68, 0x79)
    }
}
