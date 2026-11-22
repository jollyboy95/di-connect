package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads the H7 hotspot band in the background and reports it in diagnostics. */
internal object HotspotBandGuard {
    private const val TAG = "DiConnect-Hotspot"
    private const val PREFS = "di_connect_hotspot"
    private const val KEY_ENABLED = "keep_5ghz"

    @Volatile private var last: String? = null
    @Volatile private var checkedThisRun = false

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun report(): String = "Hotspot band guard: ${last ?: "not run"}"

    /**
     * The hotspot as the shell sees it: saved band (-1 any, 0 2.4 GHz, 1 5 GHz or Both on this car),
     * running frequency, AP interface, and how many radios that interface bridges (null: not read).
     */
    internal data class ApState(val band: Int?, val frequency: Int?, val iface: String?, val bridgedRadios: Int? = null) {
        val channel: Int? get() = frequency?.let { channelOf(it) }
        /** True only when every signal agrees: saved 5 GHz, running at 5 GHz, on one radio. */
        val fiveGhzOnly: Boolean get() = band == 1 && (frequency ?: 0) >= 5000 &&
            iface != null && iface.startsWith("wlan") && bridgedRadios != null && bridgedRadios < 2
        fun diagnostic() = when {
            fiveGhzOnly -> "5 GHz only"
            band == null || frequency == null || iface == null || bridgedRadios == null -> "5 GHz only could not be confirmed"
            else -> "not running on 5 GHz only"
        } + " (${describe()})"

        fun describe() = "band ${when (band) { 1 -> "5 GHz"; 0 -> "2.4 GHz"; -1 -> "any"; null -> "unknown"; else -> "$band" }}, " +
            (frequency?.let { "running at $it MHz${channel?.let { c -> ", channel $c" } ?: ""}" } ?: "not running") +
            (iface?.let { " on $it" } ?: "") + (bridgedRadios?.takeIf { it >= 2 }?.let { " bridging $it radios (Both)" } ?: "")
    }

    internal fun parseAp(dump: String) = ApState(
        Regex("""mApConfig\.apBand:\s*(-?\d+)""").find(dump)?.groupValues?.get(1)?.toIntOrNull(),
        Regex("""mReportedFrequency:\s*(\d+)""").find(dump)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
        Regex("""mApInterfaceName:\s*([A-Za-z0-9_.-]+)""").find(dump)?.groupValues?.get(1),
    )

    /** Members of `/sys/class/net/<iface>/brif` that are radios; 0 when the interface is not a bridge. */
    internal fun bridgedRadios(brifListing: String): Int =
        brifListing.split(Regex("\\s+")).count { it.startsWith("wlan") }

    internal fun safeIface(name: String?) = name?.takeIf { Regex("[A-Za-z0-9_.-]{1,15}").matches(it) }

    internal fun channelOf(mhz: Int): Int? = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5160..5885 -> (mhz - 5000) / 5
        else -> null
    }

    @Volatile private var needsHelp = false

    fun needsHelp(): Boolean = needsHelp

    /** Once per app start when switched on, off the main thread. */
    fun checkOnStart(context: Context) {
        if (checkedThisRun || !enabled(context)) return
        checkedThisRun = true
        val app = context.applicationContext
        // Straight after the head unit boots, adbd is often not listening yet, and the car has
        // usually put the band back to Both at that same boot. Keep trying until ADB answers.
        Thread({
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            var result = checkBand(app)
            var tries = 1
            while (result.contains("Local ADB not available") && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(if (tries < 6) 10_000L else 30_000L) }
                tries++
                result = checkBand(app)
            }
            if (tries > 1) last = "$last (attempt $tries)"
        }, "hotspot-band-guard").start()
    }

    /** Blocking. Returns what happened, in one line. */
    fun checkBand(context: Context): String {
        val result = runCatching {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                val access = adb.connect(mayAsk = false)
                if (access != LocalAdb.Access.READY) {
                    needsHelp = true
                    return@use "Local ADB not available ($access)"
                }
                fun sh(command: String) = adb.shell(command, 10_000)
                val read = parseAp(sh("dumpsys wifi | grep -E 'mApConfig.apBand|mReportedFrequency|mApInterfaceName'").orEmpty())
                val radios = safeIface(read.iface)?.let { iface ->
                    sh("ls /sys/class/net/$iface/brif 2>/dev/null")?.let(::bridgedRadios)
                }
                val state = read.copy(bridgedRadios = radios)
                needsHelp = !state.fiveGhzOnly
                state.diagnostic()
            }
        }.getOrElse {
            needsHelp = true
            "failed: ${it.javaClass.simpleName}"
        }
        val line = "${SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())} $result"
        Log.i(TAG, line)
        last = line
        return line
    }
}
