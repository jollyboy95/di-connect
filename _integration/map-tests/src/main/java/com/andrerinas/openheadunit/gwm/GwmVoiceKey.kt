package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The steering wheel voice button opens the phone's assistant while Android Auto runs on a GWM
 * head unit, instead of the car's own voice assistant.
 *
 * The H7 never turns that button into an Android key event: it goes to the car's hardkey_service,
 * which hands it to the car's voice assistant. Apps can register a listener there for one key
 * (1005, the voice button); the listener sees the press first and can keep it from the car. The
 * service only keeps the newest listener on top, so the registration is renewed every few seconds
 * while Android Auto runs. As a backstop the button's last state is also read from gwm_adapter
 * (sys.other.keyevent_notify), in case another app holds the key.
 *
 * Outside Android Auto the listener lets every press through, so the car's assistant (and CarPlay's
 * Siri) work as before.
 */
object GwmVoiceKey {
    private const val SERVICE = "hardkey_service"
    private const val SERVICE_DESCRIPTOR = "com.gwm.app.hardkey.IHardKeyService"
    private const val LISTENER_DESCRIPTOR = "com.gwm.app.hardkey.IHardKeyListener"
    private const val TRANSACTION_REGISTER = 3
    private const val TRANSACTION_KEY_EVENT = 1
    private const val ADAPTER = "gwm_adapter"
    private const val ADAPTER_DESCRIPTOR = "com.gwm.android.adapter.IGwmAdapterService"
    private const val ADAPTER_GET = 1
    private const val KEY_STATE = "sys.other.keyevent_notify"
    const val VOICE_KEY = 1005
    private const val REFRESH_MS = 5_000L
    private const val POLL_MS = 80L
    private const val PRESS_GAP_MS = 1_000L
    private const val PREFS = "gwm_voice_key"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var session = false
    @Volatile private var app: Context? = null
    @Volatile private var lastPressAt = 0L
    @Volatile private var poller: Thread? = null
    @Volatile private var registered = "not tried"
    @Volatile private var lastEvent = "none yet"

    val last: String get() = "listener $registered; last press $lastEvent"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (!on) sessionEnded(context)
    }

    private val listener = object : Binder(), IInterface {
        init { attachInterface(this, LISTENER_DESCRIPTOR) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(LISTENER_DESCRIPTOR); return true }
            if (code != TRANSACTION_KEY_EVENT) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(LISTENER_DESCRIPTOR)
            var consumed = 0
            if (data.readInt() != 0) {
                val action = data.readInt()
                val key = data.readInt()
                data.readInt() // flags
                val repeat = data.readInt()
                if (key == VOICE_KEY && session) {
                    consumed = 1
                    if (action == KeyEvent.ACTION_DOWN && repeat == 0) pressed("wheel button")
                }
            }
            reply?.writeNoException()
            reply?.writeInt(consumed)
            return true
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!session) return
            Thread({ register() }, "gwm-voice-key-register").start()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun sessionStarted(context: Context) {
        if (!GwmMode.active(context) || !enabled(context)) return
        app = context.applicationContext
        session = true
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        startPolling()
    }

    fun sessionEnded(@Suppress("UNUSED_PARAMETER") context: Context) {
        session = false
        handler.removeCallbacks(refresh)
        poller?.interrupt()
        poller = null
    }

    /** Sends Android Auto's assistant key (the phone's Google Assistant or Gemini). */
    private fun pressed(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPressAt < PRESS_GAP_MS) return
        lastPressAt = now
        val context = app ?: return
        lastEvent = "${time()} from the $source"
        AppLog.i("GwmVoiceKey: voice button from $source, opening the phone's assistant")
        handler.post {
            runCatching {
                val comm = App.provide(context).commManager
                comm.sendKey(KeyEvent.KEYCODE_SEARCH, true, null, "gwm-voice-key")
                comm.sendKey(KeyEvent.KEYCODE_SEARCH, false, null, "gwm-voice-key")
            }.onFailure { lastEvent = "${time()} could not reach Android Auto: ${it.javaClass.simpleName}" }
        }
    }

    private fun service(name: String): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull()

    private fun register() {
        val context = app ?: return
        val binder = service(SERVICE)
        if (binder == null) { registered = "unavailable (no hardkey_service)"; return }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        registered = try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(context.packageName)
            data.writeInt(0)
            data.writeStrongBinder(listener)
            if (!binder.transact(TRANSACTION_REGISTER, data, reply, 0)) "refused"
            else { reply.readException(); "registered ${time()}" }
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Backstop: watch the button's last state in gwm_adapter, firing once each time it goes down. */
    private fun startPolling() {
        if (poller?.isAlive == true) return
        poller = Thread({
            var previous: Int? = null
            while (session && !Thread.currentThread().isInterrupted) {
                val state = runCatching { readKeyState() }.getOrNull()
                if (state != null && state.first == VOICE_KEY) {
                    val action = state.second
                    // The first reading is only a baseline: it may be a press from long ago.
                    if (previous != null && previous != action && action == KeyEvent.ACTION_DOWN) pressed("car's key report")
                    previous = action
                }
                try { Thread.sleep(if (state == null) 500 else POLL_MS) } catch (_: InterruptedException) { break }
            }
        }, "gwm-voice-key-poll").apply { isDaemon = true; start() }
    }

    /** Reads "[key, action]" from gwm_adapter, as the car's own apps do. */
    private fun readKeyState(): Pair<Int, Int>? {
        val context = app ?: return null
        val binder = service(ADAPTER) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ADAPTER_DESCRIPTOR)
            data.writeInt(1)
            data.writeString(context.packageName)
            data.writeStringArray(arrayOf(KEY_STATE))
            data.writeStringArray(null)
            data.writeInt(1)
            if (!binder.transact(ADAPTER_GET, data, reply, 0)) return null
            reply.readException()
            val raw = reply.createStringArray()?.firstOrNull() ?: return null
            val parts = raw.replace("[", "").replace("]", "").trim().split(",")
            if (parts.size < 2) return null
            return parts[0].trim().toInt() to parts[1].trim().toInt()
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun time() = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())
}
