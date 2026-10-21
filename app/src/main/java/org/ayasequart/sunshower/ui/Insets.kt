package org.ayasequart.sunshower.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.applySystemBarInsets(extraTopPx: Int = 0, extraBottomPx: Int = 0) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            view.paddingLeft,
            bars.top + extraTopPx,
            view.paddingRight,
            bars.bottom + extraBottomPx
        )
        WindowInsetsCompat.CONSUMED
    }
}
