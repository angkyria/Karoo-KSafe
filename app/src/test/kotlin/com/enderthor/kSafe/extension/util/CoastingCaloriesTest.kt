package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CoastingCalories]: a fresh 0 W reading from a meter that was
 * producing power moments ago is the rider coasting, and the calorie counter must
 * add nothing for it (the work was 0 J). Without this, [CarbBurnEstimator] drops
 * to the HR tier on every 0 W tick and the FIT total_calories runs above kJ.
 */
class CoastingCaloriesTest {

    private val now = 1_000_000L

    @Test
    fun `zero watts moments after pedalling is coasting`() {
        assertTrue(CoastingCalories.isCoasting(freshPowerW = 0, nowMs = now, lastNonZeroPowerMs = now - 3_000L))
    }

    @Test
    fun `zero watts at the end of the window is still coasting`() {
        val edge = now - CoastingCalories.LIVE_METER_ZERO_WINDOW_MS
        assertTrue(CoastingCalories.isCoasting(freshPowerW = 0, nowMs = now, lastNonZeroPowerMs = edge))
    }

    @Test
    fun `zero watts long after the last power reading is a latched meter, not coasting`() {
        val stale = now - CoastingCalories.LIVE_METER_ZERO_WINDOW_MS - 1_000L
        assertFalse(CoastingCalories.isCoasting(freshPowerW = 0, nowMs = now, lastNonZeroPowerMs = stale))
    }

    @Test
    fun `zero watts from a meter that never produced power is not coasting`() {
        assertFalse(CoastingCalories.isCoasting(freshPowerW = 0, nowMs = now, lastNonZeroPowerMs = 0L))
    }

    @Test
    fun `positive power is not coasting`() {
        assertFalse(CoastingCalories.isCoasting(freshPowerW = 180, nowMs = now, lastNonZeroPowerMs = now))
    }

    @Test
    fun `no fresh power reading is not coasting`() {
        assertFalse(CoastingCalories.isCoasting(freshPowerW = null, nowMs = now, lastNonZeroPowerMs = now - 3_000L))
    }
}
