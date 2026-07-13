package com.ryu.sonyremote.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDenoisePolicyTest {
    @Test
    fun offNeverRuns() {
        assertFalse(shouldAutoDenoise(AutoDenoiseMode.Off, iso = 12800, threshold = 1600))
    }

    @Test
    fun alwaysRunsWithoutIsoMetadata() {
        assertTrue(shouldAutoDenoise(AutoDenoiseMode.Always, iso = null, threshold = 1600))
    }

    @Test
    fun thresholdRunsAtAndAboveSelectedIso() {
        assertFalse(shouldAutoDenoise(AutoDenoiseMode.IsoThreshold, iso = 800, threshold = 1600))
        assertTrue(shouldAutoDenoise(AutoDenoiseMode.IsoThreshold, iso = 1600, threshold = 1600))
        assertTrue(shouldAutoDenoise(AutoDenoiseMode.IsoThreshold, iso = 3200, threshold = 1600))
    }

    @Test
    fun thresholdSkipsWhenIsoMetadataIsMissing() {
        assertFalse(shouldAutoDenoise(AutoDenoiseMode.IsoThreshold, iso = null, threshold = 1600))
    }
}
