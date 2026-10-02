package com.enderthor.kSafe.extension.crash

import java.util.Locale

/**
 * Diagnostic-only speed recorder for the `VIGIL_TRACE` calibration row: one value per
 * [periodMs] for [durationMs] after [start], formatted `20.6|14.2|-|5.7` (`-` = no driver
 * sample in that period, so a stalled feed never compresses the timeline). Changes no
 * behaviour. Main-looper only, like its caller `driveMovingVigilance`.
 */
class SpeedTrace(private val periodMs: Long, private val durationMs: Long) {

    private var startMs = NOT_STARTED
    private var nextMs = 0L

    /** Start of the most recent trace; kept after it closes so the row can carry its age. */
    var startedAtMs: Long = 0L
        private set
    private val values = StringBuilder()

    val isOpen: Boolean get() = startMs != NOT_STARTED

    fun start(nowMs: Long) {
        startMs = nowMs
        startedAtMs = nowMs
        nextMs = nowMs
        values.setLength(0)
    }

    /** Records [speedKmh] if a period is due; returns the finished trace exactly once. */
    fun offer(nowMs: Long, speedKmh: Double): String? {
        if (startMs == NOT_STARTED) return null
        if (nowMs - startMs >= durationMs) return close()
        if (nowMs < nextMs) return null
        while (nowMs >= nextMs + periodMs) {
            append("-")
            nextMs += periodMs
        }
        append(String.format(Locale.US, "%.1f", speedKmh))
        nextMs += periodMs
        return null
    }

    /** Ends the trace early; returns what was recorded, or null when none was open. */
    fun close(): String? {
        if (startMs == NOT_STARTED) return null
        startMs = NOT_STARTED
        return values.toString()
    }

    private fun append(v: String) {
        if (values.isNotEmpty()) values.append('|')
        values.append(v)
    }

    private companion object { const val NOT_STARTED = Long.MIN_VALUE }
}
