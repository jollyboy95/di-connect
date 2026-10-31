package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.andrerinas.openheadunit.DiConnectAndroidAuto

/**
 * One primary phone. When Di-Connect opens with Android Auto and an Android phone is chosen, that
 * phone is the primary one: when it reaches the car's Bluetooth, Android Auto connects by itself,
 * and an iPhone reaching the car's Bluetooth no longer starts CarPlay. CarPlay is still one tap
 * away on the home screen (or by USB), and a CarPlay session in use is never taken over.
 * With CarPlay chosen as what Di-Connect opens with, or no Android phone chosen, D-Play's own
 * iPhone wake-up applies unchanged.
 */
internal object DiConnectPhoneWake {
    const val EXTRA_ANDROID_AUTO = "com.jollyboy95.diconnect.AUTO_CONNECT_ANDROID_AUTO"
    private const val TAG = "DiConnectPhoneWake"

    @Volatile var last: String? = null
        private set

    /** True when Android Auto is primary and this event is fully handled here. */
    fun handle(context: Context, intent: Intent): Boolean {
        if (DiConnectAndroidAuto.opensWithCarPlay(context)) return false
        val primary = DiConnectAndroidAuto.phoneAddress(context) ?: return false
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return true
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return true
        val address = runCatching { device.address }.getOrNull() ?: return true
        if (!address.equals(primary, ignoreCase = true)) {
            note("another phone reached Bluetooth; Android Auto is primary, so CarPlay was not started")
            return true
        }
        // With Android Auto primary, CarPlay only ever runs because someone chose it (a tap, or an
        // iPhone on USB), so the Android phone coming back on Bluetooth never takes over from it.
        if (CarPlayBackgroundSession.hasSession()) {
            note("primary phone reached Bluetooth; CarPlay is in use, so it was left running")
            return true
        }
        if (runCatching { DiConnectAndroidAuto.isConnected(context) }.getOrDefault(false)) {
            note("primary phone reached Bluetooth; Android Auto already connected")
            return true
        }
        val prefs = context.getSharedPreferences("di_connect_phone_wake", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong("last_launch_elapsed", 0)
        if (prefs.contains("last_launch_elapsed") && now >= previous && now - previous < 20_000) return true
        prefs.edit().putLong("last_launch_elapsed", now).commit()
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_ANDROID_AUTO, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onSuccess { note("primary phone reached Bluetooth; connecting Android Auto") }
            .onFailure { note("primary phone reached Bluetooth; launch failed: ${it.javaClass.simpleName}") }
        return true
    }

    fun report(): String = "Primary phone wake: ${last ?: "no event since start"}"

    private fun note(what: String) {
        val line = "${java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())} $what"
        Log.i(TAG, line)
        last = line
    }
}
