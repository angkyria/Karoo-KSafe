package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.HardwareType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KarooHardware.isKaroo2]'s pure core. A Karoo 2 must be recognised from either
 * signal: the SDK hardware type (once the service has connected) or the build model / device
 * ("k2" on a Karoo 2) — the latter is all BuzzerClient and the settings UI have to go on.
 */
class KarooHardwareTest {

    @Test fun `sdk hardware type K2 is a Karoo 2`() {
        assertTrue(KarooHardware.isKaroo2(HardwareType.K2, model = null, device = null))
    }

    @Test fun `build model or device k2 is a Karoo 2 before the sdk connects`() {
        assertTrue(KarooHardware.isKaroo2(null, model = "k2", device = null))
        assertTrue(KarooHardware.isKaroo2(null, model = "K2", device = null))
        assertTrue(KarooHardware.isKaroo2(null, model = null, device = "k2"))
    }

    @Test fun `karoo 3 and unknown devices are not a Karoo 2`() {
        assertFalse(KarooHardware.isKaroo2(HardwareType.KAROO, model = "Karoo 3", device = "karoo3"))
        assertFalse(KarooHardware.isKaroo2(HardwareType.UNKNOWN, model = null, device = null))
        assertFalse(KarooHardware.isKaroo2(null, model = null, device = null))
    }
}
