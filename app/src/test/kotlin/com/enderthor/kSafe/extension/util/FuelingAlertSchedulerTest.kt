package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FuelingAlertScheduler] — pinned the contracts that were
 * load-bearing for v17 (time-grid alignment, initial-delay filter,
 * configurable deficit reminder cooldown) and v18 (coincidence resolution
 * via tick consumption).
 *
 * These tests caught the FuelingAlertScheduler refactor and would catch
 * future regressions on the trackers' alert-scheduling shape without
 * requiring a Robolectric harness.
 */
class FuelingAlertSchedulerTest {

    // ── currentDueTimeTick ────────────────────────────────────────────────────

    @Test
    fun `time alert fires at sessionStart + N × interval regardless of logs`() {
        // interval=20 min, sessionStartMs=0, no initial delay, no logs.
        // Expected ticks at 20, 40, 60, 80 …
        val interval = 20L * 60_000L
        var lastFire = 0L
        for (n in 1..5) {
            val now = n * interval
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true,
                intervalMs = interval,
                sessionStartMs = 0L,
                lastTimeAlertFireMs = lastFire,
                initialDelayMs = 0L,
                cumLogged = 0,
                now = now,
            )
            assertEquals("tick $n should fire at $now ms", now, tick)
            lastFire = now
        }
    }

    @Test
    fun `time alert does NOT fire before the first grid point`() {
        val interval = 20L * 60_000L
        // 0, 5, 10, 15, 19 min — all below the first grid point at 20 min.
        for (mins in listOf(0L, 5L, 10L, 15L, 19L)) {
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = interval, sessionStartMs = 0L,
                lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
                now = mins * 60_000L,
            )
            assertEquals("no tick due at $mins min", 0L, tick)
        }
    }

    @Test
    fun `time alert FIRES at the first tick of session when no initial delay`() {
        val interval = 20L * 60_000L
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
            now = interval,  // exactly 20 min
        )
        assertEquals(interval, tick)
    }

    @Test
    fun `time alert does not re-fire same tick within the interval`() {
        val interval = 20L * 60_000L
        // Already fired at 20 min. Subsequent calls at 25 / 30 / 35 / 39 min
        // must NOT fire because the next tick is at 40.
        for (mins in listOf(20L, 25L, 30L, 35L, 39L)) {
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = interval, sessionStartMs = 0L,
                lastTimeAlertFireMs = 20L * 60_000L,
                initialDelayMs = 0L, cumLogged = 0,
                now = mins * 60_000L,
            )
            assertEquals("no re-fire at $mins (already fired at 20)", 0L, tick)
        }
    }

    @Test
    fun `rider logging does NOT reset the grid`() {
        // v17 contract: rider logged at minute 22 (mid-interval). The next
        // alert must still fire at minute 40 (next grid point), NOT minute 42.
        val interval = 20L * 60_000L
        // Already fired at 20. cumLogged > 0 (rider logged after).
        val tick40 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 20L * 60_000L,
            initialDelayMs = 0L,  // doesn't matter once cumLogged > 0
            cumLogged = 25,  // rider logged something
            now = 40L * 60_000L,
        )
        assertEquals("rider log must not shift the grid", 40L * 60_000L, tick40)
    }

    // ── Initial-delay FILTER (v17 user-facing contract) ──────────────────────

    @Test
    fun `initial delay FILTERS early ticks - interval 20 + delay 30 fires at 40`() {
        val interval = 20L * 60_000L
        val initialDelay = 30L * 60_000L
        // Tick at minute 20: filtered (20 < 30).
        val tick20 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = initialDelay,
            cumLogged = 0, now = 20L * 60_000L,
        )
        assertEquals("tick at 20 min filtered by 30 min initial delay", 0L, tick20)
        // Tick at minute 40: passes filter (40 >= 30). First fire of the session.
        val tick40 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = initialDelay,
            cumLogged = 0, now = 40L * 60_000L,
        )
        assertEquals("tick at 40 min passes filter — first fire", 40L * 60_000L, tick40)
    }

    @Test
    fun `initial delay does NOT shift the grid forward by the delay`() {
        // PRE-v17 BUG: initial delay 30 + interval 20 used to fire at 30, then
        // 30+20=50, then 70. Grid was shifted. v17 contract: fires at 40, 60, 80.
        val interval = 20L * 60_000L
        val initialDelay = 30L * 60_000L
        // After firing at 40, the next due tick should be 60, NOT 60 (40+20=60
        // is correct grid-aligned). Test that a query at 50 doesn't fire.
        val tick50 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 40L * 60_000L,
            initialDelayMs = initialDelay, cumLogged = 0,
            now = 50L * 60_000L,
        )
        assertEquals("tick at 50 not on grid (grid points at 40, 60, 80)", 0L, tick50)
        val tick60 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 40L * 60_000L,
            initialDelayMs = initialDelay, cumLogged = 0,
            now = 60L * 60_000L,
        )
        assertEquals("tick at 60 fires (grid stays anchored)", 60L * 60_000L, tick60)
    }

    @Test
    fun `initial delay releases as soon as rider logs anything`() {
        // Rider logged at minute 5 (within the 30 min initial delay). The
        // first tick at minute 20 must fire — initial delay no longer applies
        // because the rider is engaged.
        val interval = 20L * 60_000L
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L,
            initialDelayMs = 30L * 60_000L,
            cumLogged = 25,  // rider logged
            now = 20L * 60_000L,
        )
        assertEquals(20L * 60_000L, tick)
    }

    @Test
    fun `a log does not resurrect a tick the initial delay already filtered`() {
        // 2026-09-24 field ride (`0e6f39_c38ced`): interval 22, initial delay 30.
        // The 22-min tick was filtered; the rider's first drink at ~41 min released
        // the filter and made that stale tick due, so a "time to drink" reminder
        // fired seconds after drinking. A tick the rider logged after must not fire.
        val interval = 22L * 60_000L
        val delay = 30L * 60_000L
        val logAt = 41L * 60_000L
        val stale = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = delay, cumLogged = 150,
            now = logAt + 12_000L, lastRealLogMs = logAt,
        )
        assertEquals("stale 22-min tick must stay silent after the log", 0L, stale)
        // The grid is untouched: the 44-min tick still fires.
        val next = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = delay, cumLogged = 150,
            now = 44L * 60_000L, lastRealLogMs = logAt,
        )
        assertEquals(44L * 60_000L, next)
    }

    @Test
    fun `a log before the tick still lets that tick fire`() {
        // Released-filter contract: logged at 5 min, the 20-min tick fires on time.
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = 20L * 60_000L, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 30L * 60_000L, cumLogged = 25,
            now = 20L * 60_000L + 10_000L, lastRealLogMs = 5L * 60_000L,
        )
        assertEquals(20L * 60_000L, tick)
    }

    @Test
    fun `outside the initial delay a log after the tick does not suppress it`() {
        // Ordinary ticks keep their pre-fix behaviour: the guard only covers ticks the
        // initial delay had dropped (a combined-field undo keeps the log timestamp).
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = 20L * 60_000L, sessionStartMs = 0L,
            lastTimeAlertFireMs = 20L * 60_000L, initialDelayMs = 30L * 60_000L, cumLogged = 25,
            now = 40L * 60_000L + 10_000L, lastRealLogMs = 40L * 60_000L + 5_000L,
        )
        assertEquals(40L * 60_000L, tick)
    }

    @Test
    fun `a future-dated log from a clock step does not suppress a filtered-window tick`() {
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = 22L * 60_000L, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 30L * 60_000L, cumLogged = 150,
            now = 22L * 60_000L + 10_000L, lastRealLogMs = 41L * 60_000L,
        )
        assertEquals(22L * 60_000L, tick)
    }

    @Test
    fun `disabled time alert never produces a tick`() {
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = false, intervalMs = 20L * 60_000L, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
            now = 1_000_000L,
        )
        assertEquals(0L, tick)
    }

    @Test
    fun `zero or negative interval produces no ticks (defensive guard)`() {
        for (interval in listOf(0L, -1L)) {
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = interval, sessionStartMs = 0L,
                lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
                now = 1_000_000L,
            )
            assertEquals("guard interval=$interval", 0L, tick)
        }
    }

    // ── Coincidence resolution: time tick consumption (caller responsibility) ─

    @Test
    fun `consumed time tick prevents same-tick re-fire on subsequent call`() {
        // The trackers stamp `lastTimeAlertFireMs = now` when a coincident
        // deficit alert wins. Verify that the consumption correctly prevents
        // the same tick from re-firing on the very next call, but leaves the
        // next grid point available.
        val interval = 20L * 60_000L
        val now = 20L * 60_000L  // tick at 20 min
        val tick1 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0, now = now,
        )
        assertEquals("tick due at 20", now, tick1)
        // Tracker stamps now (coincidence consumption).
        val tickAfterConsume = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = now, initialDelayMs = 0L, cumLogged = 0,
            now = now + 15_000L,  // 15 s later (next tracker tick)
        )
        assertEquals("consumed tick — no re-fire same window", 0L, tickAfterConsume)
        // Next grid point at 40 min — must fire normally.
        val tickAt40 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = now,
            initialDelayMs = 0L, cumLogged = 0,
            now = 40L * 60_000L,
        )
        assertEquals("next grid point at 40 fires", 40L * 60_000L, tickAt40)
    }

    // ── shouldFireDeficit ────────────────────────────────────────────────────

    @Test
    fun `deficit alert fires when over threshold and cooldown elapsed`() {
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 30, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L, now = 60L * 60_000L,
        )
        assertTrue(fire)
    }

    @Test
    fun `deficit alert does NOT fire when below threshold`() {
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 10, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L, now = 60L * 60_000L,
        )
        assertFalse(fire)
    }

    @Test
    fun `deficit alert reminder cooldown respected (v17 configurable cadence)`() {
        // Last fire at minute 30, reminder = 10 min, threshold crossed.
        // Should fire at minute 40 but NOT at 35.
        val reminderInterval = 10L * 60_000L
        val lastFire = 30L * 60_000L
        val tick35 = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 30, deficitThreshold = 25,
            lastDeficitAlertFireMs = lastFire,
            reminderIntervalMs = reminderInterval,
            initialDelayMs = 0L, cumLogged = 50,
            sessionStartMs = 0L, now = 35L * 60_000L,
        )
        assertFalse("only 5 min since last fire", tick35)
        val tick40 = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 30, deficitThreshold = 25,
            lastDeficitAlertFireMs = lastFire,
            reminderIntervalMs = reminderInterval,
            initialDelayMs = 0L, cumLogged = 50,
            sessionStartMs = 0L, now = 40L * 60_000L,
        )
        assertTrue("10 min elapsed — reminder due", tick40)
    }

    @Test
    fun `deficit initial-delay grace only applies to first fire and when no logs`() {
        // Initial delay 30 min, no log, no previous deficit fire — must NOT
        // fire at minute 20 even if deficit is huge.
        val tick20 = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 50, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 30L * 60_000L,
            cumLogged = 0,
            sessionStartMs = 0L, now = 20L * 60_000L,
        )
        assertFalse("initial delay holds first fire", tick20)
        // Same conditions but rider has logged — grace lifts.
        val tick20Logged = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 50, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 30L * 60_000L,
            cumLogged = 25,  // rider logged → grace lifts
            sessionStartMs = 0L, now = 20L * 60_000L,
        )
        assertTrue("rider engaged — initial delay no longer applies", tick20Logged)
    }

    @Test
    fun `deficit alert disabled never fires`() {
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled = false,
            deficit = 100, deficitThreshold = 1,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 1L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L, now = 1_000_000L,
        )
        assertFalse(fire)
    }

    // ── unacknowledged back-off (2026-07-22 field sweep) ─────────────────────

    /** Helper: 10-min base interval, threshold crossed, no initial delay. */
    private fun deficitDueAfter(minutesSinceLastFire: Long, unacked: Int): Boolean =
        FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 500, deficitThreshold = 300,
            lastDeficitAlertFireMs = 30L * 60_000L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L,
            now = (30L + minutesSinceLastFire) * 60_000L,
            unackedFires = unacked,
        )

    @Test
    fun `first two unacknowledged reminders keep the configured interval`() {
        assertFalse("9 min < base interval", deficitDueAfter(9, unacked = 0))
        assertTrue("base interval, nothing ignored yet", deficitDueAfter(10, unacked = 0))
        assertTrue("one ignored — still base cadence", deficitDueAfter(10, unacked = 1))
    }

    @Test
    fun `back-off doubles then caps at four times the interval`() {
        assertFalse("2 ignored → 20 min, not due at 19", deficitDueAfter(19, unacked = 2))
        assertTrue("2 ignored → due at 20", deficitDueAfter(20, unacked = 2))
        assertFalse("3 ignored → 40 min, not due at 39", deficitDueAfter(39, unacked = 3))
        assertTrue("3 ignored → due at 40", deficitDueAfter(40, unacked = 3))
    }

    @Test
    fun `back-off never goes silent — cap holds at four intervals however many are ignored`() {
        // The 2026-07-22 worst case: 13 unacknowledged hydration prompts in an hour.
        // However deep the counter goes, the reminder must still arrive at ×4.
        assertFalse("still gated below the cap", deficitDueAfter(39, unacked = 13))
        assertTrue("capped at ×4 — reminder still fires", deficitDueAfter(40, unacked = 13))
        assertTrue("cap holds at absurd counts", deficitDueAfter(40, unacked = 999))
    }

    @Test
    fun `a log resets the ladder — caller passes zero and base cadence returns`() {
        // The trackers zero their counter when `lastRealLogMs` moves; from the
        // scheduler's side that is simply unackedFires = 0 again.
        assertFalse("backed off at ×4", deficitDueAfter(20, unacked = 5))
        assertTrue("after a log, 20 min is well past the base interval", deficitDueAfter(20, unacked = 0))
    }

    // ── re-fire right after a log (2026-09-12 field sweep) ────────────────────

    /** The `0e6f39_8f1921` shape: 15-min base interval, one deficit alert fired at
     *  t=0, then nothing for 27 min while the ×2 ladder held. The rider drinks at
     *  27 min and the amount does not clear the deficit. */
    private fun deficitDueAfterLog(minutesSinceLog: Long, lastLogMin: Long = 27L): Boolean =
        FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 513, deficitThreshold = 200,
            lastDeficitAlertFireMs = 0L + 1L,   // fired at t≈0; non-zero = not the first fire
            reminderIntervalMs = 15L * 60_000L,
            initialDelayMs = 30L * 60_000L, cumLogged = 100,
            sessionStartMs = 0L,
            now = (lastLogMin + minutesSinceLog) * 60_000L,
            unackedFires = 0,                   // the log reset the ladder
            lastRealLogMs = lastLogMin * 60_000L,
        )

    @Test
    fun `logging a drink does not re-fire the deficit reminder seconds later`() {
        // Before the fix the ×2 gap of 27 min had already elapsed against the base
        // ×1 interval, so the reset ladder made the alert due on the very next tick.
        assertFalse("at the log instant", deficitDueAfterLog(minutesSinceLog = 0))
        assertFalse("5 min after the log", deficitDueAfterLog(minutesSinceLog = 5))
        assertFalse("one tick short of the interval", deficitDueAfterLog(minutesSinceLog = 14))
    }

    @Test
    fun `the reminder still arrives a full interval after the log`() {
        assertTrue("15 min after the drink", deficitDueAfterLog(minutesSinceLog = 15))
        assertTrue("and later still", deficitDueAfterLog(minutesSinceLog = 40))
    }

    @Test
    fun `a log older than the last fire leaves the back-off ladder intact`() {
        // The other branch of the maxOf: a stale log from earlier in the ride must not
        // shorten the x2/x4 cooldown the 2026-07-22 back-off put there.
        fun due(minutesSinceFire: Long, unacked: Int) =
            FuelingAlertScheduler.shouldFireDeficit(
                enabled = true,
                deficit = 500, deficitThreshold = 300,
                lastDeficitAlertFireMs = 30L * 60_000L,
                reminderIntervalMs = 10L * 60_000L,
                initialDelayMs = 0L, cumLogged = 100,
                sessionStartMs = 0L,
                now = (30L + minutesSinceFire) * 60_000L,
                unackedFires = unacked,
                lastRealLogMs = 10L * 60_000L,   // logged BEFORE the last fire
            )
        assertFalse("2 ignored -> x2 = 20 min, not due at 19", due(19, unacked = 2))
        assertTrue("2 ignored -> due at 20", due(20, unacked = 2))
    }

    @Test
    fun `however often the rider logs, silence never exceeds the back-off ceiling`() {
        // A rider logging small amounts more often than the interval must still be
        // reminded. Base 15 min, ceiling x4 = 60 min from the last fire.
        fun due(nowMin: Long, lastLogMin: Long) =
            FuelingAlertScheduler.shouldFireDeficit(
                enabled = true,
                deficit = 500, deficitThreshold = 300,
                lastDeficitAlertFireMs = 0L,
                reminderIntervalMs = 15L * 60_000L,
                initialDelayMs = 0L, cumLogged = 100,
                sessionStartMs = 0L,
                now = nowMin * 60_000L,
                unackedFires = 0,
                lastRealLogMs = lastLogMin * 60_000L,
            ).let { it }
        // lastDeficitAlertFireMs must be non-zero for the log anchor to apply at all.
        fun dueAfterFire(nowMin: Long, lastLogMin: Long) =
            FuelingAlertScheduler.shouldFireDeficit(
                enabled = true,
                deficit = 500, deficitThreshold = 300,
                lastDeficitAlertFireMs = 1L,
                reminderIntervalMs = 15L * 60_000L,
                initialDelayMs = 0L, cumLogged = 100,
                sessionStartMs = 0L,
                now = nowMin * 60_000L,
                unackedFires = 0,
                lastRealLogMs = lastLogMin * 60_000L,
            )
        assertFalse("inside the ceiling, a recent log still defers", dueAfterFire(40, lastLogMin = 35))
        assertFalse("still inside the ceiling at 59 min", dueAfterFire(59, lastLogMin = 55))
        // The fire is stamped at 1 ms, so the ceiling lands 1 ms past the 60-minute mark;
        // assert at 61 rather than encode that artefact.
        assertTrue("past the x4 ceiling the reminder fires despite a log 6 min ago",
            dueAfterFire(61, lastLogMin = 55))
        assertTrue("and keeps firing however recent the log", dueAfterFire(75, lastLogMin = 74))
        assertTrue("sanity: no prior fire is governed by the interval alone", due(20, lastLogMin = 19))
    }

    @Test
    fun `a log never delays the first deficit alert of the session`() {
        // lastDeficitAlertFireMs == 0 means the initial-delay grace still owns the
        // gate; the log timestamp must not push the first reminder out.
        assertTrue(
            "first fire, past the initial delay",
            FuelingAlertScheduler.shouldFireDeficit(
                enabled = true,
                deficit = 500, deficitThreshold = 300,
                lastDeficitAlertFireMs = 0L,
                reminderIntervalMs = 15L * 60_000L,
                initialDelayMs = 30L * 60_000L, cumLogged = 100,
                sessionStartMs = 0L,
                now = 31L * 60_000L,
                unackedFires = 0,
                lastRealLogMs = 30L * 60_000L,
            )
        )
    }

    // ── shouldFireDeficit lookahead (time → deficit direction) ────────────────

    @Test
    fun `the lookahead reports a deficit alert due within the window`() {
        // Field ride aa23ea_11b571 (2026-10-02 sweep): the time reminder fired and the
        // deficit one 15 s later with the same number. Here the backed-off deficit is due
        // at 80 min (fired at 40, two unacked -> x2 of 20 min) and a time tick lands 15 s
        // earlier: the lookahead sees it coming, which is what lets resolveTick hold the tick.
        fun due(lookaheadMs: Long) = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 972, deficitThreshold = 300,
            lastDeficitAlertFireMs = 40L * 60_000L,
            reminderIntervalMs = 20L * 60_000L,
            initialDelayMs = 30L * 60_000L, cumLogged = 0,
            sessionStartMs = 0L,
            now = 80L * 60_000L - 15_000L,
            unackedFires = 2,
            lastRealLogMs = 0L,
            lookaheadMs = lookaheadMs,
        )
        assertFalse("without lookahead the deficit is not due yet", due(0L))
        assertTrue("within the quiet window it is reported due", due(FuelingAlertScheduler.QUIET_WINDOW_MS))
    }

    @Test
    fun `the lookahead never pulls a deficit alert from beyond the window`() {
        assertFalse(
            "due 4 min from now is outside a 3-min lookahead",
            FuelingAlertScheduler.shouldFireDeficit(
                enabled = true,
                deficit = 500, deficitThreshold = 300,
                lastDeficitAlertFireMs = 40L * 60_000L,
                reminderIntervalMs = 20L * 60_000L,
                initialDelayMs = 0L, cumLogged = 0,
                sessionStartMs = 0L,
                now = 56L * 60_000L,
                unackedFires = 1,
                lookaheadMs = FuelingAlertScheduler.QUIET_WINDOW_MS,
            )
        )
    }

    @Test
    fun `the lookahead never bypasses the threshold or the initial delay`() {
        fun due(deficit: Int, nowMin: Long) = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = deficit, deficitThreshold = 300,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 20L * 60_000L,
            initialDelayMs = 30L * 60_000L, cumLogged = 0,
            sessionStartMs = 0L,
            now = nowMin * 60_000L,
            lookaheadMs = FuelingAlertScheduler.QUIET_WINDOW_MS,
        )
        assertFalse("below the threshold nothing is reported due", due(deficit = 299, nowMin = 40))
        assertFalse("the rider's initial delay is honoured to the minute", due(deficit = 500, nowMin = 28))
        assertTrue("sanity: past the delay and over the threshold", due(deficit = 500, nowMin = 30))
    }

    // ── suppressedByRecentAlert (time-alert quiet window) ─────────────────────

    @Test
    fun `a time reminder within the quiet window of a deficit alert is suppressed`() {
        val min = 60_000L
        // 2026-09-20 field ride: deficit and time reminders landed 2.0 / 2.0 / 3.7
        // min apart. The two 2-min pairs must go; the 3.7-min one is outside the
        // 3-min window by design (the rider picked 3 min max).
        for (gapMin in listOf(0L, 1L, 2L)) {
            assertTrue(
                "$gapMin min after the deficit alert is still inside the window",
                FuelingAlertScheduler.suppressedByRecentAlert(10L * min, 10L * min + gapMin * min)
            )
        }
        assertFalse(
            "exactly at the window edge the channel reopens",
            FuelingAlertScheduler.suppressedByRecentAlert(10L * min, 13L * min)
        )
        assertFalse(
            "3.7 min apart is outside the window",
            FuelingAlertScheduler.suppressedByRecentAlert(10L * min, 13L * min + 42_000L)
        )
    }

    @Test
    fun `the quiet window never throttles the time channel by itself`() {
        // The anchor is the DEFICIT clock, so a rider with the deficit alert off
        // (or simply not behind) keeps the exact cadence they configured — a 1-min
        // reminder must not become a 3-min one. Anchoring on "last alert of any
        // source" instead would make every one of these true.
        val min = 60_000L
        for (intervalMin in listOf(1L, 2L, 3L)) {
            assertFalse(
                "no deficit alert has fired, so a ${intervalMin}-min grid must not be gated",
                FuelingAlertScheduler.suppressedByRecentAlert(0L, intervalMin * min)
            )
        }
    }

    @Test
    fun `a backwards clock step never silences the channel`() {
        assertFalse(
            "an NTP step backwards must not read as 'fired in the future'",
            FuelingAlertScheduler.suppressedByRecentAlert(60L * 60_000L, 45L * 60_000L)
        )
    }

    // ── resolveTick (the trackers' per-tick decision) ─────────────────────────

    private val min = 60_000L

    private fun resolve(
        deficitDueAtMs: Long?,           // when the deficit alert becomes due; null = never
        timeTickAtMs: Long,
        now: Long,
        lastDeficitAlertFireMs: Long = 0L,
        lastRealLogMs: Long = 0L,
        emergencyActive: Boolean = false,
    ) = FuelingAlertScheduler.resolveTick(
        deficitDueWithin = { look -> deficitDueAtMs != null && now + look >= deficitDueAtMs },
        timeTickAtMs = timeTickAtMs,
        lastDeficitAlertFireMs = lastDeficitAlertFireMs,
        lastRealLogMs = lastRealLogMs,
        emergencyActive = emergencyActive,
        now = now,
    )

    @Test
    fun `a time tick whose deficit alert is due inside the window is held, not fired`() {
        val tick = 80L * min
        assertEquals("deficit 15 s after the tick -> hold",
            FuelingAlertScheduler.TickAction.NONE, resolve(tick + 15_000L, tick, now = tick))
        assertEquals("then the deficit fires and takes the tick",
            FuelingAlertScheduler.TickAction.FIRE_DEFICIT, resolve(tick + 15_000L, tick, now = tick + 15_000L))
        assertEquals("deficit 4 min away -> the time tick fires",
            FuelingAlertScheduler.TickAction.FIRE_TIME, resolve(tick + 4L * min, tick, now = tick))
        assertEquals("the hold never outlasts the window from the grid point",
            FuelingAlertScheduler.TickAction.FIRE_TIME, resolve(tick + 4L * min, tick, now = tick + 3L * min))
    }

    @Test
    fun `an emergency defers both channels and consumes nothing`() {
        val tick = 80L * min
        assertEquals(FuelingAlertScheduler.TickAction.NONE,
            resolve(deficitDueAtMs = 0L, timeTickAtMs = tick, now = tick, emergencyActive = true))
        assertEquals(FuelingAlertScheduler.TickAction.NONE,
            resolve(deficitDueAtMs = null, timeTickAtMs = tick, now = tick, emergencyActive = true))
    }

    @Test
    fun `a held tick the rider logged after is dropped, never fired after the drink`() {
        val tick = 80L * min
        // Rider drank 1 min into the hold; the deficit is no longer due.
        assertEquals(FuelingAlertScheduler.TickAction.QUIET_LOGGED,
            resolve(deficitDueAtMs = null, timeTickAtMs = tick, now = tick + 75_000L, lastRealLogMs = tick + 60_000L))
        assertEquals("a log before the grid point does not drop it",
            FuelingAlertScheduler.TickAction.FIRE_TIME,
            resolve(deficitDueAtMs = null, timeTickAtMs = tick, now = tick, lastRealLogMs = tick - 60_000L))
    }

    @Test
    fun `the quiet window after a deficit alert still consumes the time tick`() {
        val tick = 80L * min
        assertEquals(FuelingAlertScheduler.TickAction.QUIET_AFTER_DEFICIT,
            resolve(deficitDueAtMs = null, timeTickAtMs = tick, now = tick, lastDeficitAlertFireMs = tick - 2L * min))
    }

    /**
     * Drives a never-logging rider (deficit always over threshold) through a ride on the
     * trackers' 15-s tick, stamping state the way `CarbsTracker.tick` / `HydrationTracker.tick`
     * do. [legacy] = the 2.2.3 rule (no hold, no logged-after drop), for comparison.
     */
    private fun simulate(timeMin: Long, deficitMin: Long, delayMin: Long, legacy: Boolean): List<Pair<Long, Char>> {
        val fires = mutableListOf<Pair<Long, Char>>()
        var lastDeficit = 0L
        var lastTime = 0L
        var unacked = 0
        var now = 15_000L
        while (now <= 4L * 60L * min) {
            val t = now
            val tickAt = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = timeMin * min, sessionStartMs = 0L,
                lastTimeAlertFireMs = lastTime, initialDelayMs = delayMin * min, cumLogged = 0, now = t,
            )
            val due = { look: Long ->
                FuelingAlertScheduler.shouldFireDeficit(
                    enabled = true, deficit = 1_000, deficitThreshold = 300,
                    lastDeficitAlertFireMs = lastDeficit, reminderIntervalMs = deficitMin * min,
                    initialDelayMs = delayMin * min, cumLogged = 0, sessionStartMs = 0L, now = t,
                    unackedFires = unacked, lookaheadMs = look,
                )
            }
            val action = if (!legacy) {
                FuelingAlertScheduler.resolveTick(due, tickAt, lastDeficit, 0L, false, t)
            } else when {
                due(0L) -> FuelingAlertScheduler.TickAction.FIRE_DEFICIT
                tickAt == 0L -> FuelingAlertScheduler.TickAction.NONE
                FuelingAlertScheduler.suppressedByRecentAlert(lastDeficit, t) ->
                    FuelingAlertScheduler.TickAction.QUIET_AFTER_DEFICIT
                else -> FuelingAlertScheduler.TickAction.FIRE_TIME
            }
            when (action) {
                FuelingAlertScheduler.TickAction.FIRE_DEFICIT -> {
                    fires += t to 'D'; lastDeficit = t; unacked++
                    if (tickAt != 0L) lastTime = t
                }
                FuelingAlertScheduler.TickAction.FIRE_TIME -> { fires += t to 'T'; lastTime = t }
                FuelingAlertScheduler.TickAction.QUIET_AFTER_DEFICIT,
                FuelingAlertScheduler.TickAction.QUIET_LOGGED -> lastTime = t
                FuelingAlertScheduler.TickAction.NONE -> {}
            }
            now += 15_000L
        }
        return fires
    }

    @Test
    fun `holding never moves a deficit alert and never adds an alert`() {
        var legacyPairs = 0
        for (delay in listOf(0L, 30L)) for (t in 1L..30L) for (d in 1L..30L) {
            val old = simulate(t, d, delay, legacy = true)
            val new = simulate(t, d, delay, legacy = false)
            val cfg = "time=$t deficit=$d delay=$delay"
            assertEquals("$cfg: deficit alerts unchanged", old.filter { it.second == 'D' }, new.filter { it.second == 'D' })
            assertTrue("$cfg: never more alerts (${new.size} vs ${old.size})", new.size <= old.size)
            fun pairs(f: List<Pair<Long, Char>>) = f.zipWithNext().count { (a, b) ->
                a.second == 'T' && b.second == 'D' && b.first - a.first < FuelingAlertScheduler.QUIET_WINDOW_MS - 15_000L
            }
            legacyPairs += pairs(old)
            assertEquals("$cfg: no time alert followed by a deficit one inside the window", 0, pairs(new))
        }
        assertTrue("sanity: the 2.2.3 rule really produced time->deficit pairs", legacyPairs > 0)
    }
}
