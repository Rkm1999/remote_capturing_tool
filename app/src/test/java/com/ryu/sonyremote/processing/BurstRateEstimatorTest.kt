package com.ryu.sonyremote.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstRateEstimatorTest {
    private val key = BurstRateKey("ILCE-6300", "Hi", "1/250")

    @Test
    fun estimatesHoldFromCompletedImportsAndMeasuredStopLatency() {
        val estimator = BurstRateEstimator(smoothing = 0.5)

        val estimate = estimator.record(
            key = key,
            commandHoldMillis = 900,
            stopCommandLatencyMillis = 100,
            importedFrameCount = 5,
        )

        assertEquals(200.0, estimate?.frameIntervalMillis ?: 0.0, 0.001)
        assertEquals(700L, estimator.estimatedHoldMillis(key, requestedFrames = 4, 750))
    }

    @Test
    fun smoothsLaterSamplesInsteadOfReplacingEstimate() {
        val estimator = BurstRateEstimator(smoothing = 0.25)
        estimator.record(key, commandHoldMillis = 900, stopCommandLatencyMillis = 100, importedFrameCount = 5)

        val estimate = estimator.record(
            key,
            commandHoldMillis = 1_900,
            stopCommandLatencyMillis = 100,
            importedFrameCount = 5,
        )

        assertEquals(250.0, estimate?.frameIntervalMillis ?: 0.0, 0.001)
        assertEquals(2, estimate?.sampleCount)
        assertTrue((estimate?.confidence ?: 0.0) < 1.0)
    }

    @Test
    fun keepsConfigurationsIndependent() {
        val estimator = BurstRateEstimator()
        estimator.record(key, 900, 100, 5)
        val otherSpeed = key.copy(continuousSpeed = "Lo")

        assertNull(estimator.estimate(otherSpeed))
        assertEquals(3_000L, estimator.estimatedHoldMillis(otherSpeed, 4, 750))
    }

    @Test
    fun ignoresSamplesWithoutSuccessfullyImportedFrames() {
        val estimator = BurstRateEstimator()

        assertNull(estimator.record(key, 1_000, 100, 0))
        assertNull(estimator.estimate(key))
    }
}
