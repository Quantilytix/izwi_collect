package com.quantilytix.izwi.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads a view for the system status/navigation bars so headers and bottom
 * actions never sit underneath them. Written against explicit
 * WindowInsetsCompat handling rather than fitsSystemWindows or the API 35
 * edge-to-edge opt-out, since both behave inconsistently once a colored
 * header needs to visually extend under the status bar while its content
 * doesn't. Real-device testing (a bottom button and a screen header both
 * obscured by system bars) is what surfaced the need for this.
 */
fun View.applySystemBarInsetPadding(applyTop: Boolean = false, applyBottom: Boolean = false) {
    val baseTop = paddingTop
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            top = if (applyTop) baseTop + bars.top else baseTop,
            bottom = if (applyBottom) baseBottom + bars.bottom else baseBottom,
        )
        insets
    }
}
