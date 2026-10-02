package com.enderthor.kSafe.extension.util

/**
 * Pure helper extracted from [com.enderthor.kSafe.extension.managers.CarbsTracker]
 * and [com.enderthor.kSafe.extension.managers.HydrationTracker]. Both trackers
 * implement the same grid-aligned time-alert + cooldown-gated deficit-alert
 * machinery; lifting it out unblocks unit testing without a Robolectric harness.
 *
 * Two contracts pinned by tests, each implemented as a pure function:
 *
 *  1. **Time-grid alignment** (`currentDueTimeTick`). The schedule is fixed at
 *     `sessionStartMs + N × intervalMs` for N = 1, 2, 3, … Rider logs do NOT
 *     shift the grid. Each tick fires at most once.
 *
 *  2. **Initial-delay FILTER** (not shift). When the rider hasn't logged
 *     anything yet, ticks earlier than `sessionStartMs + initialDelayMs` are
 *     silently dropped — the grid stays anchored to session start. Once the
 *     rider has logged at least one item the filter releases — for later ticks
 *     only: a tick it already dropped stays dropped if the rider logged after it
 *     (`lastRealLogMs`).
 *
 *  3. **Deficit reminder cooldown** (`shouldFireDeficit`). Once the deficit
 *     crosses the rider-configured threshold, subsequent reminder alerts are
 *     gated by a configurable cooldown (`reminderIntervalMs`). The first
 *     alert in a session may additionally be gated by `initialDelayMs` if the
 *     rider hasn't logged anything yet.
 *
 *  4. **Unacknowledged back-off** (`unackedFires`). The flat cooldown alone has
 *     no ceiling: field logs (2026-07-22 sweep) show one rider getting 13
 *     hydration prompts in an hour and another 14 across a 4h45 ride, none
 *     acknowledged — the deficit only grows, so it re-fires until the ride ends.
 *     The cooldown is therefore multiplied by 1 / 2 / 4 as unacknowledged fires
 *     accumulate, capped at ×4 so the reminder never goes fully silent
 *     (dehydration matters most on the long rides where this triggers). Any log
 *     resets the caller's counter, so the next reminder is back at the base
 *     interval — measured from the log itself, never from an older fire whose
 *     backed-off cooldown has already elapsed (see `lastRealLogMs`).
 *
 * **Coincidence resolution** (deficit + time tick on the same call) is NOT
 * handled here — it's the tracker's responsibility because resolving it
 * involves stamping `lastTimeAlertFireMs` to consume the time tick, which is
 * a mutation outside this object's pure-function contract. See
 * `CarbsTracker.tick` / `HydrationTracker.tick` for the deficit-wins logic.
 */
internal object FuelingAlertScheduler {

    /**
     * Returns the timestamp of the time-alert grid tick that's due to fire
     * right now, or `0L` when no tick is due (alert disabled, before the first
     * grid point, already fired this tick, or filtered by the initial-delay
     * window).
     *
     * **Pure**: every input is a parameter; the function mutates nothing and
     * has no side effects.
     */
    fun currentDueTimeTick(
        enabled: Boolean,
        intervalMs: Long,
        sessionStartMs: Long,
        lastTimeAlertFireMs: Long,
        initialDelayMs: Long,
        cumLogged: Int,
        now: Long,
        lastRealLogMs: Long = 0L,
    ): Long {
        if (!enabled) return 0L
        if (intervalMs <= 0L) return 0L
        val sinceStart = now - sessionStartMs
        if (sinceStart < intervalMs) return 0L  // before the first grid point
        val ticksElapsed = sinceStart / intervalMs
        val currentTickAt = sessionStartMs + ticksElapsed * intervalMs
        if (lastTimeAlertFireMs >= currentTickAt) return 0L  // already fired this tick
        // Initial-delay FILTER: ticks earlier than `sessionStartMs + initialDelayMs`
        // are silently dropped. The grid stays anchored — interval=20 + initialDelay=30
        // fires at 40 / 60 / 80, not 30 / 50 / 70.
        val effectiveInitialDelayMs = if (cumLogged == 0) initialDelayMs else 0L
        if (currentTickAt < sessionStartMs + effectiveInitialDelayMs) return 0L
        // A first log releases the filter above, which would resurrect the tick it had
        // already dropped: "time to drink" seconds after drinking (field ride
        // 0e6f39_c38ced). Such a tick stays dropped when the rider logged after it.
        // Scoped to the initial-delay window so ordinary ticks behave exactly as before
        // (a combined-field undo keeps the log timestamp); `..now` ignores a log stamped
        // in the future by a clock step.
        if (currentTickAt < sessionStartMs + initialDelayMs &&
            lastRealLogMs in currentTickAt..now) return 0L
        return currentTickAt
    }

