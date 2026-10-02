package com.enderthor.kSafe.datatype

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [fuelPanelHalf] — the render rule behind each half of the Fuel Panel field.
 * Precedence is preview → OFF → tap flash → waiting → live status, and the live colours must
 * match the Hydration / Carb Status fields' bands.
 */
class FuelPanelHalfTest {

    private val icon = 42

    private fun half(
        preview: Boolean = false,
        enabled: Boolean = true,
        flash: PanelFlash? = null,
        deficit: Int? = 250,
        threshold: Int = 300,
        approx: Boolean = false,
    ) = fuelPanelHalf(
        preview = preview,
        enabled = enabled,
        flash = flash,
        deficit = deficit,
        threshold = threshold,
        unit = "ml",
        approx = approx,
        slotHint = "💧 Sip 70ml",
        slotIconRes = icon,
        offText = "OFF",
        tapUndoText = "TAP UNDO",
    )

    @Test fun `live deficit shows the signed amount over its status colour with the slot hint`() {
        assertEquals(PanelHalf(PANEL_COLOR_OK, "−250ml", "💧 Sip 70ml", icon), half(deficit = 250))
        assertEquals(PanelHalf(PANEL_COLOR_AHEAD, "+100ml", "💧 Sip 70ml", icon), half(deficit = -100))
        assertEquals(PanelHalf(PANEL_COLOR_OK, "0ml", "💧 Sip 70ml", icon), half(deficit = 0))
    }

    @Test fun `colour bands match the status fields`() {
        assertEquals(PANEL_COLOR_AHEAD, half(deficit = -1).bgColor)
        assertEquals(PANEL_COLOR_OK, half(deficit = 299).bgColor)     // below the threshold
        assertEquals(PANEL_COLOR_AMBER, half(deficit = 300).bgColor)  // threshold .. 1.5×
        assertEquals(PANEL_COLOR_AMBER, half(deficit = 449).bgColor)
        assertEquals(PANEL_COLOR_RED, half(deficit = 450).bgColor)    // 1.5× and beyond
    }

    @Test fun `low-confidence sweat estimate gets the leading tilde`() {
        assertEquals("~−250ml", half(approx = true).main)
    }

    @Test fun `no status yet is the neutral waiting frame, still naming the slot`() {
        assertEquals(PanelHalf(PANEL_COLOR_OK, "---", "💧 Sip 70ml", icon), half(deficit = null))
    }

    @Test fun `tap flashes show the logged or reversed amount without the icon`() {
        assertEquals(
            PanelHalf(PANEL_COLOR_LOGGED, "+70ml", "TAP UNDO", 0),
            half(flash = PanelFlash.Logged(70)),
        )
        assertEquals(
            PanelHalf(PANEL_COLOR_UNDONE, "−70ml", "✓", 0),
            half(flash = PanelFlash.Undone(70)),
        )
    }

    @Test fun `a flash beats the waiting frame`() {
        assertEquals("+70ml", half(flash = PanelFlash.Logged(70), deficit = null).main)
    }

    @Test fun `OFF beats a flash, since the tap handler ignores a disabled tracker`() {
        assertEquals(
            PanelHalf(PANEL_COLOR_DISABLED, "OFF", "💧 Sip 70ml", 0),
            half(enabled = false, flash = PanelFlash.Logged(70)),
        )
    }

    @Test fun `profile-editor preview is always the neutral frame`() {
        val neutral = PanelHalf(PANEL_COLOR_OK, "---", "💧 Sip 70ml", icon)
        assertEquals(neutral, half(preview = true, deficit = 900))
        assertEquals(neutral, half(preview = true, enabled = false))
        assertEquals(neutral, half(preview = true, flash = PanelFlash.Logged(70)))
    }

    @Test fun `formatDeficit uses the status fields' sign convention`() {
        assertEquals("−30g", formatDeficit(30, "g"))
        assertEquals("+5g", formatDeficit(-5, "g"))
        assertEquals("0g", formatDeficit(0, "g"))
    }
}
