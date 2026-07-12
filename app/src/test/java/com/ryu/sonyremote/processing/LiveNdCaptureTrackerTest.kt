package com.ryu.sonyremote.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNdCaptureTrackerTest {
    @Test
    fun usesExactlyTargetAcceptedFramesAndRetainsLaterFramesAsExtra() {
        val tracker = LiveNdCaptureTracker(targetFrames = 2)
        repeat(2) {
            assertEquals(LiveNdFrameDisposition.Align, tracker.disposition())
            tracker.recordAccepted()
        }

        assertEquals(LiveNdFrameDisposition.Extra, tracker.disposition())
        assertEquals(2, tracker.acceptedFrames)
        assertEquals(1, tracker.extraFrames)
        assertFalse(tracker.needsMoreFrames)
    }

    @Test
    fun alignmentRejectionStillRequiresAnotherAcceptedFrame() {
        val tracker = LiveNdCaptureTracker(targetFrames = 2)
        tracker.recordRejected()
        tracker.recordAccepted()

        assertTrue(tracker.needsMoreFrames)
        assertEquals(1, tracker.rejectedFrames)
        assertEquals(1, tracker.acceptedFrames)
        tracker.recordAccepted()
        assertFalse(tracker.needsMoreFrames)
    }
}
