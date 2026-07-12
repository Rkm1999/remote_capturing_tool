package com.ryu.sonyremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewRoutingTest {
    @Test
    fun panoramaPreviewAndLiveViewRemainIndependent() {
        val routed = routeCapturePreviews(
            liveView = "live",
            processedPreview = "stitched",
            mode = CaptureMode.Panorama,
        )

        assertEquals("stitched", routed.main)
        assertEquals("live", routed.pip)
    }

    @Test
    fun photoModeUsesLiveViewWithoutPip() {
        val routed = routeCapturePreviews("live", "ignored", CaptureMode.Photo)

        assertEquals("live", routed.main)
        assertNull(routed.pip)
    }
}
