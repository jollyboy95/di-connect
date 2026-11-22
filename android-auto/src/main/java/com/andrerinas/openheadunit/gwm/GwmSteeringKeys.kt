package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Steering-wheel keys on GWM head units, read from the car's own source.
 *
 * Found in GwmAdapterServer.apk on the H7: the head unit's InputService takes the steering pad keys
 * away from apps and republishes each one through the gwm_adapter data id
 * `sys.other.keyevent_notify` as "[code, action]". DiPlay polled that id as a system property,
 * which is why it never saw a key; here it is received through the adapter's data-change listener.
 *
 * Codes and actions are the firmware's own table (InputService constants), not guesses. Which
 * physical button sends which code still has to be learned on the car: DiAuto only acts on a code
 * after it is assigned in Settings → Keymap, and drops it otherwise.
 */
internal object GwmSteeringKeys {
    const val DATA_ID = "sys.other.keyevent_notify"
    private const val LISTENER_DESCRIPTOR = "com.gwm.android.adapter.IDataChangedListener"
    private const val TRANSACTION_ON_DATA_CHANGED = 1

    /** InputService actions. */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_LONG_PRESS = 2
    const val ACTION_LONG_PRESS_UP = 3
    const val ACTION_CANCEL = 4

    data class Event(val code: Int, val action: Int, val atMillis: Long) {
        val pressed: Boolean get() = action == ACTION_DOWN
        /** Long-press keeps the key down; long-press-up and cancel release it. */
        val released: Boolean get() = action == ACTION_UP || action == ACTION_LONG_PRESS_UP || action == ACTION_CANCEL
        override fun toString(): String = "$code ${label(code)} · ${actionLabel(action)}"
    }

    // Lazy, so the parsing below can be unit-tested without an Android main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArraySet<(Event) -> Unit>()
    @Volatile var status: String = "Not listening."
        private set
    private var app: Context? = null

    private val binder by lazy { object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER_DESCRIPTOR)
                return true
            }
            if (code != TRANSACTION_ON_DATA_CHANGED) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(LISTENER_DESCRIPTOR)
            val id = data.readString()
            val value = data.readString()
            if (id == DATA_ID) parse(value, SystemClock.elapsedRealtime())?.let { event ->
                main.post { listeners.forEach { runCatching { it(event) } } }
            }
            return true
        }
    } }

    /** Listeners run on the main thread. Registers with the adapter for the first listener. */
    @Synchronized fun addListener(context: Context, listener: (Event) -> Unit) {
        listeners += listener
        if (app != null) return
        val appContext = context.applicationContext
        val accepted = runCatching { GwmVehicleData.registerListener(appContext, arrayOf(DATA_ID), binder) }
            .onFailure { status = "Not listening: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault(false)
        if (accepted) {
            app = appContext
            status = "Listening to $DATA_ID on gwm_adapter."
        } else if (!status.startsWith("Not listening:")) {
            status = "Not listening: the adapter refused the listener (GET_DATA permission missing?)."
        }
        AppLog.i("GWM keys: $status")
    }

    @Synchronized fun removeListener(listener: (Event) -> Unit) {
        listeners -= listener
        if (listeners.isNotEmpty()) return
        val context = app ?: return
        app = null
        runCatching { GwmVehicleData.unregisterListener(context, binder) }
        status = "Not listening."
    }

    private val NOTIFY = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]""")

    internal fun parse(value: String?, now: Long): Event? {
        val match = NOTIFY.find(value ?: return null) ?: return null
        val code = match.groupValues[1].toIntOrNull() ?: return null
        val action = match.groupValues[2].toIntOrNull() ?: return null
        if (code < 0 || action !in ACTION_DOWN..ACTION_CANCEL) return null
        return Event(code, action, now)
    }

    /** The firmware's names for its codes; which physical button is which is learned on the car. */
    fun label(code: Int): String = when (code) {
        1000 -> "media pad up"
        1001 -> "media pad down"
        1002 -> "media pad left"
        1003 -> "media pad right"
        1004 -> "media pad OK"
        1005 -> "voice"
        1006 -> "mute"
        1007 -> "custom (star)"
        2000 -> "second pad up"
        2001 -> "second pad down"
        2002 -> "second pad left"
        2003 -> "second pad right"
        2004 -> "second pad OK"
        2005 -> "second pad home"
        2006 -> "second pad back"
        2007 -> "second pad custom"
        in 4000..4004 -> "driver-assist key"
        else -> "unknown"
    }

    fun actionLabel(action: Int): String = when (action) {
        ACTION_DOWN -> "down"
        ACTION_UP -> "up"
        ACTION_LONG_PRESS -> "long press"
        ACTION_LONG_PRESS_UP -> "long press up"
        ACTION_CANCEL -> "cancel"
        else -> "action $action"
    }
}
