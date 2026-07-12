package com.ryu.sonyremote.processing

enum class LiveNdFrameDisposition { Align, Extra }

class LiveNdCaptureTracker(val targetFrames: Int) {
    var acceptedFrames: Int = 0
        private set
    var rejectedFrames: Int = 0
        private set
    var extraFrames: Int = 0
        private set
    var consecutiveAlignmentFailures: Int = 0
        private set

    init {
        require(targetFrames >= 2)
    }

    val needsMoreFrames: Boolean get() = acceptedFrames < targetFrames

    fun disposition(): LiveNdFrameDisposition = if (needsMoreFrames) {
        LiveNdFrameDisposition.Align
    } else {
        extraFrames++
        LiveNdFrameDisposition.Extra
    }

    fun recordAccepted() {
        check(needsMoreFrames)
        acceptedFrames++
        consecutiveAlignmentFailures = 0
    }

    fun recordRejected() {
        check(needsMoreFrames)
        rejectedFrames++
        consecutiveAlignmentFailures++
    }
}
