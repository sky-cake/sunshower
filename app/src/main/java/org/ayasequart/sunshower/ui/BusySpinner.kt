package org.ayasequart.sunshower.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar

class BusySpinner(private val activity: Activity) {

    private var overlay: FrameLayout? = null

    fun show() {
        activity.runOnUiThread {
            if (overlay != null) return@runOnUiThread
            val scrim = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0x88000000.toInt())
                isClickable = true
                isFocusable = true
            }
            scrim.addView(
                ProgressBar(activity),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            (activity.findViewById<View>(android.R.id.content) as ViewGroup)
                .addView(scrim)
            overlay = scrim
        }
    }

    fun hide() {
        val current = overlay ?: return
        overlay = null
        activity.runOnUiThread {
            (current.parent as? ViewGroup)?.removeView(current)
        }
    }
}
