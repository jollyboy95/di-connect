package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andrerinas.openheadunit.aap.AapService

/**
 * Serializes a switch back to CarPlay after AA has released audio and transports.
 *
 * The wait is bounded by [STOP_TIMEOUT_MS] so a stuck Android Auto shutdown cannot leave the
 * CarPlay button doing nothing. While the switch is under way AA leaves the hotspot up.
 */
object DiConnectEngine {
    private const val TAG = "DiConnectEngine"
    const val STOP_TIMEOUT_MS = 10_000L

    private val gate = StopGate<AapService>(
        Handler(Looper.getMainLooper()),
        STOP_TIMEOUT_MS,
        requestStop = { it.stopService(Intent(it, AapService::class.java)) },
        onTimeout = { Log.w(TAG, "Android Auto did not stop within $STOP_TIMEOUT_MS ms; starting CarPlay anyway") },
    )

    var service: AapService?
        get() = gate.current
        set(value) { gate.current = value }

    /** True while Android Auto is stopping so CarPlay can take over. */
    val handoverInProgress: Boolean get() = gate.stopping

    /** Whether CarPlay must wait for Android Auto before it starts. */
    fun needsStop(): Boolean = gate.needsStop()

    fun stopThen(completion: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        gate.stopThen(completion)
    }

    fun onStopped() = gate.onStopped()

    fun open(activity: Activity) {
        App.get(activity).initializeAndroidAuto()
        activity.startActivity(Intent(activity, com.andrerinas.openheadunit.main.MainActivity::class.java))
    }
}
