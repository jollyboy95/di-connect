package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Sample directions for testing while parked: a run of every kind of turn the cluster and HUD can
 * draw, about three seconds each. While it runs it replaces Android Auto's guidance on the cluster
 * and the HUD; real guidance returns as soon as it ends.
 */
internal object NavDemo {
    const val STEP_MILLIS = 3_000L

    /** icon codes are the AMap cluster codes the cluster and HUD draw. */
    val steps: List<ClusterTurnGuidance> = listOf(
        ClusterTurnGuidance(2, 0, 300, "Hunter St"),
        ClusterTurnGuidance(3, 0, 150, "King St"),
        ClusterTurnGuidance(4, 0, 800, "Pacific Hwy"),
        ClusterTurnGuidance(5, 0, 1_200, "Central Coast Hwy"),
        ClusterTurnGuidance(6, 0, 90, "Darby St"),
        ClusterTurnGuidance(7, 0, 60, "Parry St"),
        ClusterTurnGuidance(8, 0, 40, "Ocean St"),
        ClusterTurnGuidance(19, 0, 40, "Ocean St"),
        ClusterTurnGuidance(11, 2, 500, "Wallsend Rd"),
        ClusterTurnGuidance(17, 3, 250, "Lake Rd"),
        ClusterTurnGuidance(9, 0, 2_400, "M1 Pacific Mwy"),
        ClusterTurnGuidance(15, 0, 120, "Destination"),
    )

    @Volatile private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    val active: Boolean get() = startedAt != 0L && SystemClock.elapsedRealtime() - startedAt < steps.size * STEP_MILLIS

    /** The sample for this moment, or null when the preview is not running. */
    fun current(now: Long = SystemClock.elapsedRealtime()): ClusterTurnGuidance? {
        if (startedAt == 0L) return null
        val index = ((now - startedAt) / STEP_MILLIS).toInt()
        return steps.getOrNull(index)
    }

    /** Starts the run; [changed] is called on the main thread at every step and once at the end. */
    fun start(changed: () -> Unit = {}) {
        handler.removeCallbacksAndMessages(null)
        startedAt = SystemClock.elapsedRealtime()
        for (i in 1..steps.size) handler.postDelayed({
            if (i == steps.size) startedAt = 0L
            changed()
        }, i * STEP_MILLIS)
        changed()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        startedAt = 0L
    }
}
