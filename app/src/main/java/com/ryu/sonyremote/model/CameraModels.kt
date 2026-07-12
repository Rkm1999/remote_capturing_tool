package com.ryu.sonyremote.model

import java.net.URI

data class SonyCameraDevice(
    val friendlyName: String,
    val modelName: String,
    val descriptionUri: URI,
    val endpoints: Map<String, URI>,
) {
    fun endpoint(service: String): URI =
        requireNotNull(endpoints[service]) { "Camera did not advertise the $service service" }
}

enum class CameraSettingId(val label: String) {
    ShootMode("Capture type"),
    ContShootingMode("Drive"),
    ContShootingSpeed("Burst speed"),
    ExposureMode("Mode"),
    FNumber("Aperture"),
    ShutterSpeed("Shutter"),
    IsoSpeedRate("ISO"),
    ExposureCompensation("Exposure compensation"),
}

data class CameraSettingOption(
    val label: String,
    val wireValue: String,
)

data class CameraSetting(
    val id: CameraSettingId,
    val currentWireValue: String,
    val options: List<CameraSettingOption>,
    val isWritable: Boolean = true,
) {
    val currentLabel: String =
        options.firstOrNull { it.wireValue == currentWireValue }?.label ?: currentWireValue
}

data class CameraCapabilities(
    val availableApis: Set<String> = emptySet(),
    val settings: Map<CameraSettingId, CameraSetting> = emptyMap(),
    val applicationName: String? = null,
    val applicationVersion: String? = null,
    val contentsTransferAvailable: Boolean = false,
    val zoomPosition: Int? = null,
    val zoomSetting: String? = null,
    val zoomBoxCount: Int? = null,
    val zoomBoxIndex: Int? = null,
    val liveviewSize: String? = null,
    val availableLiveviewSizes: List<String> = emptyList(),
) {
    val canLiveView: Boolean get() =
        "startLiveview" in availableApis || "startLiveviewWithSize" in availableApis
    val canTakePicture: Boolean
        get() {
            if ("actTakePicture" !in availableApis) return false
            val driveMode = settings[CameraSettingId.ContShootingMode]
            if (driveMode != null) {
                return driveMode.currentWireValue.equals("Single", ignoreCase = true)
            }
            return "getAvailableContShootingMode" !in availableApis
        }
    val canStartMovie: Boolean get() = "startMovieRec" in availableApis
    val canStopMovie: Boolean get() = "stopMovieRec" in availableApis
    val canStartContinuousShooting: Boolean get() = "startContShooting" in availableApis
    val canStopContinuousShooting: Boolean get() = "stopContShooting" in availableApis
    val canRemoteZoom: Boolean get() = "actZoom" in availableApis
    val canSetLiveviewSize: Boolean get() =
        ("setLiveviewSize" in availableApis || "startLiveviewWithSize" in availableApis) &&
            availableLiveviewSizes.isNotEmpty()
}

fun CameraCapabilities.retainDynamicZoomFrom(previous: CameraCapabilities): CameraCapabilities = copy(
    zoomPosition = zoomPosition ?: previous.zoomPosition,
    zoomSetting = zoomSetting ?: previous.zoomSetting,
    zoomBoxCount = zoomBoxCount ?: previous.zoomBoxCount,
    zoomBoxIndex = zoomBoxIndex ?: previous.zoomBoxIndex,
)

data class LiveViewFrame(
    val jpeg: ByteArray,
    val sequenceNumber: Int,
    val timestampMillis: Long,
)

data class CameraEvent(
    val remoteCaptures: List<RemoteCapture>,
    val cameraStatus: String? = null,
    val availableApis: Set<String>? = null,
    val continuousShootingMode: String? = null,
    val zoomPosition: Int? = null,
    val zoomSetting: String? = null,
    val zoomBoxCount: Int? = null,
    val zoomBoxIndex: Int? = null,
    val eventVersion: String = "1.0",
) {
    val bodyCaptureUris: List<URI> get() = remoteCaptures.map(RemoteCapture::postviewUri)
    val captureKinds: Set<CameraCaptureEventKind> get() = remoteCaptures.mapTo(mutableSetOf()) { it.kind }
}

data class RemoteCapture(
    val kind: CameraCaptureEventKind,
    val postviewUri: URI,
    val thumbnailUri: URI? = null,
)

enum class CameraCaptureEventKind {
    Single,
    Continuous,
}

enum class PostviewSizePreference(val wireValue: String) {
    Original("Original"),
    FastPreview("2M"),
}

enum class OutputImageFormat { Jpeg, Webp }

data class CapturedImage(
    val remoteUri: URI,
    val postviewSize: String?,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val originalSizeRequested: Boolean
        get() = postviewSize.equals(PostviewSizePreference.Original.wireValue, ignoreCase = true)
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object WaitingForWifi : ConnectionState
    data object Discovering : ConnectionState
    data class Connecting(val description: String) : ConnectionState
    data class Ready(
        val device: SonyCameraDevice,
        val capabilities: CameraCapabilities,
    ) : ConnectionState

    data class Failed(val message: String, val recoverable: Boolean = true) : ConnectionState
}
