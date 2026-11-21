package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * One app the driver chooses (the H7's audio tuning app, for example) started when Di-Connect
 * starts, without the driver seeing it. Three ways, tried in order:
 *
 * 1. **Known app, no screen at all.** Opening the H7's AudioTuning app (`com.gwm.car.audiotuning`)
 *    only sends three `AudioManager.setParameters` strings to the DSP from `MainActivity.onCreate`
 *    (`setInitParameters()` in the factory APK). Di-Connect sends the same strings itself.
 * 2. **Any other app, off screen.** Di-Connect makes a private virtual display the same size as the
 *    car's screen, and the local ADB shell starts the app there (the shell may launch on any display).
 *    The app runs, but nothing reaches the car's screen.
 * 3. **Last resort.** Without ADB, the app opens on the screen and Di-Connect comes back to the front
 *    a moment later, as before.
 */
internal object DiConnectStartApp {
    private const val PREFS = "di_connect_start_app"
    private const val KEY = "package"
    private const val TAG = "DiConnectStartApp"
    internal const val AUDIO_TUNING = "com.gwm.car.audiotuning"

    /** What opening each known app does, replayed without its screen. */
    internal val RECIPES: Map<String, List<String>> = mapOf(
        AUDIO_TUNING to listOf("aux_input=false", "effect=reset:0", "clip=1:0:5:500:100:5:50:10"),
    )

    @Volatile private var launched = false
    @Volatile var last: String = "not used"
        private set

    /** Kept for the life of the process: the app started on it stays there, off screen. */
    @Volatile private var hiddenDisplay: VirtualDisplay? = null
    @Volatile private var hiddenReader: ImageReader? = null

    fun packageName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, pkg: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pkg).apply()
    }

    /** Apps that can be opened, as (label, package), sorted by label. */
    fun choices(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) null else (info.loadLabel(pm)?.toString() ?: pkg) to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    fun label(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Called once Di-Connect's screen is up. Starts the chosen app without showing it. */
    fun onStart(activity: Activity) {
        if (launched) return
        val pkg = packageName(activity) ?: return
        launched = true
        val app = activity.applicationContext
        RECIPES[pkg]?.let { params ->
            if (applyRecipe(app, params)) {
                note("sent ${label(app, pkg)}'s start settings (${params.joinToString(", ")}) without opening it")
                return
            }
        }
        val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
        val component = launch?.component
        if (launch == null || component == null) { note("$pkg is not installed"); return }
        val task = activity.taskId
        Thread({
            val hidden = runCatching { startOffScreen(app, component.flattenToShortString()) }
                .getOrElse { "failed: ${it.javaClass.simpleName}" }
            when {
                hidden == OFF_SCREEN -> note("started ${label(app, pkg)} off screen")
                // Accepted but not in the task list: it may have closed itself. Opening it on screen
                // would only show the driver what was meant to stay hidden.
                hidden == UNSEEN -> note("asked ${label(app, pkg)} to start off screen; it is not in the task list now")
                hidden == ON_MAIN -> {
                    bringBack(app, task, pkg, 0)
                    note("${label(app, pkg)} opened on the main screen instead of off screen; Di-Connect came back")
                }
                else -> Handler(Looper.getMainLooper()).post { openThenReturn(app, launch, task, pkg, hidden) }
            }
        }, "start-app").start()
    }

    /** Sends [params] the way the app itself does. False when Di-Connect may not change audio settings. */
    private fun applyRecipe(context: Context, params: List<String>): Boolean {
        if (context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching { params.forEach { audio.setParameters(it) } }.isSuccess
    }

    private const val OFF_SCREEN = "off screen"
    private const val ON_MAIN = "on main"
    private const val UNSEEN = "unseen"

    /** Blocking. Returns [OFF_SCREEN], [ON_MAIN], [UNSEEN], or why it could not try. */
    private fun startOffScreen(context: Context, component: String): String {
        val display = hiddenDisplayId(context) ?: return "no off-screen display"
        return AdbSwitch.withAdb(context) {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                val access = adb.connect(mayAsk = false)
                if (access != LocalAdb.Access.READY) return@use "local ADB not available ($access)"
                val output = adb.shell(offScreenCommand(component, display), 10_000).orEmpty()
                if (!AdbClusterRouter.accepted(output)) return@use "launch refused: ${output.lines().firstOrNull { it.isNotBlank() }?.take(80)}"
                Thread.sleep(1200)
                when (displayOf(adb.shell("dumpsys activity activities", 10_000).orEmpty(), component.substringBefore('/'))) {
                    Display.DEFAULT_DISPLAY -> ON_MAIN
                    null -> UNSEEN
                    else -> OFF_SCREEN
                }
            }
        }
    }

    internal fun offScreenCommand(component: String, display: Int): String {
        require(display > 0)
        require(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(component))
        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION
        return "am start-activity --display $display -f 0x10010000 -n $component"
    }

    /** The display that holds [pkg]'s top task in `dumpsys activity activities`, or null. */
    internal fun displayOf(dump: String, pkg: String): Int? {
        var display: Int? = null
        val header = Regex("""^Display #(\d+)""")
        val task = Regex("""\* TaskRecord\{\S+ #\d+ [AI]=([^\s/]+)""")
        for (line in dump.lineSequence()) {
            header.find(line.trim())?.let { display = it.groupValues[1].toInt() }
            if (task.find(line)?.groupValues?.get(1) == pkg) return display
        }
        return null
    }

    /** A private display matching the car's screen, so apps that cannot resize may still start there. */
    private fun hiddenDisplayId(context: Context): Int? {
        hiddenDisplay?.display?.displayId?.let { return it }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val metrics = DisplayMetrics().also { dm.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(it) ?: return null }
        return runCatching {
            val main = Handler(Looper.getMainLooper())
            val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            // Frames are dropped as they arrive, so the hidden app never waits for a buffer.
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, main)
            val vd = dm.createVirtualDisplay("di-connect-start-app", metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi, reader.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            hiddenReader = reader
            hiddenDisplay = vd
            vd.display.displayId
        }.onFailure { Log.w(TAG, "no off-screen display: $it") }.getOrNull()
    }

    private fun openThenReturn(app: Context, launch: Intent, task: Int, pkg: String, why: String) {
        val ok = runCatching {
            app.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.isSuccess
        if (!ok) { note("could not open ${label(app, pkg)} ($why)"); return }
        note("opened ${label(app, pkg)} on screen and came back ($why)")
        bringBack(app, task, pkg, 1800)
    }

    private fun bringBack(app: Context, task: Int, pkg: String, delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(task, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            }.onFailure { note("opened ${label(app, pkg)}, but could not bring Di-Connect back: ${it.javaClass.simpleName}") }
        }, delayMs)
    }

    private fun note(what: String) {
        last = "${java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())} $what"
        Log.i(TAG, last)
    }
}
