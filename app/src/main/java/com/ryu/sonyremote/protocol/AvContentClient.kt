package com.ryu.sonyremote.protocol

import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class CameraContentOriginal(
    val contentUri: String,
    val originalUrl: URI,
    val fileName: String?,
    val createdAtEpochMillis: Long?,
)

class AvContentClient(
    private val endpoint: URI,
    private val transport: JsonRpcTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val nextId = AtomicInteger(1)

    suspend fun findOriginalNear(capturedAtEpochMillis: Long): CameraContentOriginal {
        val result = call(
            method = "getContentList",
            version = "1.3",
            params = buildJsonArray {
                add(buildJsonObject {
                    put("uri", "storage:memoryCard1")
                    put("stIdx", 0)
                    put("cnt", CONTENT_LOOKBACK)
                    put("view", "flat")
                    put("sort", "descending")
                })
            },
        )
        val candidates = result.getOrNull(0)?.let { it as? JsonArray }.orEmpty()
            .mapNotNull(::parseStillOriginal)
        check(candidates.isNotEmpty()) { "Camera Contents Transfer returned no downloadable JPEGs" }
        val timed = candidates.mapNotNull { candidate ->
            candidate.createdAtEpochMillis?.let { created -> candidate to abs(created - capturedAtEpochMillis) }
        }
        if (timed.isEmpty()) return candidates.first()
        val nearest = timed.minBy { it.second }
        check(nearest.second <= MATCH_WINDOW_MILLIS) {
            "Could not safely match this preview to an Original on the camera"
        }
        return nearest.first
    }

    private fun parseStillOriginal(element: kotlinx.serialization.json.JsonElement): CameraContentOriginal? {
        val item = element as? JsonObject ?: return null
        if (item["contentKind"]?.jsonPrimitive?.contentOrNull != "still") return null
        val originals = (item["content"] as? JsonObject)?.get("original") as? JsonArray ?: return null
        val jpeg = originals.mapNotNull { it as? JsonObject }.firstOrNull { original ->
            original["stillObject"]?.jsonPrimitive?.contentOrNull.equals("jpeg", ignoreCase = true)
        } ?: return null
        val url = jpeg["url"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        return CameraContentOriginal(
            contentUri = item["uri"]?.jsonPrimitive?.contentOrNull ?: return null,
            originalUrl = URI(url),
            fileName = jpeg["fileName"]?.jsonPrimitive?.contentOrNull,
            createdAtEpochMillis = parseCameraTime(item["createdTime"]?.jsonPrimitive?.contentOrNull),
        )
    }

    private suspend fun call(method: String, version: String, params: JsonArray): JsonArray {
        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("method", method)
            put("params", params)
            put("id", id)
            put("version", version)
        }.toString()
        val response = json.parseToJsonElement(transport.postJson(endpoint, body, REQUEST_TIMEOUT_MILLIS)).jsonObject
        response["error"]?.jsonArray?.let { error ->
            val code = error.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            val message = error.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "Contents Transfer failed"
            throw CameraApiException(code, message)
        }
        return response["result"]?.jsonArray ?: error("Camera returned no Contents Transfer result")
    }

    private fun parseCameraTime(value: String?): Long? {
        val text = value?.takeIf(String::isNotBlank) ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrNull()
    }

    private companion object {
        const val CONTENT_LOOKBACK = 20
        const val REQUEST_TIMEOUT_MILLIS = 15_000
        const val MATCH_WINDOW_MILLIS = 10 * 60 * 1_000L
    }
}
