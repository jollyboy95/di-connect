package com.andrerinas.openheadunit.gwm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.utils.AppLog

/**
 * While Android Auto runs on a GWM head unit, the phone's Bluetooth music link to the car is
 * switched off, and it is allowed back once Android Auto ends.
 *
 * Music already reaches the car inside Android Auto. A Bluetooth media link to the same phone
 * makes the car's own Bluetooth player compete for the audio and causes stutter, which the driver
 * otherwise has to stop by hand each time. Calls (HFP) are left alone.
 *
 * The head unit is the A2DP sink (profile 11, hidden from the SDK). Its proxy's disconnect and
 * setPriority are reached by reflection; on Android 9 they need only BLUETOOTH_ADMIN. Priority 0
 * stops the car reconnecting mid-session; it goes back to 100 (on) when Android Auto ends. The
 * phone often connects its music link after Android Auto has started, so the switch keeps
 * watching through the session rather than looking once.
 */
object GwmBluetoothMedia {
    private const val PROFILE_A2DP_SINK = 11
    private const val PRIORITY_OFF = 0
    private const val PRIORITY_ON = 100
    private const val PREFS = "gwm_bluetooth_media"
    private const val KEY_PAUSED = "paused_addresses"
    private const val ACTION_SINK_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    /** The phone often brings its music link up a little after Android Auto starts, so look again. */
    private val RETRY_MS = longArrayOf(3_000, 10_000, 25_000, 60_000)

    private val handler = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<String>()
    @Volatile private var session = false
    private var receiver: BroadcastReceiver? = null

    /** The last few things this did, newest last, for the diagnostic report. */
    val last: String get() = synchronized(history) { if (history.isEmpty()) "not used yet" else history.joinToString(" | ") }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (!on) { stopWatching(context); restore(context) }
    }

    /**
     * Android Auto connected: switch off the Bluetooth music link of every connected phone now,
     * a few more times over the first minute, and whenever the car brings it back during the session.
     */
    fun sessionStarted(context: Context) {
        if (!GwmMode.active(context) || !enabled(context)) return
        val app = context.applicationContext
        session = true
        sweep(app, "Android Auto started")
        RETRY_MS.forEach { delay -> handler.postDelayed({ if (session) sweep(app, "check after ${delay / 1000} s") }, delay) }
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    // 1 = connecting, 2 = connected.
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (session && (state == 1 || state == 2)) handler.postDelayed({ if (session) sweep(app, "music link came back") }, 800)
                }
            }
            runCatching { app.registerReceiver(receiver, IntentFilter(ACTION_SINK_STATE)) }
                .onFailure { receiver = null; note("could not watch the music link: ${it.javaClass.simpleName}") }
        }
    }

    /** Android Auto ended: allow the Bluetooth music link again. */
    fun sessionEnded(context: Context) {
        stopWatching(context)
        restore(context)
    }

    private fun stopWatching(context: Context) {
        session = false
        handler.removeCallbacksAndMessages(null)
        receiver?.let { r -> runCatching { context.applicationContext.unregisterReceiver(r) } }
        receiver = null
    }

    private fun sweep(context: Context, why: String) {
        withSink(context, why) { proxy ->
            val devices = runCatching {
                @Suppress("UNCHECKED_CAST")
                proxy.javaClass.getMethod("getDevicesMatchingConnectionStates", IntArray::class.java)
                    .invoke(proxy, intArrayOf(1, 2)) as List<BluetoothDevice>
            }.getOrElse { runCatching { proxy.connectedDevices }.getOrDefault(emptyList()) }
            if (devices.isEmpty()) {
                // Say whether a phone is on Bluetooth at all, so a report shows which case this is.
                val phones = runCatching {
                    BluetoothAdapter.getDefaultAdapter().bondedDevices.filter { d ->
                        runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
                    }.map { it.name ?: "phone" }
                }.getOrDefault(emptyList())
                note("$why: no Bluetooth music link" + if (phones.isEmpty()) "" else " (on Bluetooth: ${phones.joinToString()})")
                return@withSink
            }
            val paused = load(context).toMutableSet()
            devices.forEach { device ->
                val hold = call(proxy, "setPriority", device, PRIORITY_OFF)
                val disconnected = call(proxy, "disconnect", device, null)
                paused += device.address
                note("$why: music off for ${device.name ?: "phone"} (disconnect=$disconnected, hold=$hold)")
            }
            save(context, paused)
        }
    }

    private fun restore(context: Context) {
        val paused = load(context)
        if (paused.isEmpty()) return
        withSink(context, "Android Auto ended") { proxy ->
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withSink
            paused.forEach { address ->
                val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return@forEach
                call(proxy, "setPriority", device, PRIORITY_ON)
            }
            save(context, emptySet())
            note("Bluetooth music allowed again")
        }
    }

    private fun withSink(context: Context, why: String, work: (BluetoothProfile) -> Unit) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) { note("$why: Bluetooth is off"); return }
        val app = context.applicationContext
        val asked = runCatching {
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try { work(proxy) } catch (e: Exception) { note("$why: failed ${e.javaClass.simpleName} ${e.message}") }
                    finally { runCatching { adapter.closeProfileProxy(PROFILE_A2DP_SINK, proxy) } }
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, PROFILE_A2DP_SINK)
        }.getOrDefault(false)
        if (!asked) note("$why: the car's Bluetooth music service did not answer")
    }

    private fun call(proxy: BluetoothProfile, name: String, device: BluetoothDevice, value: Int?): String = runCatching {
        val method = if (value == null) proxy.javaClass.getMethod(name, BluetoothDevice::class.java)
            else proxy.javaClass.getMethod(name, BluetoothDevice::class.java, Int::class.javaPrimitiveType)
        (if (value == null) method.invoke(proxy, device) else method.invoke(proxy, device, value)).toString()
    }.getOrElse { "${(it.cause ?: it).javaClass.simpleName}" }

    private fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()

    private fun save(context: Context, addresses: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_PAUSED, HashSet(addresses)).apply()
    }

    private fun note(what: String) {
        val line = "${java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())} $what"
        synchronized(history) { history.addLast(line); while (history.size > 4) history.removeFirst() }
        AppLog.i("GwmBluetoothMedia: $what")
    }
}

