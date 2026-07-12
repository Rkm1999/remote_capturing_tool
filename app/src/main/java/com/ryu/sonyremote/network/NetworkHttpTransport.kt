package com.ryu.sonyremote.network

import android.net.Network
import com.ryu.sonyremote.protocol.JsonRpcTransport
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkHttpTransport(
    private val network: Network,
    private val cameraHost: String,
) : JsonRpcTransport {
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override suspend fun postJson(
        endpoint: URI,
        body: String,
        readTimeoutMillis: Int,
    ): String = withContext(Dispatchers.IO) {
        val connection = openConnection(endpoint).apply {
            requestMethod = "POST"
            doOutput = true
            readTimeout = readTimeoutMillis
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            connection.requireSuccess()
            connection.inputStream.use { it.readLimited(MAX_JSON_BYTES).toString(Charsets.UTF_8) }
        } finally {
            release(connection)
        }
    }

    suspend fun getText(endpoint: URI): String = withContext(Dispatchers.IO) {
        val connection = openConnection(endpoint)
        try {
            connection.requireSuccess()
            connection.inputStream.use { it.readLimited(MAX_DESCRIPTION_BYTES).toString(Charsets.UTF_8) }
        } finally {
            release(connection)
        }
    }

    suspend fun readBytes(
        endpoint: URI,
        maxBytes: Int = 60 * 1024 * 1024,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = openConnection(endpoint).apply { readTimeout = DOWNLOAD_TIMEOUT_MILLIS }
            try {
                connection.requireSuccess()
                val expectedBytes = connection.contentLengthLong
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "Camera response exceeded $maxBytes bytes" }
                        output.write(buffer, 0, count)
                        onProgress?.invoke(total, expectedBytes.takeIf { it >= 0 })
                    }
                    output.toByteArray()
                }
                require(expectedBytes < 0 || bytes.size.toLong() == expectedBytes) {
                    "Camera download ended early: received ${bytes.size} of $expectedBytes bytes"
                }
                bytes
            } finally {
                release(connection)
            }
        }

    suspend fun openStream(endpoint: URI): CameraHttpStream = withContext(Dispatchers.IO) {
        val connection = openConnection(endpoint).apply { readTimeout = LIVEVIEW_IDLE_TIMEOUT_MILLIS }
        try {
            connection.requireSuccess()
            CameraHttpStream(
                input = BufferedInputStream(connection.inputStream, 64 * 1024),
                onClose = { release(connection) },
            )
        } catch (error: Throwable) {
            release(connection)
            throw error
        }
    }

    fun cancelActiveRequests() {
        activeConnections.toList().forEach { connection ->
            activeConnections.remove(connection)
            runCatching { connection.disconnect() }
        }
    }

    private fun openConnection(endpoint: URI): HttpURLConnection {
        PrivateNetworkPolicy.requireCameraUri(endpoint, cameraHost)
        return (network.openConnection(endpoint.toURL()) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
        }.also(activeConnections::add)
    }

    private fun release(connection: HttpURLConnection) {
        activeConnections.remove(connection)
        connection.disconnect()
    }

    private fun HttpURLConnection.requireSuccess() {
        val status = responseCode
        if (status !in 200..299) {
            val detail = errorStream?.use { it.readLimited(MAX_ERROR_BYTES).toString(Charsets.UTF_8) }.orEmpty()
            throw CameraHttpException(status, detail.ifBlank { responseMessage ?: "HTTP error" })
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Camera download exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 5_000
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000
        const val LIVEVIEW_IDLE_TIMEOUT_MILLIS = 5_000
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_DESCRIPTION_BYTES = 1024 * 1024
        const val MAX_ERROR_BYTES = 4 * 1024
    }
}

class CameraHttpStream internal constructor(
    val input: InputStream,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        onClose()
    }
}

class CameraHttpException(
    val statusCode: Int,
    message: String,
) : IOException("Camera HTTP $statusCode: $message")
