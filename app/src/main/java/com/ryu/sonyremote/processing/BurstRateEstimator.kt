package com.ryu.sonyremote.processing

data class BurstRateKey(
    val cameraModel: String,
    val continuousSpeed: String?,
    val shutterSpeed: String?,
)

data class BurstRateEstimate(
    val frameIntervalMillis: Double,
    val stopLatencyMillis: Double,
    val sampleCount: Int,
) {
    val confidence: Double get() = (sampleCount / CONFIDENT_SAMPLE_COUNT).coerceIn(0.0, 1.0)

    private companion object {
        const val CONFIDENT_SAMPLE_COUNT = 4.0
    }
}

class BurstRateEstimator(
    private val smoothing: Double = DEFAULT_SMOOTHING,
) {
    private val estimates = mutableMapOf<BurstRateKey, BurstRateEstimate>()

    init {
        require(smoothing in 0.0..1.0)
    }

    fun estimate(key: BurstRateKey): BurstRateEstimate? = estimates[key]

    fun record(
        key: BurstRateKey,
        commandHoldMillis: Long,
        stopCommandLatencyMillis: Long,
        importedFrameCount: Int,
    ): BurstRateEstimate? {
        if (commandHoldMillis <= 0 || stopCommandLatencyMillis < 0 || importedFrameCount <= 0) {
            return estimates[key]
        }
        val observedInterval =
            (commandHoldMillis + stopCommandLatencyMillis).toDouble() / importedFrameCount
        if (!observedInterval.isFinite() || observedInterval !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
            return estimates[key]
        }
        val previous = estimates[key]
        val next = if (previous == null) {
            BurstRateEstimate(
                frameIntervalMillis = observedInterval,
                stopLatencyMillis = stopCommandLatencyMillis.toDouble(),
                sampleCount = 1,
            )
        } else {
            BurstRateEstimate(
                frameIntervalMillis = smooth(previous.frameIntervalMillis, observedInterval),
                stopLatencyMillis = smooth(previous.stopLatencyMillis, stopCommandLatencyMillis.toDouble()),
                sampleCount = previous.sampleCount + 1,
            )
        }
        estimates[key] = next
        return next
    }

    fun estimatedHoldMillis(
        key: BurstRateKey,
        requestedFrames: Int,
        fallbackFrameIntervalMillis: Long,
    ): Long {
        require(requestedFrames > 0)
        require(fallbackFrameIntervalMillis > 0)
        val estimate = estimates[key]
        val interval = estimate?.frameIntervalMillis ?: fallbackFrameIntervalMillis.toDouble()
        val stopLatency = estimate?.stopLatencyMillis ?: 0.0
        return (requestedFrames * interval - stopLatency).coerceAtLeast(MIN_HOLD_MILLIS).toLong()
    }

    private fun smooth(previous: Double, observed: Double): Double =
        previous * (1.0 - smoothing) + observed * smoothing

    private companion object {
        const val DEFAULT_SMOOTHING = 0.25
        const val MIN_INTERVAL_MILLIS = 20.0
        const val MAX_INTERVAL_MILLIS = 30_000.0
        const val MIN_HOLD_MILLIS = 100.0
    }
}