    /**
     * Multiplier ladder for [shouldFireDeficit]'s cooldown, indexed by
     * unacknowledged fires: the first two reminders keep the rider's configured
     * interval, then it doubles, then caps at ×4. Capped (not unbounded, not a
     * hard stop) so a rider who never logs still gets a prompt every 4 intervals.
     */
    private const val MAX_BACKOFF_SHIFT = 2

    /**
     * Returns `true` when the deficit alert should fire on the current tick.
     *
     * Conditions:
     *  - Alert enabled.
     *  - Cumulative deficit ≥ threshold.
     *  - Either (a) no previous deficit fire AND outside the initial-delay
     *    grace, or (b) the backed-off cooldown has elapsed since the last fire.
     *
     * The initial-delay grace only applies to the FIRST deficit fire AND only
     * when the rider hasn't logged anything yet (a logged item is implicit
     * acknowledgement that the rider is engaged with fueling).
     *
     * [unackedFires] is how many deficit alerts have fired since the rider last
     * logged anything. 0 or 1 → the configured [reminderIntervalMs]; 2 → ×2;
     * 3 or more → ×4 (the cap). The caller owns the counter and resets it on any
     * log — see `HydrationTracker.evaluateDeficitAlert`.
     *
     * [lastRealLogMs] restarts the cooldown from the rider's last log. Without it,
     * dropping the ladder back to ×1 on a log can leave an already-elapsed cooldown
     * behind and the reminder re-fires seconds after the rider drank (2026-09-12
     * sweep: `0e6f39_8f1921` fired a deficit prompt 5.8 s and 12.8 s after a
     * `HYD_LOG`, because a ×2 gap of 27 min had accrued under the ×1 interval of
     * 15 min). Only applied once a deficit alert has fired this session, so the
     * first-fire initial-delay grace above is untouched, and clamped so total silence
     * never exceeds the x(1 shl MAX_BACKOFF_SHIFT) ceiling however often the rider logs.
     * Defaulted to 0 for callers that do not track it (tests); no production caller
     * passes 0, since both trackers seed the field in `start()`.
     *
     * [lookaheadMs] asks "is the reminder due within this long?" instead of "due now".
     * It is the other half of the quiet window: [suppressedByRecentAlert] stops a time
     * reminder right after a deficit one, but a time reminder followed seconds later by
     * the deficit one still beeped twice (2026-10-02 sweep, `aa23ea_11b571`: 15 s apart,
     * same number). The trackers pass [QUIET_WINDOW_MS] only when a time tick is about to
     * fire, so the deficit alert goes out early and takes the tick's place — pulled
     * forward, never suppressed. It shortens the cooldown only: the threshold and the
     * rider's initial delay are still judged at the real `now`.
     */
    fun shouldFireDeficit(
        enabled: Boolean,
        deficit: Int,
        deficitThreshold: Int,
        lastDeficitAlertFireMs: Long,
        reminderIntervalMs: Long,
        initialDelayMs: Long,
        cumLogged: Int,
        sessionStartMs: Long,
        now: Long,
        unackedFires: Int = 0,
        lastRealLogMs: Long = 0L,
        lookaheadMs: Long = 0L,
    ): Boolean {
        if (!enabled) return false
        // Initial-delay grace: only blocks the first fire AND only while no log
        // has been recorded yet.
        if (lastDeficitAlertFireMs == 0L && cumLogged == 0 && initialDelayMs > 0L) {
            if (now - sessionStartMs < initialDelayMs) return false
        }
        if (deficit < deficitThreshold) return false
        // The log may push the cooldown origin forward, but never so far that the next
        // reminder would land beyond the x(1 shl MAX_BACKOFF_SHIFT) ceiling measured from
        // the last fire. Without this clamp a rider logging small amounts more often than
        // the interval — exactly the rider the deficit channel exists for, drinking but
        // not enough — silences the channel for the rest of the ride, and a single log
        // placed just under the ceiling already buys one interval more silence than the
        // ladder promises.
        val latestLogAnchor =
            lastDeficitAlertFireMs + (reminderIntervalMs shl MAX_BACKOFF_SHIFT) - reminderIntervalMs
        val cooldownFrom =
            if (lastDeficitAlertFireMs == 0L) lastDeficitAlertFireMs
            else maxOf(lastDeficitAlertFireMs, minOf(lastRealLogMs, latestLogAnchor))
        val backoffShift = (unackedFires - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        if (now + lookaheadMs - cooldownFrom < (reminderIntervalMs shl backoffShift)) return false
        return true
    }

    /**
     * Minimum quiet time before a TIME-grid reminder may follow a DEFICIT alert
     * from the same tracker. The time grid and the deficit cooldown are
     * independent clocks, so nothing stopped them landing minutes apart: the
     * 2026-09-20 field ride (`0e6f39_c16c8f`, 8 hydration fires, nothing logged)
     * paired every one of its three deficit reminders with a time reminder
     * 2.0 / 2.0 / 3.7 min away. The rider hears two beeps, and the second one
     * says less than the first.
     *
     * 3 min, which swallows the two 2.0-min pairs and leaves the 3.7-min one
     * (the rider's call). Not a setting: the rider already configures both
     * intervals, and this only stops them colliding.
     */
    const val QUIET_WINDOW_MS: Long = 3L * 60_000L

    /**
     * True when the last DEFICIT alert is recent enough that a time-grid
     * reminder on top of it would just be noise.
     *
     * **One-way by construction.** The parameter is the deficit clock, and the
     * deficit path never consults this function — so no configuration of the
     * time channel can silence a deficit alert. That channel reports an actual
     * physiological gap ("you are N ml behind"), which is independent of, and
     * more actionable than, "your N-minute reminder is due". It is also why the
     * anchor is NOT "the last alert of any source": that would make the time
     * channel throttle *itself* to a 3-min floor, halving the cadence of a
     * rider who configured a 1- or 2-min reminder.
     *
     * The caller consumes the tick it was about to fire rather than deferring
     * it, widening the existing same-tick "deficit wins, time tick consumed"
     * rule to a window. A grid tick swallowed this way is skipped, not queued —
     * and while the deficit channel keeps firing inside the window (possible
     * when `4 × deficitReminderIntervalMin ≈ timeIntervalMin`, e.g. 5 and 20),
     * the time channel can stay quiet for the rest of the ride. That outcome is
     * intended: the rider still gets a beep per interval, carrying strictly
     * more information than the one it replaced.
     *
     * The `now >= lastDeficitAlertFireMs` half keeps a backwards NTP step from
     * reading as "fired in the future" and silencing the channel until the
     * clock catches up. `0L` (no deficit alert yet) falls out of the same
     * comparison.
     *
     * **Pure**: mutates nothing.
     */
    fun suppressedByRecentAlert(lastDeficitAlertFireMs: Long, now: Long): Boolean =
        lastDeficitAlertFireMs > 0L && now >= lastDeficitAlertFireMs &&
            now - lastDeficitAlertFireMs < QUIET_WINDOW_MS
}
