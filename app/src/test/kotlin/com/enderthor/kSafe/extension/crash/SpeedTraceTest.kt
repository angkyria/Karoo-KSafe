package com.enderthor.kSafe.extension.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedTraceTest {

    @Test
    fun `records one value per period and returns the trace once at the end`() {
        val trace = SpeedTrace(periodMs = 1_000L, durationMs = 3_000L)
        trace.start(10_000L)
        assertNull(trace.offer(10_000L, 20.6))
        assertNull("same period: not recorded", trace.offer(10_400L, 19.0))
        assertNull(trace.offer(11_000L, 14.2))
        assertNull(trace.offer(12_100L, 5.7))
        assertEquals("20.6|14.2|5.7", trace.offer(13_000L, 9.9))
        assertNull("closed after it was returned", trace.offer(14_000L, 9.9))
        assertFalse(trace.isOpen)
    }

    @Test
    fun `a stalled feed shows up as gaps instead of compressing time`() {
        val trace = SpeedTrace(periodMs = 1_000L, durationMs = 10_000L)
        trace.start(0L)
        trace.offer(0L, 20.0)
        trace.offer(3_200L, 4.0)
        assertEquals("20.0|-|-|4.0", trace.close())
    }

    @Test
    fun `closing an idle trace returns nothing`() {
        val trace = SpeedTrace(periodMs = 1_000L, durationMs = 10_000L)
        assertNull(trace.close())
        assertNull(trace.offer(5_000L, 10.0))
    }

    @Test
    fun `restarting drops the previous values`() {
        val trace = SpeedTrace(periodMs = 1_000L, durationMs = 10_000L)
        trace.start(0L)
        trace.offer(0L, 20.0)
        trace.start(50_000L)
        trace.offer(50_000L, 7.5)
        assertEquals("7.5", trace.close())
        assertEquals("the start survives the close for the row's age", 50_000L, trace.startedAtMs)
    }
}
