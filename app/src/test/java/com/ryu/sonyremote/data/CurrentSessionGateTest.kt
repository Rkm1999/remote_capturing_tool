package com.ryu.sonyremote.data

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentSessionGateTest {
    @Test
    fun stalePublisherCannotRestoreInvalidatedSession() {
        val gate = CurrentSessionGate<Any>()
        val session = Any()
        var published = false
        gate.install(session) { }

        assertTrue(gate.invalidate(session) { })
        assertFalse(gate.withCurrent(session) { published = true })

        assertFalse(published)
        assertNull(gate.current())
    }

    @Test
    fun lateLossCannotInvalidateReplacementSession() {
        val gate = CurrentSessionGate<Any>()
        val oldSession = Any()
        val newSession = Any()
        gate.install(oldSession) { }
        gate.install(newSession) { }

        assertFalse(gate.invalidate(oldSession) { })

        assertTrue(gate.isCurrent(newSession))
    }

    @Test
    fun inFlightStalePublisherLosesRaceWithInvalidation() {
        val gate = CurrentSessionGate<Any>()
        val session = Any()
        val captured = CountDownLatch(1)
        val complete = CountDownLatch(1)
        var published = false
        gate.install(session) { }
        val publisher = thread {
            captured.countDown()
            complete.await()
            published = gate.withCurrent(session) { }
        }

        captured.await()
        gate.invalidate(session) { }
        complete.countDown()
        publisher.join()

        assertFalse(published)
        assertNull(gate.current())
    }

    @Test
    fun classifiesLiveViewEndFromJobAndNetworkState() {
        assertEquals(
            LiveViewEndDecision.IntentionalStop,
            classifyLiveViewEnd(coroutineActive = false, networkAvailable = false),
        )
        assertEquals(
            LiveViewEndDecision.KeepSession,
            classifyLiveViewEnd(coroutineActive = true, networkAvailable = true),
        )
        assertEquals(
            LiveViewEndDecision.NetworkLost,
            classifyLiveViewEnd(coroutineActive = true, networkAvailable = false),
        )
    }
}
