package com.ryu.sonyremote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PanoramaResourceLimitTest {
    @Test
    fun frameLimitScalesWithHeapAndStaysBounded() {
        assertEquals(4, panoramaFrameLimitForMemory(32L * 1024 * 1024))
        assertEquals(10, panoramaFrameLimitForMemory(120L * 1024 * 1024))
        assertEquals(32, panoramaFrameLimitForMemory(1024L * 1024 * 1024))
    }
}
