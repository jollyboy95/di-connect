package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds where the head unit keeps its hotspot band, so Di-Connect can later hold it on 5 GHz.
 * On the H7 the car's own setting falls back to "Both" (which can pick 2.4 GHz and makes Android
 * Auto audio stutter), and an app cannot read the band itself on Android 9. The local ADB shell
 * can read dumpsys, settings tables and properties. Names, passwords and addresses are dropped.
 */
internal object HotspotBandProbe {
    @Volatile private var last: String? = null

    private val COMMANDS = listOf(
        "dumpsys wifi" to "dumpsys wifi 2>&1 | grep -iE 'softap|apband|ap_band|apchannel|channel|frequency|hostapd|band' | head -n 60",
        "settings global" to "settings list global 2>&1 | grep -iE 'band|hotspot|tether|softap|wifi_ap|_ap_' | head -n 40",
        "settings system" to "settings list system 2>&1 | grep -iE 'band|hotspot|tether|softap|wifi_ap|_ap_' | head -n 40",
        "settings secure" to "settings list secure 2>&1 | grep -iE 'band|hotspot|tether|softap|wifi_ap|_ap_' | head -n 40",
        "properties" to "getprop 2>&1 | grep -iE 'hostapd|softap|wifi.*ap|band|5g|tether' | head -n 40",
        "packages" to "pm list packages 2>&1 | grep -iE 'setting|wifi|hotspot|tether' | head -n 30",
        "wifi files" to "ls -l /data/misc/wifi /data/vendor/wifi/hostapd 2>&1 | head -n 20",
        "cmd wifi" to "cmd wifi help 2>&1 | head -n 25",
    )

    private val PRIVATE = Regex("ssid|psk|pass|key|bssid|mac|addr|secret|network name", RegexOption.IGNORE_CASE)

    /** Runs the probe over local ADB (only if already approved) and returns readable text. Blocking. */
    fun run(context: Context): String {
        val text = runCatching {
            LocalAdb(AdbKeys.load(context.applicationContext)).use { adb ->
                when (val access = adb.connect(mayAsk = false)) {
                    LocalAdb.Access.READY -> buildString {
                        for ((title, command) in COMMANDS) {
                            appendLine("[$title]")
                            val out = adb.shell(command, 8_000).orEmpty().lines()
                                .map { it.trimEnd() }.filter { it.isNotBlank() }
                            val kept = out.filterNot { PRIVATE.containsMatchIn(it) }
                            kept.forEach { appendLine("  ${it.take(200)}") }
                            if (kept.isEmpty()) appendLine("  (nothing)")
                            if (kept.size < out.size) appendLine("  (${out.size - kept.size} private line(s) left out)")
                        }
                    }
                    else -> "Local ADB not available ($access). Approve Di-Connect's ADB access, then scan again."
                }
            }
        }.getOrElse { "Hotspot band probe failed: ${it.javaClass.simpleName}" }
        val stamped = "--- Hotspot band probe ${SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())} ---\n$text".trimEnd()
        last = stamped
        return stamped
    }

    fun report(): String = last ?: "--- Hotspot band probe: not run (Settings > GWM cluster > Scan displays) ---"
}
