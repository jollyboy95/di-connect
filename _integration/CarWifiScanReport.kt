package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * For the diagnostic report: whether the car scans for Wi-Fi in the background, and its recent
 * scans with their times, so a drop-out in the Android Auto link can be matched (or not) to a scan
 * by the car. Read over local ADB, read-only; off the main thread only.
 */
internal object CarWifiScanReport {
    private val SETTINGS = arrayOf("wifi_scan_always_enabled", "ble_scan_always_enabled")

    fun report(context: Context): String = buildString {
        appendLine("--- Car Wi-Fi scanning ---")
        appendLine(SETTINGS.joinToString(" ") { key ->
            "$key=${runCatching { Settings.Global.getInt(context.contentResolver, key, -1) }.getOrDefault(-1)}"
        })
        val lines = runCatching {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use listOf("scan history: local ADB not available")
                val out = adb.shell("dumpsys wifiscanner | grep -iE 'scan|result' | tail -n 60", 8_000).orEmpty()
                // Network names and addresses stay out of the report.
                out.lines().filter { it.isNotBlank() }
                    .map { it.replace(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"), "[mac]").replace(Regex("SSID[:=]\\s*\"?[^,\"]*\"?"), "SSID=[name]") }
                    .ifEmpty { listOf("scan history: none listed") }
            }
        }.getOrElse { listOf("scan history unavailable: ${it.javaClass.simpleName}") }
        lines.forEach { appendLine(it.take(220)) }
    }
}
