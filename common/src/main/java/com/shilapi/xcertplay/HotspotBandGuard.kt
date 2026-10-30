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
        // Straight after the head unit boots, adbd is often not listening yet, and the car has
        // usually put the band back to Both at that same boot. Keep trying until ADB answers.
        Thread({
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            var result = ensureFiveGhz(app)
            var tries = 1
            while (result.contains("Local ADB not available") && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(if (tries < 6) 10_000L else 30_000L) }
                tries++
                result = ensureFiveGhz(app)
            }
            if (tries > 1) last = "$last (attempt $tries)"
        }, "hotspot-band-guard").start()
    }

    private data class Node(val text: String, val x: Int, val y: Int)

    /** Plain text: XML entities decoded and Android's non-breaking hyphens made ordinary. */
    private fun norm(t: String) = t.replace("&amp;", "&").replace("&#8209;", "-").replace('\u2011', '-').replace('\u2010', '-')
        .replace('\u00a0', ' ').trim()

    private val NODE = Regex("""<node\b[^>]*?\btext="([^"]*)"[^>]*?\bbounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")

    /** Blocking. Returns what happened, in one line. */
    fun ensureFiveGhz(context: Context): String {
        val result = runCatching { AdbSwitch.withAdb(context) {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                val access = adb.connect(mayAsk = false)
                if (access != LocalAdb.Access.READY) return@use if (AdbSwitch.canSwitch(context)) "Local ADB not available ($access)"
                    else "Local ADB not available ($access). Turn ADB on once; Di-Connect then switches it itself."
                AdbSwitch.grantSelf(context, adb)
                fun sh(command: String) = adb.shell(command, 10_000).orEmpty()
                fun frequency() = Regex("""mReportedFrequency:\s*(\d+)""").find(sh("dumpsys wifi | grep -m1 mReportedFrequency"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                fun pause(ms: Long) = Thread.sleep(ms)
                fun screen(): List<Node> {
                    sh("uiautomator dump $DUMP >/dev/null 2>&1")
                    return NODE.findAll(sh("cat $DUMP")).map {
                        val (t, a, b, c, d) = it.destructured
                        Node(norm(t), (a.toInt() + c.toInt()) / 2, (b.toInt() + d.toInt()) / 2)
                    }.filter { it.text.isNotBlank() }.toList()
                }
                /** Reads the screen until [found] matches or about 5 s pass, for a page still loading. */
                fun screenUntil(found: (List<Node>) -> Boolean): List<Node> {
                    var nodes = screen()
                    repeat(4) { if (found(nodes)) return nodes; pause(1200); nodes = screen() }
                    return nodes
                }
                fun hasBand(nodes: List<Node>) = nodes.any { it.text.equals("AP Band", true) }
                fun tap(node: Node) { sh("input tap ${node.x} ${node.y}") }
                /** True while a settings screen, not Di-Connect, is in front. */
                fun inFront() = sh("dumpsys activity activities | grep -m1 mResumedActivity").let {
                    !it.contains(context.packageName) && (it.contains(HOTSPOT_APP) || it.contains("settings", true))
                }
                fun leave() { repeat(6) { if (inFront()) { sh("input keyevent 4"); pause(500) } } }
                fun isFive(text: String) = Regex("""\b5(\.0)?\s*G""", RegexOption.IGNORE_CASE).containsMatchIn(text)
                fun squash(t: String) = t.lowercase().replace("-", "").replace(" ", "").replace("&", "")

                val before = frequency()
                if (before != null && before < 3000) Log.w(TAG, "hotspot is on 2.4 GHz ($before MHz); switching to 5 GHz")
                // Straight to the hotspot page where the car allows it; otherwise the car's settings app.
                val direct = listOf("com.android.settings.WIFI_TETHER_SETTINGS", "android.settings.TETHER_SETTINGS")
                var nodes: List<Node> = emptyList()
                for (action in direct) {
                    sh("am start -a $action >/dev/null 2>&1"); pause(1500)
                    nodes = screen()
                    if (hasBand(nodes) || nodes.any { squash(it.text).startsWith("wifihotspot") || squash(it.text) == "advanced" }) break
                }
                if (!hasBand(nodes) && nodes.none { squash(it.text).startsWith("wifihotspot") }) {
                    sh("am start -n $HOTSPOT_APP/.MainActivity >/dev/null 2>&1"); pause(1800)
                    nodes = screen()
                }
                // From whichever page opened, step towards AP Band: each step taps the deepest entry on
                // screen, so it works from the main menu, "Hotspot & tethering" or the hotspot page.
                val path = listOf<(String) -> Boolean>(
                    { it == "advanced" },
                    { it.startsWith("wifihotspot") },
                    { it.startsWith("hotspottethering") || it == "hotspot" || it == "tethering" || it.startsWith("hotspotandtethering") },
                    { it.startsWith("networkinternet") || it.startsWith("networkandinternet") || it == "network" || it == "wlan" },
                )
                repeat(6) {
                    if (hasBand(nodes)) return@repeat
                    val next = path.firstNotNullOfOrNull { match -> nodes.firstOrNull { match(squash(it.text)) } } ?: return@repeat
                    tap(next); pause(1300)
                    nodes = screenUntil { n -> hasBand(n) || n.any { node -> path.any { it(squash(node.text)) } && node.text != next.text } }
                }
                val band = nodes.indexOfFirst { it.text.equals("AP Band", true) }
                if (band < 0) {
                    val seen = nodes.take(12).joinToString(" | ") { it.text.take(30) }
                    leave(); return@use "AP Band not found (was ${before ?: "?"} MHz). Screen showed: ${seen.ifBlank { "nothing readable" }}"
                }
                val setting = nodes.getOrNull(band + 1)?.text.orEmpty()
                if (isFive(setting)) { leave(); return@use "already $setting, running at ${before ?: "?"} MHz" }
                tap(nodes[band]); pause(1200)
                nodes = screenUntil { n -> n.any { isFive(it.text) } }
                val option = nodes.firstOrNull { isFive(it.text) }
                    ?: run { leave(); return@use "5 GHz option not found (setting was '$setting', ${before ?: "?"} MHz)" }
                tap(option); pause(900)
                screen().firstOrNull { it.text.trim().lowercase() in setOf("ok", "save", "confirm", "apply", "done") }?.let { tap(it); pause(700) }
                leave()
                // The hotspot restarts on a band change: wait up to about 20 s for it to report again.
                var after: Int? = null
                for (i in 0 until 10) { pause(2000); after = frequency(); if (after != null && after > 0) break }
                "switched from '$setting' to 5 GHz (was ${before ?: "?"} MHz, now ${after ?: "still restarting"}${if (after != null) " MHz" else ""})"
            }
        } }.getOrElse { "failed: ${it.javaClass.simpleName}" }
        val line = "${SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())} $result"
        Log.i(TAG, line)
        last = line
        return line
    }
}
