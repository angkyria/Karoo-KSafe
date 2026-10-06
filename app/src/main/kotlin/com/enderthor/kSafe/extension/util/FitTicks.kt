package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/** One pass of the FIT writer: the latest ride-clock value and ride state, and whether this pass
 *  writes a `ksafe_*` record. */
internal data class FitTick(val elapsed: Double, val rideState: RideState?, val writeRecord: Boolean)

/**
 * The FIT writer's passes: one for every ride-clock (`ELAPSED_TIME`) value AND one for every
 * ride-state change, each carrying the latest of both. A pass writes a record when the ride is
 * Recording and the clock shows a value not written yet, so each ride-clock second is written once.
 *
 * Both triggers are needed because the clock and the state reach KSafe on separate streams, in no
 * fixed order. At a resume the first tick can arrive while the state still reads Paused. A writer
 * that only looked at the state when the clock ticked skipped that second, and nothing wrote it
 * once Recording landed: 40 of the 46 records still missing `ksafe_*` across seven Karoo 2 rides
 * (2026-10-06) were that first second after a resume. Passing again on the state change writes it.
 */
internal fun fitTicks(elapsed: Flow<Double>, rideState: Flow<RideState?>): Flow<FitTick> = flow {
    // Sentinel NaN: NaN != NaN, so the first Recording value is always written, even 0.
    var lastRecordElapsed = Double.NaN
    combine(elapsed, rideState) { e, s -> e to s }.collect { (e, s) ->
        val write = s is RideState.Recording && e != lastRecordElapsed
        if (write) lastRecordElapsed = e
        emit(FitTick(e, s, write))
    }
}
