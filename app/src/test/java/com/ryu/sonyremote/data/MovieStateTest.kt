package com.ryu.sonyremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieStateTest {
    @Test
    fun authorizesRequestedCommandFromFreshCapabilities() {
        assertEquals(
            MovieCommandDecision.Start,
            decideMovieCommand(requestedStop = false, canStart = true, canStop = false),
        )
        assertEquals(
            MovieCommandDecision.Stop,
            decideMovieCommand(requestedStop = true, canStart = false, canStop = true),
        )
    }

    @Test
    fun doesNotInvertCommandWhenPhysicalStateChanged() {
        assertEquals(
            MovieCommandDecision.AlreadyRecording,
            decideMovieCommand(requestedStop = false, canStart = false, canStop = true),
        )
        assertEquals(
            MovieCommandDecision.AlreadyStopped,
            decideMovieCommand(requestedStop = true, canStart = true, canStop = false),
        )
    }

    @Test
    fun unknownAcceptedTransitionKeepsStopPathAvailable() {
        assertTrue(recordingStateFromCapabilities(canStart = false, canStop = false, fallback = true))
        assertTrue(recordingStateFromCapabilities(canStart = false, canStop = true, fallback = false))
        assertFalse(recordingStateFromCapabilities(canStart = true, canStop = false, fallback = true))
    }
}
