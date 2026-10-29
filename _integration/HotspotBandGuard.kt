package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the H7's hotspot on 5 GHz. The car's own setting falls back to "Both", which lets the car
 * pick 2.4 GHz at some starts, and Android Auto stutters there. Android 9 lets no app set the band
 * (the shell's `cmd wifi` is refused too), and the band lives in the car's hotspot app
 * (com.mak.wifisettings), so this does what the driver does by hand over local ADB: open that
 * screen, read AP Band, and if it is not 5 GHz pick the 5 GHz option. The running frequency comes
 * from `dumpsys wifi` and is logged either way.
 */
internal object HotspotBandGuard {
    private const val TAG = "DiConnect-Hotspot"
    private const val PREFS = "di_connect_hotspot"
    private const val KEY_ENABLED = "keep_5ghz"
    private const val HOTSPOT_APP = "com.mak.wifisettings"
    private const val DUMP = "/data/local/tmp/di-connect-ui.xml"

    @Volatile private var last: String? = null
    @Volatile private var checkedThisRun = false

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun report(): String = "Hotspot band guard: ${last ?: "not run"}"

    /** Once per app start when switched on, off the main thread. */
    fun checkOnStart(context: Context) {
        if (checkedThisRun || !enabled(context)) return
        checkedThisRun = true
        val app = context.applicationContext
        Thread({ ensureFiveGhz(app) }, "hotspot-band-guard").start()
    }

    private data class Node(val text: String, val x: Int, val y: Int)

    private val NODE = Regex("""<node\b[^>]*?\btext="([^"]*)"[^>]*?\bbounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")

    /** Blocking. Returns what happened, in one line. */
    fun ensureFiveGhz(context: Context): String {
        val result = runCatching {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                val access = adb.connect(mayAsk = false)
                if (access != LocalAdb.Access.READY) return@use "Local ADB not available ($access)"
                fun sh(command: String) = adb.shell(command, 10_000).orEmpty()
                fun frequency() = Regex("""mReportedFrequency:\s*(\d+)""").find(sh("dumpsys wifi | grep -m1 mReportedFrequency"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                fun screen(): List<Node> {
                    sh("uiautomator dump $DUMP >/dev/null 2>&1")
                    return NODE.findAll(sh("cat $DUMP")).map {
                        val (t, a, b, c, d) = it.destructured
                        Node(t.replace("&amp;", "&"), (a.toInt() + c.toInt()) / 2, (b.toInt() + d.toInt()) / 2)
                    }.filter { it.text.isNotBlank() }.toList()
                }
                fun tap(node: Node) { sh("input tap ${node.x} ${node.y}") }
                fun pause(ms: Long) = Thread.sleep(ms)
                fun inFront() = sh("dumpsys activity activities | grep -m1 mResumedActivity").contains(HOTSPOT_APP)
                fun leave() { repeat(3) { if (inFront()) { sh("input keyevent 4"); pause(500) } } }
                fun isFive(text: String) = Regex("""\b5(\.0)?\s*G""", RegexOption.IGNORE_CASE).containsMatchIn(text)

                val before = frequency()
                if (before != null && before < 3000) Log.w(TAG, "hotspot is on 2.4 GHz ($before MHz); switching to 5 GHz")
                sh("am start -n $HOTSPOT_APP/.MainActivity >/dev/null 2>&1")
                pause(1800)
                var nodes = screen()
                if (nodes.none { it.text.equals("AP Band", true) }) {
                    // The app may open on Wi-Fi; the hotspot page is one tap away.
                    nodes.firstOrNull { it.text.contains("hotspot", true) }?.let { tap(it); pause(1500); nodes = screen() }
                }
                val band = nodes.indexOfFirst { it.text.equals("AP Band", true) }
                if (band < 0) { leave(); return@use "AP Band not found on the car's hotspot screen (was ${before ?: "?"} MHz)" }
                val setting = nodes.getOrNull(band + 1)?.text.orEmpty()
                if (isFive(setting)) { leave(); return@use "already $setting, running at ${before ?: "?"} MHz" }
                tap(nodes[band]); pause(1200)
                nodes = screen()
                val option = nodes.firstOrNull { isFive(it.text) }
                    ?: run { leave(); return@use "5 GHz option not found (setting was '$setting', ${before ?: "?"} MHz)" }
                tap(option); pause(900)
                screen().firstOrNull { it.text.trim().lowercase() in setOf("ok", "save", "confirm", "apply", "done") }?.let { tap(it); pause(700) }
                leave()
                pause(4000) // the hotspot restarts on a band change
                "switched from '$setting' to 5 GHz (was ${before ?: "?"} MHz, now ${frequency() ?: "?"} MHz)"
            }
        }.getOrElse { "failed: ${it.javaClass.simpleName}" }
        val line = "${SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())} $result"
        Log.i(TAG, line)
        last = line
        return line
    }
}
