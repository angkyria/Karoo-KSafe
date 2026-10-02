package com.enderthor.kSafe.extension.util

import android.os.Build
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HardwareType

/**
 * Which Karoo KSafe runs on. KSafe targets the Karoo 3; the Karoo 2 (Android 8.1 / API 27)
 * runs it too, with one hard firmware limit: an extension cannot make ANY sound on it.
 * Measured on a Karoo 2 running Hammerhead OS 1.613:
 *  - the HAL buzzer bypass ([com.enderthor.kSafe.extension.managers.BuzzerClient]) and the SDK
 *    `PlayBeepPattern` both end in PhoneROMController, which logs "beep(): Nothing to do on K2";
 *  - the Karoo 2's real beeper is driven through the vibrator driver, and its VibratorService
 *    only accepts io.hammerhead.activityservice ("only io.hammerhead.activityservice is allowed");
 *  - the beeper sysfs nodes are not writable by apps.
 * So on a Karoo 2 the countdown / SOS / reminder beeps are silent and KSafe is visual-only.
 */
object KarooHardware {

    /**
     * True on a Karoo 2. Prefers the SDK's hardware type once [karooSystem] has connected, and
     * falls back to the build model ("k2"), which is available before the SDK connects and to
     * the settings UI (which has no connected [KarooSystemService] of its own).
     */
    fun isKaroo2(karooSystem: KarooSystemService? = null): Boolean =
        isKaroo2(karooSystem?.hardwareType, Build.MODEL, Build.DEVICE)

    /** Pure core of [isKaroo2], split out so it is unit-testable without Android. */
    internal fun isKaroo2(hardwareType: HardwareType?, model: String?, device: String?): Boolean =
        hardwareType == HardwareType.K2 ||
            model.equals("k2", ignoreCase = true) ||
            device.equals("k2", ignoreCase = true)
}
