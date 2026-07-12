package com.ryu.sonyremote.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCapabilitiesTest {
    @Test
    fun capabilityRefreshRetainsEventDerivedZoomState() {
        val previous = CameraCapabilities(
            zoomPosition = 42,
            zoomSetting = "On:Clear Image Zoom",
            zoomBoxCount = 2,
            zoomBoxIndex = 1,
        )

        val refreshed = CameraCapabilities(availableApis = setOf("actZoom"))
            .retainDynamicZoomFrom(previous)

        assertEquals(42, refreshed.zoomPosition)
        assertEquals("On:Clear Image Zoom", refreshed.zoomSetting)
        assertEquals(2, refreshed.zoomBoxCount)
        assertEquals(1, refreshed.zoomBoxIndex)
    }

    @Test
    fun freshZoomEventOverridesRetainedState() {
        val previous = CameraCapabilities(zoomPosition = 42, zoomBoxIndex = 0)
        val refreshed = CameraCapabilities(zoomPosition = 70, zoomBoxIndex = 1)
            .retainDynamicZoomFrom(previous)

        assertEquals(70, refreshed.zoomPosition)
        assertEquals(1, refreshed.zoomBoxIndex)
    }
}
