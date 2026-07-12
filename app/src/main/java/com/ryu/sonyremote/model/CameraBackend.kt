package com.ryu.sonyremote.model

import java.net.URI

/** Camera-family boundary used by the session layer and future native backends. */
interface CameraBackend {
    suspend fun getAvailableApiList(): Set<String>
    suspend fun negotiateEventVersion(): String
    suspend fun getEvent(longPolling: Boolean): CameraEvent
    suspend fun startRecMode()
    suspend fun stopRecMode(readTimeoutMillis: Int)
    suspend fun startLiveview(): URI
    suspend fun startLiveviewWithSize(size: String): URI = startLiveview()
    suspend fun stopLiveview(readTimeoutMillis: Int)
    suspend fun setLiveviewSize(size: String) {
        throw UnsupportedOperationException("Live-view quality selection is unavailable")
    }
    suspend fun startMovieRecording()
    suspend fun stopMovieRecording()
    suspend fun startContinuousShooting()
    suspend fun stopContinuousShooting(readTimeoutMillis: Int)
    suspend fun actZoom(direction: String, movement: String) {
        throw UnsupportedOperationException("Remote zoom is unavailable")
    }
    suspend fun takePicture(
        availableApis: Set<String>,
        postviewSize: PostviewSizePreference = PostviewSizePreference.Original,
    ): CapturedImage
    suspend fun setPostviewSize(
        availableApis: Set<String>,
        postviewSize: PostviewSizePreference,
    ): String?
    suspend fun loadCapabilities(includeApplicationInfo: Boolean = true): CameraCapabilities
    suspend fun setSetting(id: CameraSettingId, wireValue: String)
    suspend fun getAvailableCameraFunctions(): List<String> = emptyList()
    suspend fun setCameraFunction(function: String) {
        throw UnsupportedOperationException("Camera function switching is unavailable")
    }
}
