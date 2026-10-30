package com.andrerinas.openheadunit.gwm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A slim climate strip along the bottom of the centre screen while Android Auto runs, for what the
 * H7's physical buttons do not cover: temperature for both seats, seat ventilation for both seats,
 * and volume for the passenger. Right-hand drive: passenger on the left, driver on the right.
 *
 * Values come from and go to the car's gwm_adapter service with the keys a GWM voice-control app
 * uses; the car only acts on changes sent as its voice assistant (measured on the H7). Android Auto
 * is told to keep its own controls above the strip ([insetFraction]), so nothing is hidden.
 */
internal object GwmClimateBar {
    private const val PREF = "climate-bar"
    /** Height of the strip as a fraction of the screen height. */
    const val HEIGHT_FRACTION = .11f

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(GwmSettings.PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREF, false)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(PREF, on).apply() }

    /** The bottom inset Android Auto keeps its controls above, or 0 when the strip is off. */
    fun insetFraction(context: Context) = if (GwmMode.active(context) && enabled(context)) HEIGHT_FRACTION else 0f

    private var view: BarView? = null

    fun show(context: Context) {
        if (view != null || !enabled(context) || !GwmMode.active(context)) return
        if (!Settings.canDrawOverlays(context)) { AppLog.w("ClimateBar: no overlay permission"); return }
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val height = (app.resources.displayMetrics.heightPixels * HEIGHT_FRACTION).roundToInt()
        val bar = BarView(app)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START }
        runCatching { wm.addView(bar, params); view = bar; bar.start() }
            .onFailure { AppLog.w("ClimateBar: could not show: ${it.message}") }
    }

    fun hide(context: Context) {
        val bar = view ?: return
        view = null
        bar.stop()
        runCatching { (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(bar) }
    }

    // ---- car values -------------------------------------------------------------------------

    private const val SERVICE = "gwm_adapter"
    private const val DESCRIPTOR = "com.gwm.android.adapter.IGwmAdapterService"
    /** The car takes climate changes only from its voice assistant (H7, Oct 2026). */
    private const val CALLER = "com.gwm.voiceassistantoversea"
    const val DRIVER_TEMP = "car.hvac.driver_temperature"
    const val PASS_TEMP = "car.hvac.pass_temperature"
    const val DRIVER_VENT = "car.comfort_setting.driver_seat_ventilation_level_nine"
    const val PASS_VENT = "car.comfort_setting.passenger_seat_ventilation_level_nine"
    const val FAN = "car.hvac.fan_speed"
    const val AC = "car.hvac.ac_enable"
    val KEYS = arrayOf(DRIVER_TEMP, PASS_TEMP, DRIVER_VENT, PASS_VENT, FAN, AC)

    @Volatile private var binder: IBinder? = null

    private fun service(): IBinder = binder ?: (Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder).also { binder = it }

    private fun call(code: Int, caller: String, keys: Array<String>, values: Array<String>?): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1); data.writeString(caller); data.writeStringArray(keys); data.writeStringArray(values); data.writeInt(1)
            check(service().transact(code, data, reply, 0)) { "transact returned false" }
            reply.readException()
            return reply
        } catch (e: Exception) {
            reply.recycle(); binder = null; throw e
        } finally {
            data.recycle()
        }
    }

    fun read(context: Context): Map<String, Float> = runCatching {
        val reply = call(1, context.packageName, KEYS, null)
        try {
            val values = reply.createStringArray() ?: emptyArray()
            KEYS.indices.mapNotNull { i -> values.getOrNull(i)?.toFloatOrNull()?.takeIf { it > -1000f }?.let { KEYS[i] to it } }.toMap()
        } finally { reply.recycle() }
    }.getOrElse { emptyMap() }

    fun write(key: String, value: String) {
        runCatching { call(2, CALLER, arrayOf(key), arrayOf(value)).recycle() }
            .onFailure { AppLog.w("ClimateBar: write $key failed: ${it.message}") }
    }

    // ---- the strip ---------------------------------------------------------------------------

    @SuppressLint("ViewConstructor")
    private class BarView(context: Context) : View(context) {
        private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val worker = HandlerThread("climate-bar").apply { start() }
        private val io = Handler(worker.looper)
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var values: Map<String, Float> = emptyMap()
        /** A just-tapped value shown at once, until the car reports it back. */
        private val pending = HashMap<String, Pair<Float, Long>>()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val accent = Color.rgb(47, 212, 240)
        private val cool = Color.rgb(90, 170, 255)
        private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
        private var pressed: RectF? = null

        private val poll = object : Runnable {
            override fun run() {
                val now = read(context)
                main.post { values = now; invalidate() }
                io.postDelayed(this, 1000)
            }
        }

        fun start() { io.post(poll) }
        fun stop() { io.removeCallbacksAndMessages(null); worker.quitSafely() }

        private fun value(key: String): Float? {
            pending[key]?.let { (v, at) ->
                if (System.currentTimeMillis() - at < 3000 && values[key] != v) return v
                pending.remove(key)
            }
            return values[key]
        }

        private fun set(key: String, v: Float, wire: String) {
            pending[key] = v to System.currentTimeMillis()
            invalidate()
            io.post { write(key, wire); val now = read(context); main.post { values = now; invalidate() } }
        }

        private fun stepTemp(key: String, delta: Float) {
            val current = value(key) ?: return
            val target = (current + delta).coerceIn(16f, 32f)
            if (target == current) return
            set(key, target, if (target % 1f == 0f) target.toInt().toString() else String.format(Locale.US, "%.1f", target))
        }

        private fun cycleVent(key: String) {
            val level = value(key)?.toInt() ?: 0
            val target = if (level >= 3 || level < 0) 0 else level + 1
            set(key, target.toFloat(), target.toString())
        }

        private fun stepVolume(delta: Int) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val u = h / 118f
            buttons.clear()
            fill.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(12, 18, 30), Color.rgb(5, 8, 14), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fill); fill.shader = null
            stroke.color = Color.argb(140, 70, 150, 230); stroke.strokeWidth = 2f * u
            canvas.drawLine(0f, 1f * u, w, 1f * u, stroke)
            val cy = h * .54f
            val pillH = 78f * u

            fun pill(l: Float, r: Float, on: Boolean = false) {
                val box = RectF(l, cy - pillH / 2f, r, cy + pillH / 2f)
                fill.color = if (on) Color.rgb(16, 62, 84) else Color.rgb(20, 30, 46); canvas.drawRoundRect(box, pillH / 2f, pillH / 2f, fill)
                stroke.color = if (on) accent else Color.rgb(60, 80, 110); stroke.strokeWidth = 2f * u
                canvas.drawRoundRect(box, pillH / 2f, pillH / 2f, stroke)
            }
            fun label(t: String, x: Float, y: Float, size: Float, color: Int = Color.rgb(235, 240, 248), bold: Boolean = false,
                      align: Paint.Align = Paint.Align.CENTER) {
                text.textSize = size; text.color = color; text.textAlign = align
                text.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                canvas.drawText(t, x, y, text)
            }
            fun button(x: Float, sign: String, action: () -> Unit) {
                val r = 30f * u
                val box = RectF(x - r, cy - r, x + r, cy + r)
                fill.color = if (pressed == box) Color.rgb(60, 80, 110) else Color.rgb(32, 44, 64); canvas.drawOval(box, fill)
                label(sign, x, cy + 15f * u, 44f * u, bold = true)
                // A wider touch target than the drawn circle, for a moving car.
                buttons += RectF(x - r * 1.5f, 0f, x + r * 1.5f, h) to action
            }
            fun caption(t: String, l: Float, r: Float) = label(t, (l + r) / 2f, cy - pillH / 2f - 6f * u, 16f * u, Color.rgb(150, 170, 195))
            fun temp(key: String, l: Float, r: Float, name: String) {
                pill(l, r); caption(name, l, r)
                button(l + 46f * u, "−") { stepTemp(key, -.5f) }
                button(r - 46f * u, "+") { stepTemp(key, .5f) }
                val t = value(key)?.let { if (it % 1f == 0f) "${it.toInt()}.0°" else String.format(Locale.US, "%.1f°", it) } ?: "—"
                label(t, (l + r) / 2f, cy + 16f * u, 44f * u, bold = true)
            }
            fun vent(key: String, l: Float, r: Float, name: String) {
                val level = value(key)?.toInt() ?: 0
                pill(l, r, level > 0); caption(name, l, r)
                val x = (l + r) / 2f
                // Seat outline with air marks, then one bar per level.
                stroke.color = if (level > 0) accent else Color.rgb(190, 200, 215); stroke.strokeWidth = 4f * u
                canvas.drawLine(x - 34f * u, cy + 18f * u, x - 10f * u, cy + 18f * u, stroke)
                canvas.drawLine(x - 34f * u, cy + 18f * u, x - 38f * u, cy - 18f * u, stroke)
                for (i in 0 until 3) {
                    fill.color = if (i < level) accent else Color.rgb(50, 66, 90)
                    val bx = x + 4f * u + i * 14f * u
                    val bh = (12f + i * 8f) * u
                    canvas.drawRoundRect(RectF(bx, cy + 18f * u - bh, bx + 9f * u, cy + 18f * u), 3f * u, 3f * u, fill)
                }
                buttons += RectF(l, 0f, r, h) to { cycleVent(key) }
            }

            // Left, passenger side: volume, temperature, seat ventilation.
            pill(16f * u, 400f * u); caption("VOLUME", 16f * u, 400f * u)
            button(64f * u, "−") { stepVolume(-1) }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            val bl = 110f * u; val br = 306f * u
            fill.color = Color.rgb(40, 56, 80); canvas.drawRoundRect(RectF(bl, cy - 6f * u, br, cy + 6f * u), 6f * u, 6f * u, fill)
            fill.color = accent; canvas.drawRoundRect(RectF(bl, cy - 6f * u, bl + (br - bl) * level, cy + 6f * u), 6f * u, 6f * u, fill)
            button(352f * u, "+") { stepVolume(1) }
            temp(PASS_TEMP, 420f * u, 760f * u, "PASSENGER")
            vent(PASS_VENT, 776f * u, 900f * u, "SEAT")

            // Centre: what the physical buttons set, shown for reference.
            val fan = value(FAN)?.toInt()
            val ac = (value(AC) ?: 0f) > .5f
            label(listOfNotNull(if (ac) "A/C" else "A/C off", fan?.let { "Fan $it" }).joinToString("   ·   "),
                w / 2f, cy + 12f * u, 30f * u, if (ac) accent else Color.rgb(150, 170, 195), bold = true)

            // Right, driver side: seat ventilation then temperature, nearest the driver.
            vent(DRIVER_VENT, w - 900f * u, w - 776f * u, "SEAT")
            temp(DRIVER_TEMP, w - 760f * u, w - 420f * u, "DRIVER")
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val hit = buttons.lastOrNull { it.first.contains(event.x, event.y) }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = hit?.first; invalidate() }
                MotionEvent.ACTION_UP -> { if (hit != null && hit.first == pressed) hit.second(); pressed = null; invalidate() }
                MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
            }
            return true
        }
    }
}
