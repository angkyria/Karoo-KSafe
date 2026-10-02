package com.enderthor.kSafe.datatype

// Status bands — the same colours and thresholds as CarbStatusDataType /
// HydrationStatusDataType, so a Fuel Panel half reads exactly like the status field it
// stands in for. Keep the three in sync.
internal const val PANEL_COLOR_AHEAD    = 0xFF1565C0.toInt()  // blue — surplus, no concern
internal const val PANEL_COLOR_OK       = 0xFF2E7D32.toInt()  // green — within margin
internal const val PANEL_COLOR_AMBER    = 0xFFE65100.toInt()  // amber — approaching threshold
internal const val PANEL_COLOR_RED      = 0xFFB71C1C.toInt()  // red — over threshold
internal const val PANEL_COLOR_DISABLED = 0xFF616161.toInt()  // grey — master / tracker off
// Tap-feedback flashes — the same colours as the Carb / Hydration log fields.
internal const val PANEL_COLOR_LOGGED   = 0xFF1B5E20.toInt()
internal const val PANEL_COLOR_UNDONE   = 0xFFB71C1C.toInt()

/** Tap feedback a half is showing: the amount the last tap on its slot logged or reversed
 *  (the slot's Hydration/CarbLogState LOGGED / UNDONE), unit-agnostic. */
internal sealed class PanelFlash {
    data class Logged(val amount: Int) : PanelFlash()
    data class Undone(val amount: Int) : PanelFlash()
}

/** One rendered half of the Fuel Panel. [iconRes] is the hint line's left drawable, 0 = none. */
internal data class PanelHalf(val bgColor: Int, val main: String, val hint: String, val iconRes: Int)

/**
 * Render rule for one half of [FuelPanelDataType] — pure, so the state precedence is
 * unit-tested. Precedence: preview → OFF → tap flash → waiting → live status.
 *
 * @param enabled master switch AND this tracker on. OFF beats a flash: the tap handlers
 *   ignore taps for a disabled tracker, so offering "TAP UNDO" there would be a lie.
 * @param deficit the tracker's current deficit (negative = surplus), or null while it has
 *   not published a status yet (extension booting, no ride started) → `---`.
 * @param approx the hydration sweat estimate is LOW confidence (no live HR/power sensor) —
 *   the same leading `~` as the Hydration Status field.
 * @param slotHint what a tap logs, e.g. "💧 Sip 70ml" — kept on the hint line in every
 *   non-flash state so the rider always knows which half logs what.
 */
internal fun fuelPanelHalf(
    preview: Boolean,
    enabled: Boolean,
    flash: PanelFlash?,
    deficit: Int?,
    threshold: Int,
    unit: String,
    approx: Boolean,
    slotHint: String,
    slotIconRes: Int,
    offText: String,
    tapUndoText: String,
): PanelHalf = when {
    // Profile-editor gallery: the neutral waiting frame, like the status fields — never last
    // ride's deficit or a grey OFF while the rider is just laying out a page.
    preview -> PanelHalf(PANEL_COLOR_OK, "---", slotHint, slotIconRes)
    !enabled -> PanelHalf(PANEL_COLOR_DISABLED, offText, slotHint, 0)
    flash is PanelFlash.Logged -> PanelHalf(PANEL_COLOR_LOGGED, "+${flash.amount}$unit", tapUndoText, 0)
    flash is PanelFlash.Undone -> PanelHalf(PANEL_COLOR_UNDONE, "−${flash.amount}$unit", "✓", 0)
    deficit == null -> PanelHalf(PANEL_COLOR_OK, "---", slotHint, slotIconRes)
    else -> PanelHalf(
        fuelStatusColor(deficit, threshold),
        (if (approx) "~" else "") + formatDeficit(deficit, unit),
        slotHint,
        slotIconRes,
    )
}

/** Same bands as the status fields: green below the rider's alert threshold, amber up to
 *  50 % past it, red beyond; blue = surplus. */
internal fun fuelStatusColor(deficit: Int, threshold: Int): Int = when {
    deficit < 0                 -> PANEL_COLOR_AHEAD
    deficit < threshold         -> PANEL_COLOR_OK
    deficit < threshold * 3 / 2 -> PANEL_COLOR_AMBER
    else                        -> PANEL_COLOR_RED
}

/** "−250ml" behind, "+100ml" ahead, "0ml" on target — the status fields' sign convention. */
internal fun formatDeficit(deficit: Int, unit: String): String = when {
    deficit > 0 -> "−$deficit$unit"
    deficit < 0 -> "+${-deficit}$unit"
    else        -> "0$unit"
}
