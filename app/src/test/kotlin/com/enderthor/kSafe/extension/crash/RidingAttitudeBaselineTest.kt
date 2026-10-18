package com.enderthor.kSafe.extension.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidingAttitudeBaselineTest {

    @Test
    fun `no baseline until the minimum number of references`() {
        val b = RidingAttitudeBaseline(capacity = 20, minSamples = 3)
        b.add(0.17, 2.64, 9.29)
        b.add(0.10, 2.70, 9.30)
        assertNull(b.baseline())
        assertEquals(-1.0, b.deviationDeg(1.35, 3.75, 8.98), 0.0)
    }

    @Test
    fun `baseline is the per-axis median`() {
        val b = RidingAttitudeBaseline(capacity = 20, minSamples = 3)
        b.add(0.0, 2.0, 9.0)
        b.add(5.0, 3.0, 9.5)   // outlier on x
        b.add(1.0, 2.5, 9.2)
        val base = b.baseline()!!
        assertEquals(1.0, base.x, 1e-9)
        assertEquals(2.5, base.y, 1e-9)
        assertEquals(9.2, base.z, 1e-9)
    }

    @Test
    fun `keeps only the most recent references`() {
        val b = RidingAttitudeBaseline(capacity = 3, minSamples = 3)
        repeat(3) { b.add(0.0, 0.0, 9.81) }
        repeat(3) { b.add(9.81, 0.0, 0.0) }   // remount: old attitude fully replaced
        assertEquals(3, b.size)
        assertEquals(9.81, b.baseline()!!.x, 1e-9)
    }

    @Test
    fun `dea8f7 field case - silence matches riding attitude, pre-reference does not`() {
        // 2026-10-13 dea8f7_bfa76e: riding-attitude impacts vs the tilted pre-reference
        // (bike pitched nose-up) and the silence vector (bike back on its wheels).
        val b = RidingAttitudeBaseline(capacity = 20, minSamples = 3)
        b.add(0.17, 2.64, 9.29)
        b.add(-0.06, 1.14, 10.08)
        b.add(0.02, 3.14, 9.57)
        assertTrue(b.deviationDeg(1.35, 3.75, 8.98) < 20.0)
        assertTrue(b.deviationDeg(3.85, 8.05, 2.41) > 45.0)
    }

    @Test
    fun `degenerate or NaN silence vector yields the sentinel`() {
        val b = RidingAttitudeBaseline(capacity = 20, minSamples = 1)
        b.add(0.0, 0.0, 9.81)
        assertEquals(-1.0, b.deviationDeg(0.0, 0.0, 0.0), 0.0)
        assertEquals(-1.0, b.deviationDeg(Double.NaN, Double.NaN, Double.NaN), 0.0)
    }

    @Test
    fun `clear forgets the ride`() {
        val b = RidingAttitudeBaseline(capacity = 20, minSamples = 1)
        b.add(0.0, 0.0, 9.81)
        b.clear()
        assertEquals(0, b.size)
        assertNull(b.baseline())
    }
}
