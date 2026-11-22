package com.shilapi.xcertplay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.RequiresApi

/** A transparent, non-interactive window on the GWM head-up display that hosts [GwmHeadUpView]. */
internal class GwmHudOverlay(context: Context) {
    private val appContext = context.applicationContext
    private var manager: WindowManager? = null

    var view: GwmHeadUpView? = null
        private set
    var displayId = Display.INVALID_DISPLAY
        private set

    @RequiresApi(Build.VERSION_CODES.O)
    fun show(targetDisplayId: Int, anchor: GwmHeadUpView.Anchor) {
        hide()
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(targetDisplayId)
        require(display != null && display.isValid && targetDisplayId != Display.DEFAULT_DISPLAY) {
            "HUD display $targetDisplayId is not currently available"
        }
        val displayContext = appContext.createDisplayContext(display)
        val windows = checkNotNull(displayContext.getSystemService(WindowManager::class.java)) {
            "WindowManager unavailable for display $targetDisplayId"
        }
        val hud = GwmHeadUpView(displayContext).apply {
            setAnchor(anchor)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            // Opaque like the live map window, which does reach the H7 HUD; a translucent window does
            // not come through its HDMI path. Black is invisible on a windscreen HUD.
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windows.addView(hud, layout)
        view = hud
        manager = windows
        displayId = targetDisplayId
    }

    fun hide() {
        val hud = view
        val windows = manager
        view = null
        manager = null
        displayId = Display.INVALID_DISPLAY
        if (hud != null && windows != null) runCatching { windows.removeView(hud) }
    }
}
