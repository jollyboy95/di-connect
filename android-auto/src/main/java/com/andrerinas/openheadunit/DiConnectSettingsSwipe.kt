package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * D-Play's CarPlay settings swipe, for Android Auto: the chosen number of fingers (D-Play's setting,
 * three by default, 0 = off) swiped down opens Di-Connect's settings while Android Auto stays
 * connected. Same distance and direction rule as D-Play's CarPlay screen.
 */
class DiConnectSettingsSwipe(private val activity: Activity) {
    enum class Result { PASS, STARTED, CONSUMED }

    private var sequence = false
    private var tracking = false
    private var startX = 0f
    private var startY = 0f

    fun onTouch(event: MotionEvent): Result {
        val fingers = DiConnectSettings.int(activity, SETTINGS_GESTURE_FINGERS, 3).let { if (it == 0) 0 else it.coerceIn(2, 4) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sequence = false; tracking = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (fingers > 0 && event.pointerCount == fingers && !sequence) {
                sequence = true
                tracking = true
                startX = centroid(event, horizontal = true)
                startY = centroid(event, horizontal = false)
                return Result.STARTED
            }
        }
        if (!sequence) return Result.PASS
        when {
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL -> {
                sequence = false; tracking = false
            }
            event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != fingers -> tracking = false
            tracking && event.actionMasked == MotionEvent.ACTION_MOVE -> {
                val dx = abs(centroid(event, horizontal = true) - startX)
                val dy = centroid(event, horizontal = false) - startY
                val distance = SWIPE_DISTANCE_DP * activity.resources.displayMetrics.density
                if (abs(dy) >= distance && abs(dy) >= dx * SWIPE_DIRECTION_RATIO) {
                    tracking = false
                    if (dy > 0) openSettings()
                }
            }
        }
        return Result.CONSUMED
    }

    private fun openSettings() {
        activity.startActivity(Intent().setClassName(activity, DI_CONNECT_HOME)
            .putExtra("page", "settings").addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun centroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += if (horizontal) event.getX(i) else event.getY(i)
        return total / event.pointerCount
    }

    private companion object {
        const val SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"
        const val SWIPE_DISTANCE_DP = 72
        const val SWIPE_DIRECTION_RATIO = 1.15f
        const val DI_CONNECT_HOME = "com.shilapi.xcertplay.DiPlayActivity"
    }
}
