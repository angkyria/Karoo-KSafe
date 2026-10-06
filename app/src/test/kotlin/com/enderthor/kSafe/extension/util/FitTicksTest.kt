package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [fitTicks] — which ride-clock values the FIT writer turns into `ksafe_*` records.
 *
 * Why this matters: intervals.icu zero-fills a record that lacks the fields, so every missed
 * second draws a notch to zero on the fueling graphs. On 2026-10-06, 40 of the 46 records still
 * missing them across seven Karoo 2 rides were the first record after a resume: the ride clock
 * and the ride state reach KSafe on separate streams, and the resume tick could arrive while the
 * state still read Paused. The writer only looked at the state when the clock ticked, so nothing
 * wrote that second once Recording landed.
 *
 * Each step is delivered and fully processed before the next, so the order is exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FitTicksTest {

    private sealed interface Step
    private data class Clock(val elapsedMs: Double) : Step
    private data class State(val rideState: RideState?) : Step

    /** Runs [steps] through [fitTicks] and returns the ride-clock values written as records. */
    private suspend fun TestScope.recordsWritten(initial: RideState?, vararg steps: Step): List<Double> {
        val elapsed = MutableSharedFlow<Double>()
        val rideState = MutableStateFlow(initial)
        val ticks = mutableListOf<FitTick>()
        backgroundScope.launch { fitTicks(elapsed, rideState).toList(ticks) }
        runCurrent()
        for (step in steps) {
            when (step) {
                is Clock -> elapsed.emit(step.elapsedMs)
                is State -> rideState.value = step.rideState
            }
            runCurrent()
        }
        return ticks.filter { it.writeRecord }.map { it.elapsed }
    }

    @Test
    fun `the first second after a resume is written when the Recording state lands after its tick`() = runTest {
        val written = recordsWritten(
            RideState.Recording,
            Clock(0.0), Clock(1_000.0),
            State(RideState.Paused(auto = true)),
            Clock(2_000.0),                 // the resume tick, while the state still reads Paused
            State(RideState.Recording),
            Clock(3_000.0),
        )
        assertEquals(listOf(0.0, 1_000.0, 2_000.0, 3_000.0), written)
    }

    @Test
    fun `a resume writes each second once when the Recording state lands first`() = runTest {
        val written = recordsWritten(
            RideState.Recording,
            Clock(5_000.0),
            State(RideState.Paused(auto = true)),
            State(RideState.Recording),     // re-delivers 5 000 ms, which is already written
            Clock(6_000.0),
        )
        assertEquals(listOf(5_000.0, 6_000.0), written)
    }

    @Test
    fun `nothing is written before the ride state reads Recording`() = runTest {
        // A mid-ride service rebind, or the ride start: the state is published only after
        // handleRideState started or restored the trackers, and must not be pre-empted.
        val written = recordsWritten(
            null,
            Clock(0.0), Clock(1_000.0),
            State(RideState.Recording),
            Clock(2_000.0),
        )
        assertEquals(listOf(1_000.0, 2_000.0), written)
    }
}
