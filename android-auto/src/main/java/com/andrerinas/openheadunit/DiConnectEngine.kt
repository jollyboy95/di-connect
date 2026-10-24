package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.aap.AapService

/** Serializes a switch back to CarPlay after AA has released audio and transports. */
object DiConnectEngine {
    var service: AapService? = null
    private val waiting = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun stopThen(completion: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val running = service
        if (running == null) { completion(); return }
        waiting.add(completion)
        running.stopService(Intent(running, AapService::class.java))
    }
    fun onStopped() {
        service = null
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { main.post(it) }
    }
    fun open(activity: Activity) {
        App.get(activity).initializeAndroidAuto()
        activity.startActivity(Intent(activity, com.andrerinas.openheadunit.main.MainActivity::class.java))
    }
}
