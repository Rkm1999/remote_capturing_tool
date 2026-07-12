package com.ryu.sonyremote.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaRenderPlannerTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun plansNaturalResolutionWhenResourcesPermit() {
        val geometry = listOf(
            PanoramaFrameGeometry(1000, 500, identity),
            PanoramaFrameGeometry(1000, 500, identity.copyOf().apply { this[2] = 800.0 }),
        )
        val sources = listOf(PanoramaSourceDimensions(4000, 2000), PanoramaSourceDimensions(4000, 2000))

        val plan = PanoramaRenderPlanner.plan(
            geometry,
            sources,
            PanoramaResourceBudget(512L shl 20, 2L shl 30),
        )

        assertEquals(7200, plan.width)
        assertEquals(2000, plan.height)
        assertTrue(plan.originalResolution)
    }

    @Test
    fun scalesOutputUnderConstrainedHeapWithoutExceedingBudget() {
        val geometry = listOf(
            PanoramaFrameGeometry(1000, 500, identity),
            PanoramaFrameGeometry(1000, 500, identity.copyOf().apply { this[2] = 900.0 }),
        )
        val sources = listOf(PanoramaSourceDimensions(6000, 3000), PanoramaSourceDimensions(6000, 3000))
        val budget = PanoramaResourceBudget(160L shl 20, 500L shl 20)

        val plan = PanoramaRenderPlanner.plan(geometry, sources, budget)

        assertFalse(plan.originalResolution)
        assertTrue(plan.estimatedPeakBytes <= (budget.maxHeapBytes * 0.55).toLong())
        assertTrue(plan.width.toLong() * plan.height <= budget.maxOutputPixels)
    }
}
