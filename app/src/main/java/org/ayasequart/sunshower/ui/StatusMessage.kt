package org.ayasequart.sunshower.ui

import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatusMessage(
    private val scope: CoroutineScope,
    private val target: TextView,
    private val durationMs: Long = DEFAULT_DURATION_MS
) {

    private var job: Job? = null

    fun show(text: CharSequence) {
        cancel()
        target.text = text
        job = scope.launch {
            delay(durationMs)
            target.text = ""
        }
    }

    fun clear() {
        cancel()
        target.text = ""
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_DURATION_MS = 3000L
    }
}
