package com.ryu.sonyremote.protocol

import com.ryu.sonyremote.model.CameraCapabilities
import com.ryu.sonyremote.model.CameraBackend
import com.ryu.sonyremote.model.CameraCaptureEventKind
import com.ryu.sonyremote.model.RemoteCapture
import com.ryu.sonyremote.model.CameraEvent
import com.ryu.sonyremote.model.CameraSetting
import com.ryu.sonyremote.model.CameraSettingId
import com.ryu.sonyremote.model.CameraSettingOption
import com.ryu.sonyremote.model.CapturedImage
import com.ryu.sonyremote.model.PostviewSizePreference
import java.net.URI
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface JsonRpcTransport {
    suspend fun postJson(endpoint: URI, body: String, readTimeoutMillis: Int): String
}

class CameraApiException(
    val code: Int,
    message: String,
) : Exception(message) {
    val isUnsupported: Boolean get() = code == 12 || code == 14 || code == 15
}

class ScalarWebApiClient(
    private val cameraEndpoint: URI,
    private val transport: JsonRpcTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CameraBackend {
    private val nextRequestId = AtomicInteger(1)
    @Volatile private var eventVersion = EVENT_VERSION_FALLBACK

    override suspend fun getAvailableApiList(): Set<String> =
        call("getAvailableApiList")
            .getOrNull(0)
            ?.asArrayOrNull()
            .orEmpty()
            .mapNotNull { it.asStringOrNull() }
            .toSet()

    override suspend fun negotiateEventVersion(): String {
        try {
            val versions = call("getVersions")
                .getOrNull(0)
                ?.asArrayOrNull()
                .orEmpty()
                .mapNotNull { it.asStringOrNull() }
            eventVersion = if (EVENT_VERSION_CONTINUOUS in versions) {
                val methods = call("getMethodTypes", stringParams(EVENT_VERSION_CONTINUOUS))
                val supportsExtendedEvents = methods.any { method ->
                    val definition = method.asArrayOrNull()
                    definition?.getOrNull(0)?.asStringOrNull() == "getEvent" &&
                        definition.getOrNull(3)?.asStringOrNull() == EVENT_VERSION_CONTINUOUS
                }
                if (supportsExtendedEvents) EVENT_VERSION_CONTINUOUS else EVENT_VERSION_FALLBACK
            } else {
                EVENT_VERSION_FALLBACK
            }
        } catch (error: CameraApiException) {
            if (error.code !in EVENT_NEGOTIATION_FALLBACK_ERRORS) throw error
            eventVersion = EVENT_VERSION_FALLBACK
        }
        return eventVersion
    }

    override suspend fun getEvent(longPolling: Boolean): CameraEvent {
        val params = buildJsonArray { add(JsonPrimitive(longPolling)) }
        val timeout = if (longPolling) EVENT_LONG_POLL_TIMEOUT_MILLIS else RPC_READ_TIMEOUT_MILLIS
        val selectedVersion = eventVersion
        val result = try {
            call("getEvent", params, selectedVersion, timeout)
        } catch (error: CameraApiException) {
            if (selectedVersion == EVENT_VERSION_FALLBACK || error.code != UNSUPPORTED_VERSION) throw error
            eventVersion = EVENT_VERSION_FALLBACK
            call("getEvent", params, EVENT_VERSION_FALLBACK, timeout)
        }
        val captures = result.bodyCaptures()
        return CameraEvent(
            remoteCaptures = captures,
            cameraStatus = result.eventString("cameraStatus", "cameraStatus"),
            availableApis = result.eventStringArray("availableApiList", "names")?.toSet(),
            continuousShootingMode = result.eventString("contShootingMode", "contShootingMode"),
            zoomPosition = result.eventInt("zoomInformation", "zoomPosition"),
            zoomSetting = result.eventString("zoomSetting", "zoom"),
            zoomBoxCount = result.eventInt("zoomInformation", "zoomNumberBox"),
            zoomBoxIndex = result.eventInt("zoomInformation", "zoomIndexCurrentBox")
                ?: result.eventInt("zoomInformation", "zoomIndexCurrent"),
            eventVersion = eventVersion,
        )
    }

    override suspend fun startRecMode() {
        call("startRecMode")
    }

    override suspend fun stopRecMode(readTimeoutMillis: Int) {
        call("stopRecMode", readTimeoutMillis = readTimeoutMillis)
    }

    override suspend fun startLiveview(): URI {
        val value = call("startLiveview").getOrNull(0)?.asStringOrNull()
        return URI(requireNotNull(value) { "Camera returned no live-view URL" })
    }

    override suspend fun startLiveviewWithSize(size: String): URI {
        val value = call("startLiveviewWithSize", stringParams(size)).getOrNull(0)?.asStringOrNull()
        return URI(requireNotNull(value) { "Camera returned no live-view URL" })
    }

    override suspend fun stopLiveview(readTimeoutMillis: Int) {
        call("stopLiveview", readTimeoutMillis = readTimeoutMillis)
    }

    override suspend fun setLiveviewSize(size: String) {
        call("setLiveviewSize", stringParams(size))
    }

    override suspend fun startMovieRecording() {
        val result = call("startMovieRec")
        if (result.getOrNull(0)?.jsonPrimitive?.intOrNull != 0) {
            throw CameraApiException(6, "Camera did not accept movie recording start")
        }
    }

    override suspend fun stopMovieRecording() {
        val result = call("stopMovieRec")
        if (result.getOrNull(0)?.asStringOrNull() != "") {
            throw CameraApiException(6, "Camera did not accept movie recording stop")
        }
    }

    override suspend fun startContinuousShooting() {
        call("startContShooting")
    }

    override suspend fun stopContinuousShooting(readTimeoutMillis: Int) {
        call("stopContShooting", readTimeoutMillis = readTimeoutMillis)
    }

    override suspend fun takePicture(
        availableApis: Set<String>,
        postviewSize: PostviewSizePreference,
    ): CapturedImage {
        requireSingleDriveMode(availableApis)
        val selectedPostviewSize = requestPostviewSize(availableApis, postviewSize)
        val result = try {
            call("actTakePicture", readTimeoutMillis = CAPTURE_REQUEST_TIMEOUT_MILLIS)
        } catch (error: CameraApiException) {
            if (error.code != CAPTURE_STILL_IN_PROGRESS) throw error
            awaitLongExposure()
        } catch (_: SocketTimeoutException) {
            awaitLongExposure()
        }
        val uri = result.firstPostviewUri()
        return CapturedImage(remoteUri = uri, postviewSize = selectedPostviewSize)
    }

    override suspend fun setPostviewSize(
        availableApis: Set<String>,
        postviewSize: PostviewSizePreference,
    ): String? = requestPostviewSize(availableApis, postviewSize)

    private suspend fun requireSingleDriveMode(apis: Set<String>) {
        if ("getAvailableContShootingMode" !in apis) return
        val state = call("getAvailableContShootingMode").getOrNull(0) as? JsonObject
        val current = requireNotNull(state?.get("contShootingMode")?.asStringOrNull()) {
            "Camera returned an invalid drive mode"
        }
        check(current.equals("Single", ignoreCase = true)) {
            "Select Single drive mode before taking a photo"
        }
    }

    override suspend fun loadCapabilities(includeApplicationInfo: Boolean): CameraCapabilities {
        val apis = getAvailableApiList()
        val applicationInfo = if (includeApplicationInfo && "getApplicationInfo" in apis) {
            runCatching { call("getApplicationInfo") }.getOrNull()
        } else {
            null
        }
        val settings = buildMap {
            loadStringSetting(apis, CameraSettingId.ShootMode, "getAvailableShootMode", "setShootMode")?.let {
                put(it.id, it)
            }
            loadContShootingMode(apis)?.let { put(it.id, it) }
            loadContShootingSpeed(apis)?.let { put(it.id, it) }
            loadExposureMode(apis)?.let {
                put(it.id, it)
            }
            loadStringSetting(apis, CameraSettingId.FNumber, "getAvailableFNumber", "setFNumber")?.let {
                put(it.id, it)
            }
            loadStringSetting(apis, CameraSettingId.ShutterSpeed, "getAvailableShutterSpeed", "setShutterSpeed")?.let {
                put(it.id, it)
            }
            loadStringSetting(apis, CameraSettingId.IsoSpeedRate, "getAvailableIsoSpeedRate", "setIsoSpeedRate")?.let {
                put(it.id, it)
            }
            loadExposureCompensation(apis)?.let { put(it.id, it) }
        }
        val zoomSetting = if ("getAvailableZoomSetting" in apis) {
            runCatching {
                call("getAvailableZoomSetting").getOrNull(0)
                    ?.let { it as? JsonObject }
                    ?.get("zoom")
                    ?.asStringOrNull()
            }.getOrNull()
        } else if ("getZoomSetting" in apis) {
            runCatching { call("getZoomSetting").getOrNull(0)?.asStringOrNull() }.getOrNull()
        } else null
        val liveviewSizeState = if ("getAvailableLiveviewSize" in apis) {
            runCatching { call("getAvailableLiveviewSize").getOrNull(0) as? JsonObject }.getOrNull()
        } else null
        val reportedLiveviewSize = liveviewSizeState?.get("liveviewSize")?.asStringOrNull()
        val reportedLiveviewSizes = liveviewSizeState?.get("liveviewSizeCandidates")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.mapNotNull { it.asStringOrNull() }
            .orEmpty()
        val availableLiveviewSizes = reportedLiveviewSizes.ifEmpty {
            if ("startLiveviewWithSize" in apis) listOf("M", "L") else emptyList()
        }
        val liveviewSize = reportedLiveviewSize ?: availableLiveviewSizes.firstOrNull()
        return CameraCapabilities(
            availableApis = apis,
            settings = settings,
            applicationName = applicationInfo?.getOrNull(0)?.asStringOrNull(),
            applicationVersion = applicationInfo?.getOrNull(1)?.asStringOrNull(),
            zoomSetting = zoomSetting,
            liveviewSize = liveviewSize,
            availableLiveviewSizes = availableLiveviewSizes,
        )
    }

    override suspend fun setSetting(id: CameraSettingId, wireValue: String) {
        when (id) {
            CameraSettingId.ShootMode -> call("setShootMode", stringParams(wireValue))
            CameraSettingId.ContShootingMode -> call(
                "setContShootingMode",
                buildJsonArray {
                    add(buildJsonObject { put("contShootingMode", wireValue) })
                },
            )
            CameraSettingId.ContShootingSpeed -> call(
                "setContShootingSpeed",
                buildJsonArray {
                    add(buildJsonObject { put("contShootingSpeed", wireValue) })
                },
            )
            CameraSettingId.ExposureMode -> call("setExposureMode", stringParams(wireValue))
            CameraSettingId.FNumber -> call("setFNumber", stringParams(wireValue))
            CameraSettingId.ShutterSpeed -> call("setShutterSpeed", stringParams(wireValue))
            CameraSettingId.IsoSpeedRate -> call("setIsoSpeedRate", stringParams(wireValue))
            CameraSettingId.ExposureCompensation -> {
                val index = requireNotNull(wireValue.toIntOrNull()) { "Invalid exposure compensation value" }
                call("setExposureCompensation", buildJsonArray { add(JsonPrimitive(index)) })
            }
        }
    }

    override suspend fun actZoom(direction: String, movement: String) {
        require(direction == "in" || direction == "out")
        require(movement == "start" || movement == "stop" || movement == "1shot")
        call(
            "actZoom",
            buildJsonArray {
                add(JsonPrimitive(direction))
                add(JsonPrimitive(movement))
            },
        )
    }

    override suspend fun getAvailableCameraFunctions(): List<String> {
        val result = call("getAvailableCameraFunction")
        return result.getOrNull(1)?.asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull() }
    }

    override suspend fun setCameraFunction(function: String) {
        call("setCameraFunction", stringParams(function))
    }

    private suspend fun requestPostviewSize(
        apis: Set<String>,
        preference: PostviewSizePreference,
    ): String? {
        if ("getAvailablePostviewImageSize" !in apis) return null
        val available = runCatching { call("getAvailablePostviewImageSize") }.getOrNull() ?: return null
        val current = available.getOrNull(0)?.asStringOrNull()
        val options = available.getOrNull(1)?.asArrayOrNull().orEmpty().mapNotNull { it.asStringOrNull() }
        val requested = preference.wireValue.takeIf(options::contains) ?: return current
        if (current == requested || "setPostviewImageSize" !in apis) return current
        call("setPostviewImageSize", stringParams(requested))
        repeat(POSTVIEW_CONFIRM_ATTEMPTS) {
            delay(POSTVIEW_CONFIRM_DELAY_MILLIS)
            val confirmed = runCatching { call("getAvailablePostviewImageSize") }.getOrNull()
            val confirmedSize = confirmed?.getOrNull(0)?.asStringOrNull()
            if (confirmedSize == requested) return confirmedSize
        }
        return current
    }

    private suspend fun awaitLongExposure(): JsonArray {
        val deadlineNanos = System.nanoTime() + CAPTURE_OVERALL_TIMEOUT_MILLIS * NANOS_PER_MILLI
        while (System.nanoTime() < deadlineNanos) {
            try {
                return call("awaitTakePicture", readTimeoutMillis = CAPTURE_REQUEST_TIMEOUT_MILLIS)
            } catch (error: CameraApiException) {
                if (error.code !in TRANSIENT_CAPTURE_ERRORS) throw error
                delay(AWAIT_RETRY_MILLIS)
            } catch (_: SocketTimeoutException) {
                // The camera can hold awaitTakePicture for the duration of a long exposure.
            }
        }
        throw CameraApiException(CAPTURE_STILL_IN_PROGRESS, "Timed out waiting for the long exposure")
    }

    private suspend fun loadContShootingMode(apis: Set<String>): CameraSetting? {
        if ("getAvailableContShootingMode" !in apis) return null
        return runCatching {
            val state = call("getAvailableContShootingMode").getOrNull(0) as? JsonObject
            val current = requireNotNull(state?.get("contShootingMode")?.asStringOrNull())
            val options = state["candidate"]
                ?.asArrayOrNull()
                .orEmpty()
                .mapNotNull { it.asStringOrNull() }
                .map { CameraSettingOption(label = it, wireValue = it) }
            CameraSetting(
                id = CameraSettingId.ContShootingMode,
                currentWireValue = current,
                options = options,
                isWritable = "setContShootingMode" in apis,
            )
        }.getOrNull()
    }

    private suspend fun loadContShootingSpeed(apis: Set<String>): CameraSetting? {
        if ("getAvailableContShootingSpeed" !in apis) return null
        return runCatching {
            val state = call("getAvailableContShootingSpeed").getOrNull(0) as? JsonObject
            val current = requireNotNull(state?.get("contShootingSpeed")?.asStringOrNull())
            val options = state["candidate"]
                ?.asArrayOrNull()
                .orEmpty()
                .mapNotNull { it.asStringOrNull() }
                .map { CameraSettingOption(label = it, wireValue = it) }
            CameraSetting(
                id = CameraSettingId.ContShootingSpeed,
                currentWireValue = current,
                options = options,
                isWritable = "setContShootingSpeed" in apis,
            )
        }.getOrNull()
    }

    private suspend fun loadExposureMode(apis: Set<String>): CameraSetting? {
        loadStringSetting(
            apis,
            CameraSettingId.ExposureMode,
            "getAvailableExposureMode",
            "setExposureMode",
        )?.let { return it }
        if ("getExposureMode" !in apis) return null
        return runCatching {
            val current = requireNotNull(call("getExposureMode").getOrNull(0)?.asStringOrNull())
            CameraSetting(
                id = CameraSettingId.ExposureMode,
                currentWireValue = current,
                options = listOf(CameraSettingOption(label = current, wireValue = current)),
                isWritable = false,
            )
        }.getOrNull()
    }

    private suspend fun loadStringSetting(
        apis: Set<String>,
        id: CameraSettingId,
        method: String,
        setter: String,
    ): CameraSetting? {
        if (method !in apis) return null
        return runCatching {
            val result = call(method)
            val current = requireNotNull(result.getOrNull(0)?.asStringOrNull())
            val options = result.getOrNull(1)
                ?.asArrayOrNull()
                .orEmpty()
                .mapNotNull { it.asStringOrNull() }
                .map { CameraSettingOption(label = it, wireValue = it) }
            CameraSetting(
                id = id,
                currentWireValue = current,
                options = options,
                isWritable = setter in apis,
            )
        }.getOrNull()
    }

    private suspend fun loadExposureCompensation(apis: Set<String>): CameraSetting? {
        if (
            "getAvailableExposureCompensation" !in apis
        ) return null
        return runCatching {
            val result = call("getAvailableExposureCompensation")
            val current = requireNotNull(result.getOrNull(0)?.jsonPrimitive?.intOrNull)
            val upper = requireNotNull(result.getOrNull(1)?.jsonPrimitive?.intOrNull)
            val lower = requireNotNull(result.getOrNull(2)?.jsonPrimitive?.intOrNull)
            val step = requireNotNull(result.getOrNull(3)?.jsonPrimitive?.intOrNull)
            val options = (lower..upper).map { index ->
                CameraSettingOption(
                    label = formatExposureCompensation(index, step),
                    wireValue = index.toString(),
                )
            }
            CameraSetting(
                id = CameraSettingId.ExposureCompensation,
                currentWireValue = current.toString(),
                options = options,
                isWritable = "setExposureCompensation" in apis,
            )
        }.getOrNull()
    }

    private suspend fun call(
        method: String,
        params: JsonArray = EMPTY_PARAMS,
        version: String = "1.0",
        readTimeoutMillis: Int = RPC_READ_TIMEOUT_MILLIS,
    ): JsonArray {
        val requestId = nextRequestId.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
        val request = buildJsonObject {
            put("method", method)
            put("params", params)
            put("id", requestId)
            put("version", version)
        }
        val response = json.parseToJsonElement(
            transport.postJson(cameraEndpoint, request.toString(), readTimeoutMillis),
        )
            .let { element -> element as? kotlinx.serialization.json.JsonObject }
            ?: throw CameraApiException(6, "Camera returned an invalid JSON-RPC response")
        val responseId = response["id"]?.jsonPrimitive?.intOrNull
        if (responseId != null && responseId != requestId) {
            throw CameraApiException(6, "Camera response ID does not match the request")
        }
        response["error"]?.asArrayOrNull()?.let { error ->
            val code = error.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 1
            val message = error.getOrNull(1)?.asStringOrNull() ?: "Camera API error $code"
            throw CameraApiException(code, message)
        }
        return response["result"]?.asArrayOrNull()
            ?: response["results"]?.asArrayOrNull()
            ?: throw CameraApiException(6, "Camera response contains no result")
    }

    private fun JsonArray.firstPostviewUri(): URI {
        val value = getOrNull(0)
            ?.asArrayOrNull()
            ?.firstOrNull()
            ?.asStringOrNull()
        return URI(requireNotNull(value) { "Camera returned no postview image" })
    }

    private fun JsonElement.bodyCaptures(): List<RemoteCapture> = buildList {
        collectBodyCaptures(this)
    }

    private fun JsonElement.collectBodyCaptures(destination: MutableList<RemoteCapture>) {
        when (this) {
            is JsonArray -> forEach { it.collectBodyCaptures(destination) }
            is JsonObject -> {
                when (get("type")?.asStringOrNull()) {
                    "takePicture" -> {
                        val urls = get("takePictureUrl") as? JsonArray
                            ?: throw invalidBodyCaptureEvent()
                        urls.forEach { element ->
                            destination += RemoteCapture(
                                kind = CameraCaptureEventKind.Single,
                                postviewUri = element.requireCaptureUri(),
                            )
                        }
                    }
                    "contShooting" -> {
                        val urls = get("contShootingUrl") as? JsonArray
                            ?: throw invalidBodyCaptureEvent()
                        urls.forEach { element ->
                            val entry = element as? JsonObject ?: throw invalidBodyCaptureEvent()
                            destination += RemoteCapture(
                                kind = CameraCaptureEventKind.Continuous,
                                postviewUri = entry["postviewUrl"].requireCaptureUri(),
                                thumbnailUri = entry["thumbnailUrl"]?.requireCaptureUri(),
                            )
                        }
                    }
                }
                values.forEach { it.collectBodyCaptures(destination) }
            }
            else -> Unit
        }
    }

    private fun invalidBodyCaptureEvent() =
        CameraApiException(6, "Camera returned an invalid body-capture event")

    private fun JsonElement?.requireCaptureUri(): URI {
        val value = this?.asStringOrNull()?.takeIf(String::isNotBlank)
            ?: throw invalidBodyCaptureEvent()
        val uri = runCatching { URI(value) }.getOrElse { throw invalidBodyCaptureEvent() }
        if (!uri.isAbsolute) throw invalidBodyCaptureEvent()
        return uri
    }

    private fun JsonElement.eventString(type: String, key: String): String? = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.eventString(type, key) }
        is JsonObject -> if (get("type")?.asStringOrNull() == type) {
            get(key)?.asStringOrNull()
        } else {
            values.firstNotNullOfOrNull { it.eventString(type, key) }
        }
        else -> null
    }

    private fun JsonElement.eventStringArray(type: String, key: String): List<String>? = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.eventStringArray(type, key) }
        is JsonObject -> if (get("type")?.asStringOrNull() == type) {
            (get(key) as? JsonArray)?.mapNotNull { it.asStringOrNull() }
        } else {
            values.firstNotNullOfOrNull { it.eventStringArray(type, key) }
        }
        else -> null
    }

    private fun JsonElement.eventInt(type: String, key: String): Int? = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.eventInt(type, key) }
        is JsonObject -> if (get("type")?.asStringOrNull() == type) {
            (get(key) as? JsonPrimitive)?.intOrNull
        } else {
            values.firstNotNullOfOrNull { it.eventInt(type, key) }
        }
        else -> null
    }

    private fun stringParams(value: String): JsonArray = buildJsonArray { add(JsonPrimitive(value)) }

    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun formatExposureCompensation(index: Int, step: Int): String {
        val ev = when (step) {
            1 -> index / 3.0
            2 -> index / 2.0
            else -> index.toDouble()
        }
        return String.format(Locale.US, "%+.1f EV", ev)
    }

    private companion object {
        const val EVENT_VERSION_FALLBACK = "1.0"
        const val EVENT_VERSION_CONTINUOUS = "1.2"
        const val UNSUPPORTED_VERSION = 14
        const val NO_SUCH_METHOD = 12
        const val CAPTURE_STILL_IN_PROGRESS = 40403
        const val CAMERA_NOT_READY = 40401
        const val AWAIT_RETRY_MILLIS = 500L
        const val RPC_READ_TIMEOUT_MILLIS = 5_000
        const val EVENT_LONG_POLL_TIMEOUT_MILLIS = 70_000
        const val CAPTURE_REQUEST_TIMEOUT_MILLIS = 90_000
        const val CAPTURE_OVERALL_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val POSTVIEW_CONFIRM_ATTEMPTS = 10
        const val POSTVIEW_CONFIRM_DELAY_MILLIS = 100L
        val EMPTY_PARAMS = JsonArray(emptyList())
        val TRANSIENT_CAPTURE_ERRORS = setOf(CAPTURE_STILL_IN_PROGRESS, CAMERA_NOT_READY)
        val EVENT_NEGOTIATION_FALLBACK_ERRORS = setOf(NO_SUCH_METHOD, UNSUPPORTED_VERSION)
    }
}
