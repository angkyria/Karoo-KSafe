package com.enderthor.kSafe.extension.util

/**
 * Calorie-only rule for a 0 W reading from a live power meter.
 *
 * [CarbBurnEstimator] treats 0 W as unusable and falls through to the HR tiers, so a
 * meter latched at 0 after a dropout can't pin carb burn to zero on a climb. For the
 * CALORIE counter that same fall-through credits every coasting tick at the HR rate,
 * which is still elevated just after an effort: with `KSafe (HR)` the FIT
 * total_calories ran 1-7 % above the ride's kJ, more on rides with more coasting.
 *
 * A 0 W reading counts as coasting (0 kcal — no work was done) when the meter
 * produced power within [LIVE_METER_ZERO_WINDOW_MS]. Past that, the meter is treated
 * as latched and the HR fallback applies as before. Never feeds the carb path.
 */
object CoastingCalories {

    /** Longest run of 0 W still read as coasting. Coasts are mostly seconds long;
     *  a meter stuck at 0 W hands back to the HR estimate after two minutes. */
    const val LIVE_METER_ZERO_WINDOW_MS = 120_000L

    /**
     * @param freshPowerW Latest power reading if it is fresh (null when stale or absent).
     * @param nowMs Current wall-clock time.
     * @param lastNonZeroPowerMs Wall-clock of the last reading above 0 W; 0 when none yet.
     */
    fun isCoasting(freshPowerW: Int?, nowMs: Long, lastNonZeroPowerMs: Long): Boolean {
        if (freshPowerW == null || freshPowerW > 0) return false
        if (lastNonZeroPowerMs <= 0L) return false
        return nowMs - lastNonZeroPowerMs <= LIVE_METER_ZERO_WINDOW_MS
    }
}
