package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hides a factory screen that a background job has to drive over local ADB, where the head unit
 * has no API for the job (the H7's hotspot band, for example). It covers the screen with a still
 * picture of what was on it, so the driver keeps seeing the screen they were on.
 *
 * The cover takes no touches and no focus. The shell's `input tap`, `input keyevent` and
 * `uiautomator dump` (which reads the focused window) still reach the factory screen underneath.
 */
internal class ScreenCurtain private constructor(private val context: Context, private val view: View?, private val picture: Bitmap?) : Closeable {
    val shown: Boolean get() = view != null
    @Volatile private var closed = false

    override fun close() {
        val v = view ?: return
        if (closed) return
        closed = true
        onMain {
            runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) }
            picture?.recycle()
        }
    }

    companion object {
        private const val TAG = "DiConnect-Curtain"

        /** Shows the cover, or returns one that is not [shown] when the car does not allow it. Blocking. */
        fun show(context: Context, adb: LocalAdb): ScreenCurtain {
            val app = context.applicationContext
            if (!Settings.canDrawOverlays(app)) adb.shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow", 8_000)
            if (!Settings.canDrawOverlays(app)) {
                Log.w(TAG, "no overlay permission; the factory screen will be visible")
                return ScreenCurtain(app, null, null)
            }
            val picture = runCatching { decode(adb.shell("screencap -p | base64", 20_000).orEmpty()) }.getOrNull()
            var view: View? = null
            onMain {
                view = runCatching {
                    val v = ImageView(app).apply {
                        setBackgroundColor(Color.BLACK)
                        scaleType = ImageView.ScaleType.FIT_XY
                        picture?.let { setImageBitmap(it) }
                    }
                    (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, layout())
                    v
                }.onFailure { Log.w(TAG, "cover not shown: $it") }.getOrNull()
            }
            if (view == null) picture?.recycle()
            return ScreenCurtain(app, view, picture)
        }

        /** A PNG sent through the shell as base64 text. */
        internal fun decode(base64: String): Bitmap? {
            val clean = base64.filterNot { it.isWhitespace() }
            if (clean.isEmpty()) return null
            val bytes = runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull() ?: return null
            // A PNG, not a shell error that happened to be valid base64.
            if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte() ||
                bytes[2] != 'N'.code.toByte() || bytes[3] != 'G'.code.toByte()) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        internal fun layout(): WindowManager.LayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Di-Connect background task"
            windowAnimations = 0
        }

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { try { block() } finally { done.countDown() } }
            done.await(3, TimeUnit.SECONDS)
        }
    }
}
