package com.enderthor.kSafe.extension.util

import android.view.View

/**
 * Sets `clipToOutline` on the given descendants of an inflated overlay. The overlay layouts
 * also declare `android:clipToOutline` in XML, but that attribute is only read from Android 12
 * (API 31): on the Karoo 2 (Android 8.1) it is ignored and the rounded cards / buttons draw
 * their content with square corners. [View.setClipToOutline] itself exists since API 21, so
 * setting it in code rounds them on every Karoo.
 */
internal fun View.clipToOutlineCompat(vararg viewIds: Int) {
    for (id in viewIds) findViewById<View>(id)?.clipToOutline = true
}
