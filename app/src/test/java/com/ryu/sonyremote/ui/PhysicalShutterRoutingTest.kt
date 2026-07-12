package com.ryu.sonyremote.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalShutterRoutingTest {
    private val armedPanorama = CaptureSessionUiState(
        mode = CaptureMode.Panorama,
        isActive = true,
        frameCount = 1,
        targetFrames = 12,
    )

    @Test
    fun routesOnlyIntoAnAlreadyArmedPanorama() {
        assertTrue(route(session = armedPanorama))
        assertFalse(route(session = CaptureSessionUiState()))
        assertFalse(route(selectedMode = CaptureMode.Photo, session = armedPanorama))
        assertFalse(route(session = armedPanorama.copy(isActive = false)))
        assertFalse(route(hasProcessor = false, session = armedPanorama))
    }

    @Test
    fun rejectsRoutingWhilePanoramaIsBusyFinishingOrFull() {
        assertFalse(route(isBusy = true, session = armedPanorama))
        assertFalse(route(session = armedPanorama.copy(isFinishing = true)))
        assertFalse(route(processorFrameCount = 12, session = armedPanorama))
    }

    private fun route(
        selectedMode: CaptureMode = CaptureMode.Panorama,
        session: CaptureSessionUiState,
        hasProcessor: Boolean = true,
        isBusy: Boolean = false,
        processorFrameCount: Int = 1,
    ): Boolean = shouldRoutePhysicalCaptureToPanorama(
        selectedMode = selectedMode,
        session = session,
        hasProcessor = hasProcessor,
        isBusy = isBusy,
        processorFrameCount = processorFrameCount,
        maximumFrames = 12,
    )
}