/**
 * While Android Auto runs on a GWM head unit, the car's own Wi-Fi (its connection to another
 * network on wlan0) is switched off, and switched back on when Android Auto ends.
 *
 * The H7 drives its hotspot (wlan1) and its Wi-Fi connection (wlan0) from one radio. When both run
 * on different channels the radio takes turns between them, and the phone's link stalls for one to
 * two seconds at a time: the stutter seen in reports 13 to 16, all with wlan0 connected on
 * 5 GHz hotspot drives. The hotspot itself stays on.
 */
object GwmCarWifi {
    private const val PREFS = "gwm_car_wifi"

    @Volatile var last: String = "not used yet"
        private set

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (!on) sessionEnded(context)
    }

    @Suppress("DEPRECATION")
    fun sessionStarted(context: Context) {
        if (!GwmMode.active(context) || !enabled(context)) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
        if (!runCatching { wifi.isWifiEnabled }.getOrDefault(false)) { note("car Wi-Fi already off"); return }
        val ok = runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false)
        if (ok) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("turned_off", true).apply()
        note(if (ok) "car Wi-Fi off while Android Auto runs (hotspot stays on)" else "car Wi-Fi could not be switched off")
    }

    @Suppress("DEPRECATION")
    fun sessionEnded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("turned_off", false)) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
        val ok = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
        prefs.edit().putBoolean("turned_off", false).apply()
        note(if (ok) "car Wi-Fi back on" else "car Wi-Fi could not be switched back on")
    }

    private fun note(what: String) {
        last = "${java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())} $what"
        AppLog.i("GwmCarWifi: $what")
    }
}
