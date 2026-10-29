package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Lets Di-Connect switch the car's ADB on and keep it on, so the driver does not have to turn
 * it on in the car's settings every time. The first time ADB is on and approved,
 * Di-Connect grants itself WRITE_SECURE_SETTINGS over that connection; Android keeps the grant
 * across restarts. From then on ADB is switched on when needed and at start-up, and left on.
 */
internal object AdbSwitch {
    private const val TAG = "DiConnect-Adb"
    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun canSwitch(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun enabled(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** Over an approved connection, keeps the ability to switch ADB from now on. */
    fun grantSelf(context: Context, adb: LocalAdb) {
        if (canSwitch(context)) return
        adb.shell("pm grant ${context.packageName} $PERMISSION", 8_000)
        Log.i(TAG, "self grant: ${canSwitch(context)}")
    }

    /**
     * Runs [work] with local ADB available. Switches ADB on first when it is off and Di-Connect
     * may do so, and waits for it. Without the grant it runs
     * [work] as is, so the caller reports ADB as unavailable in the usual way.
     */
    fun <T> withAdb(context: Context, work: () -> T): T {
        val app = context.applicationContext
        val turnedOn = !enabled(app) && canSwitch(app) &&
            runCatching { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1) }.getOrDefault(false)
        if (turnedOn) {
            Log.i(TAG, "ADB switched on")
            // adbd takes a moment to come up after being enabled.
            val until = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < until) {
                val ready = runCatching { LocalAdb(AdbKeys.load(app)).use { it.connect(mayAsk = false) } }.getOrNull() == LocalAdb.Access.READY
                if (ready) break
                Thread.sleep(400)
            }
        }
        // Left on: the driver wants ADB available without visiting the car's settings.
        return work()
    }

    /** At start-up: switches ADB on if the car turned it off and Di-Connect may do so. */
    fun keepOn(context: Context) {
        val app = context.applicationContext
        if (enabled(app) || !canSwitch(app)) return
        runCatching { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1) }
            .onSuccess { Log.i(TAG, "ADB switched on at start-up") }
    }
}
