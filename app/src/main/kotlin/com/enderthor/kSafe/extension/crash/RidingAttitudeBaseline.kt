package com.enderthor.kSafe.extension.crash

import kotlin.math.acos
import kotlin.math.sqrt

/** A gravity direction in device axes (m/s², gravity included). */
data class Vec3(val x: Double, val y: Double, val z: Double)

/**
 * SHADOW-ONLY (2026-10-18): the rider's riding attitude for the current ride, learned
 * from the pre-impact references of impacts taken at riding speed. Logged next to the
 * silence orientation so a field sweep can measure how far a confirm's silence vector
 * sits from riding attitude — the "tilted pre-reference" FP class (`dea8f7_bfa76e`:
 * reference captured with the bike pitched nose-up, silence = bike back on its wheels).
 * It drives NO decision: an earlier design that disabled the on-side paths on a match
 * was rejected in review because it can lose a crash whose bike keeps rolling.
 *
 * Baseline = per-axis median of the last [capacity] references; null below [minSamples].
 * Main-thread only, like the rest of the crash pipeline.
 */
class RidingAttitudeBaseline(
    private val capacity: Int = 20,
    private val minSamples: Int = 3,
) {
    private val refs = ArrayDeque<Vec3>()

    val size: Int get() = refs.size

    fun add(x: Double, y: Double, z: Double) {
        if (refs.size == capacity) refs.removeFirst()
        refs.addLast(Vec3(x, y, z))
    }

    fun clear() = refs.clear()

    fun baseline(): Vec3? {
        if (refs.size < minSamples) return null
        return Vec3(median { it.x }, median { it.y }, median { it.z })
    }

    /** Angle (degrees) between (x, y, z) and the baseline; -1.0 when either is unavailable. */
    fun deviationDeg(x: Double, y: Double, z: Double, base: Vec3? = baseline()): Double {
        val b = base ?: return -1.0
        val m = sqrt(x * x + y * y + z * z)
        val bm = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (m.isNaN() || m < 1e-6 || bm < 1e-6) return -1.0
        val cos = ((x * b.x + y * b.y + z * b.z) / (m * bm)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private inline fun median(axis: (Vec3) -> Double): Double {
        val v = refs.map(axis).sorted()
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
    }
}
