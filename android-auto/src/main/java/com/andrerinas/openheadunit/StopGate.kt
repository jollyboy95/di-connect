package com.andrerinas.openheadunit

import android.os.Handler

/**
 * Runs work once a running backend has stopped, but never waits longer than [timeoutMs].
 *
 * All calls must be made on [handler]'s thread. After a timeout the stuck backend is
 * abandoned: [needsStop] stays false for it, so a retry cannot wait on it again, while
 * [stopping] stays true until it really stops (or another backend replaces it).
 */
internal class StopGate<T : Any>(
    private val handler: Handler,
    private val timeoutMs: Long,
    private val requestStop: (T) -> Unit,
    private val onTimeout: (T) -> Unit = {},
) {
    var current: T? = null
        set(value) {
            if (value != null && value !== field) { abandoned = null; stopping = false }
            field = value
        }

    /** True from a stop request until the backend has actually stopped. */
    var stopping = false
        private set

    private var abandoned: T? = null
    private val waiting = mutableListOf<() -> Unit>()
    private val timeout = Runnable {
        current?.let { stuck -> abandoned = stuck; onTimeout(stuck) }
        release()
    }

    /** Whether new work must wait for the running backend to stop first. */
    fun needsStop(): Boolean = current.let { it != null && it !== abandoned }

    fun stopThen(completion: () -> Unit) {
        val running = current
        if (running == null || running === abandoned) { completion(); return }
        waiting.add(completion)
        if (stopping && waiting.size > 1) return // A stop is already under way.
        stopping = true
        handler.postDelayed(timeout, timeoutMs)
        requestStop(running)
    }

    fun onStopped() {
        current = null
        abandoned = null
        stopping = false
        release()
    }

    private fun release() {
        handler.removeCallbacks(timeout)
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { handler.post(it) }
    }
}
