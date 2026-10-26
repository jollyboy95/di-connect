package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log

/** The home screen's kill switch: a clean-as-possible stop, then a fresh start of Di-Connect. */
internal object DiConnectRestart {
    private const val TAG = "DiConnectRestart"
    /** Long enough for the stop requests to go out; short enough that a hang cannot block the restart. */
    private const val QUIT_AFTER_MS = 700L
    private const val REOPEN_AFTER_MS = 1_500L

    fun restart(activity: Activity) {
        val app = activity.applicationContext
        Log.w(TAG, "Restart requested from the home screen")
        runCatching { CarPlayBackgroundSession.stop() }
        runCatching { app.stopService(Intent(app, com.andrerinas.openheadunit.aap.AapService::class.java)) }
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val reopen = PendingIntent.getActivity(app, 7341, launch,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            app.getSystemService(AlarmManager::class.java)
                ?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + REOPEN_AFTER_MS, reopen)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            activity.finishAffinity()
            val me = Process.myPid()
            app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.filter { it.uid == Process.myUid() && it.pid != me }
                ?.forEach { Process.killProcess(it.pid) }
            Process.killProcess(me)
        }, QUIT_AFTER_MS)
    }
}
