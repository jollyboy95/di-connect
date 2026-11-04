package com.andrerinas.openheadunit.gwm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
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
 * stops the car reconnecting mid-session; it goes back to 100 (on) when Android Auto ends.
 */
object GwmBluetoothMedia {
    private const val PROFILE_A2DP_SINK = 11
    private const val PRIORITY_OFF = 0
    private const val PRIORITY_ON = 100
    private const val PREFS = "gwm_bluetooth_media"
    private const val KEY_PAUSED = "paused_addresses"

    @Volatile var last: String = "not used yet"
        private set

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (!on) restore(context)
    }

    /** Android Auto connected: switch off the Bluetooth music link of every connected phone. */
    fun sessionStarted(context: Context) {
        if (!GwmMode.active(context) || !enabled(context)) return
        withSink(context) { proxy ->
            val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
            if (devices.isEmpty()) { note("no Bluetooth music link to switch off"); return@withSink }
            val paused = mutableSetOf<String>()
            devices.forEach { device ->
                val priority = call(proxy, "setPriority", device, PRIORITY_OFF)
                val disconnected = call(proxy, "disconnect", device, null)
                paused += device.address
                note("Bluetooth music off for ${device.name ?: "phone"} (disconnect=$disconnected, hold=$priority)")
            }
            save(context, paused)
        }
    }

    /** Android Auto ended: allow the Bluetooth music link again. */
    fun sessionEnded(context: Context) = restore(context)

    private fun restore(context: Context) {
        val paused = load(context)
        if (paused.isEmpty()) return
        withSink(context) { proxy ->
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withSink
            paused.forEach { address ->
                val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return@forEach
                call(proxy, "setPriority", device, PRIORITY_ON)
            }
            save(context, emptySet())
            note("Bluetooth music allowed again")
        }
    }

    private fun withSink(context: Context, work: (BluetoothProfile) -> Unit) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) return
        val app = context.applicationContext
        val asked = runCatching {
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try { work(proxy) } catch (e: Exception) { note("failed: ${e.javaClass.simpleName} ${e.message}") }
                    finally { runCatching { adapter.closeProfileProxy(PROFILE_A2DP_SINK, proxy) } }
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, PROFILE_A2DP_SINK)
        }.getOrDefault(false)
        if (!asked) note("the car's Bluetooth music service did not answer")
    }

    private fun call(proxy: BluetoothProfile, name: String, device: BluetoothDevice, value: Int?): String = runCatching {
        val method = if (value == null) proxy.javaClass.getMethod(name, BluetoothDevice::class.java)
            else proxy.javaClass.getMethod(name, BluetoothDevice::class.java, Int::class.javaPrimitiveType)
        (if (value == null) method.invoke(proxy, device) else method.invoke(proxy, device, value)).toString()
    }.getOrElse { "${(it.cause ?: it).javaClass.simpleName}" }

    private fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()

    private fun save(context: Context, addresses: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_PAUSED, addresses).apply()
    }

    private fun note(what: String) {
        last = "${java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())} $what"
        AppLog.i("GwmBluetoothMedia: $what")
    }
}
