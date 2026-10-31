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
        if (canSwitch(app)) {
            if (!enabled(app)) {
                if (runCatching { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1) }.getOrDefault(false)) {
                    Log.i(TAG, "ADB switched on")
                    waitReady(app, 8_000)
                }
            } else if (!reachable(app)) restartIfStuck(app, "on demand")
        }
        // Left on: the driver wants ADB available without visiting the car's settings.
        return work()
    }

    /** True when the head unit's adbd answers on its local port and accepts Di-Connect's key. */
    private fun reachable(app: Context): Boolean =
        runCatching { LocalAdb(AdbKeys.load(app)).use { it.connect(mayAsk = false) } }.getOrNull() == LocalAdb.Access.READY

    private fun waitReady(app: Context, ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (reachable(app)) return true
            Thread.sleep(500)
        }
        return false
    }

    /** True when something listens on adbd's local port at all (a refusal means adbd is not on it). */
    private fun portOpen(): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 1_500); true }
    }.getOrDefault(false)

    @Volatile private var lastRestart = 0L

    /**
     * ADB can read as on while adbd is not answering on its local port (seen on the H7 after a
     * restart): the same off-and-on the driver would do in the car's settings restarts adbd. At
     * most once a minute, so a slow boot is not interrupted over and over.
     */
    private fun restartIfStuck(app: Context, why: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRestart < 60_000) return false
        lastRestart = now
        val port = if (portOpen()) "port open, no answer" else "port closed"
        val ok = runCatching {
            val resolver = app.contentResolver
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
            Thread.sleep(1_500)
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            waitReady(app, 12_000)
        }.getOrDefault(false)
        val time = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())
        lastAction = "$time $why: ADB on but not answering ($port); switched off and on: ${if (ok) "answering now" else "still not answering"}"
        Log.i(TAG, lastAction)
        return ok
    }

    @Volatile private var lastAction = "not run"
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * At start-up and boot: switches developer options and ADB on if the car turned them off.
     * Checked again after about 20 s, 1 min and 3 min, as the car may reset ADB late in its boot.
     */
    fun keepOn(context: Context) {
        val app = context.applicationContext
        // Off the caller's thread: the boot receiver calls this on the main thread.
        Thread({ ensure(app, "start") }, "adb-keep-on").start()
        for (delay in longArrayOf(20_000L, 60_000L, 180_000L)) {
            handler.postDelayed({ Thread({ ensure(app, "${delay / 1000} s check") }, "adb-keep-on").start() }, delay)
        }
    }

    private fun ensure(app: Context, why: String) {
        val time = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())
        if (!canSwitch(app)) { lastAction = "$time $why: no permission yet (turn ADB on once and use the 5 GHz button)"; return }
        if (enabled(app)) {
            if (reachable(app)) lastAction = "$time $why: ADB already on and answering"
            // At start adbd may simply not be up yet; the later checks restart it if it stays silent.
            else if ((why == "start" || !restartIfStuck(app, why)) && !lastAction.contains("switched off and on"))
                lastAction = "$time $why: ADB on but not answering yet"
            return
        }
        val result = runCatching {
            val resolver = app.contentResolver
            if (Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1)
                Settings.Global.putInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            Thread.sleep(500)
            if (enabled(app)) "switched ADB on" else "asked to switch ADB on but the car kept it off"
        }.getOrElse { "could not switch ADB: ${it.javaClass.simpleName}: ${it.message}" }
        lastAction = "$time $why: $result"
        Log.i(TAG, lastAction)
    }

    fun report(context: Context): String {
        val r = context.applicationContext.contentResolver
        val adb = runCatching { Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) }.getOrNull()
        val dev = runCatching { Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) }.getOrNull()
        return "ADB keep-on: permission=${canSwitch(context)} adb_enabled=$adb developer_options=$dev last=$lastAction"
    }
}
