package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One app the driver chooses (an audio tuning app, for example) opened in the background when
 * Di-Connect starts: it is launched once per start, then Di-Connect's own task comes back to the
 * front a moment later, so the app is running without staying on screen.
 */
internal object DiConnectStartApp {
    private const val PREFS = "di_connect_start_app"
    private const val KEY = "package"
    private const val TAG = "DiConnectStartApp"

    @Volatile private var launched = false
    @Volatile var last: String = "not used"
        private set

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

    /** Called once Di-Connect's screen is up. Opens the chosen app, then brings Di-Connect back. */
    fun onStart(activity: Activity) {
        if (launched) return
        val pkg = packageName(activity) ?: return
        launched = true
        val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { note("${pkg} is not installed"); return }
        val task = activity.taskId
        val app = activity.applicationContext
        val ok = runCatching {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.isSuccess
        if (!ok) { note("could not open ${label(app, pkg)}"); return }
        note("opened ${label(app, pkg)} in the background")
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(task, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            }.onFailure { note("opened ${label(app, pkg)}, but could not bring Di-Connect back: ${it.javaClass.simpleName}") }
        }, 1800)
    }

    private fun note(what: String) {
        last = "${java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(java.util.Date())} $what"
        Log.i(TAG, last)
    }
}
