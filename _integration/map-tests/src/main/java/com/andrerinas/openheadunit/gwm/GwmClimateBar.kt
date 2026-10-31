package com.andrerinas.openheadunit.gwm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
object GwmClimateBar {
    private const val PREF = "climate-bar"
    /** Height of the strip as a fraction of the screen height. */
    const val HEIGHT_FRACTION = .075f

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(GwmSettings.PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREF, false)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(PREF, on).apply() }

    /** The bottom inset Android Auto keeps its controls above, or 0 when the strip is off. */
    fun insetFraction(context: Context) = if (GwmMode.active(context) && enabled(context)) HEIGHT_FRACTION else 0f

    private var view: BarView? = null

    /**
     * Presses the car's own volume key (true = up). Set by Di-Connect at start-up: the H7 ignores
     * Android's media volume, but obeys its volume keys, as on the steering wheel.
     */
    @Volatile var volumeKey: ((Boolean) -> Unit)? = null

    /**
     * Adds the strip to the Android Auto screen's own window, so anything the car opens on top
     * (its climate screen, settings, launcher) covers it like the rest of Android Auto.
     */
    fun show(activity: android.app.Activity) {
        if (!enabled(activity) || !GwmMode.active(activity)) return
        val root = activity.window?.decorView as? android.view.ViewGroup ?: return
        view?.let { if (it.parent === root) { it.start(); return } else hide(activity) }
        val height = (activity.resources.displayMetrics.heightPixels * HEIGHT_FRACTION).roundToInt()
        val bar = BarView(activity.applicationContext)
        runCatching {
            root.addView(bar, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
            bar.elevation = 1000f
            view = bar
            bar.start()
        }.onFailure { AppLog.w("ClimateBar: could not show: ${it.message}") }
    }

    /** Stops reading the car while Android Auto is not in front. */
    fun pause() { view?.stop() }

    fun hide(activity: android.app.Activity) {
        val bar = view ?: return
        view = null
        bar.stop()
        (bar.parent as? android.view.ViewGroup)?.removeView(bar)
    }

    // ---- car values -------------------------------------------------------------------------

    private const val SERVICE = "gwm_adapter"
    private const val DESCRIPTOR = "com.gwm.android.adapter.IGwmAdapterService"
    /** The car takes climate changes only from its voice assistant (H7, Oct 2026). */
    private const val CALLER = "com.gwm.voiceassistantoversea"
    /** Quiet time after the last tap before the value is sent. */
    private const val SETTLE_MS = 450L
    /** How long the car gets to apply a write before it is checked. */
    private const val VERIFY_MS = 1800L
    /** How long a tapped value is shown before the car's own reading wins again. */
    private const val HOLD_MS = 6000L
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
        private var worker: HandlerThread? = null
        private var io: Handler = Handler(Looper.getMainLooper())
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var values: Map<String, Float> = emptyMap()
        /** A just-tapped value shown at once, until the car reports it back. */
        private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Long>>()
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

        fun start() {
            if (worker != null) return
            val thread = HandlerThread("climate-bar").apply { start() }
            worker = thread
            io = Handler(thread.looper)
            io.post(poll)
        }
        fun stop() {
            val thread = worker ?: return
            io.removeCallbacksAndMessages(null)
            thread.quitSafely()
            worker = null
        }

        private fun value(key: String): Float? {
            pending[key]?.let { (v, at) ->
                if (System.currentTimeMillis() - at < HOLD_MS && values[key] != v) return v
                pending.remove(key)
            }
            return values[key]
        }

        /**
         * Shows the new value at once and sends one absolute value once the taps stop. The H7
         * drops writes that arrive while it is still applying the last one, so a quick run of
         * taps used to land as a single step and the display then jumped back. Each key gets one
         * write per burst, and one resend if the car has not taken it within a couple of seconds.
         */
        private fun set(key: String, v: Float, wire: String) {
            pending[key] = v to System.currentTimeMillis()
            invalidate()
            io.removeCallbacksAndMessages(key)
            io.postAtTime({ send(key, v, wire, retry = true) }, key, android.os.SystemClock.uptimeMillis() + SETTLE_MS)
        }

        private fun send(key: String, v: Float, wire: String, retry: Boolean) {
            write(key, wire)
            AppLog.i("ClimateBar: wrote $key=$wire")
            io.postAtTime({
                val now = read(context)
                if (retry && now[key] != null && now[key] != v && pending[key]?.first == v) {
                    AppLog.i("ClimateBar: $key reads ${now[key]}, resending $wire")
                    send(key, v, wire, retry = false)
                } else main.post { values = now; invalidate() }
            }, key, android.os.SystemClock.uptimeMillis() + VERIFY_MS)
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

        /** The H7 fan runs 1 to 7. */
        private fun stepFan(delta: Int) {
            val current = value(FAN)?.toInt() ?: return
            val target = (current + delta).coerceIn(1, 7)
            if (target != current) set(FAN, target.toFloat(), target.toString())
        }

        private fun stepVolume(delta: Int) {
            val key = volumeKey
            if (key != null) io.post { runCatching { key(delta > 0) } }
            else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI)
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

            fun fan(l: Float, r: Float) {
                val ac = (value(AC) ?: 0f) > .5f
                pill(l, r); caption(if (ac) "FAN  ·  A/C ON" else "FAN", l, r)
                button(l + 46f * u, "−") { stepFan(-1) }
                button(r - 46f * u, "+") { stepFan(1) }
                val speed = value(FAN)?.toInt() ?: 0
                val bars = 7
                val bw = 10f * u
                val gap = 6f * u
                val total = bars * bw + (bars - 1) * gap
                var bx = (l + r) / 2f - total / 2f
                for (i in 0 until bars) {
                    val bh = (10f + i * 4.5f) * u
                    fill.color = if (i < speed) accent else Color.rgb(50, 66, 90)
                    canvas.drawRoundRect(RectF(bx, cy + 20f * u - bh, bx + bw, cy + 20f * u), 3f * u, 3f * u, fill)
                    bx += bw + gap
                }
            }

            // Evenly across the width. Right-hand drive: passenger on the left, driver on the right.
            val margin = 16f * u
            val space = 14f * u
            val weights = floatArrayOf(2.4f, 3.0f, 1.35f, 3.3f, 1.35f, 3.0f)
            val unit = (w - 2f * margin - space * (weights.size - 1)) / weights.sum()
            val edges = ArrayList<Pair<Float, Float>>()
            var x0 = margin
            for (wt in weights) { edges += x0 to (x0 + wt * unit); x0 += wt * unit + space }

            // Volume uses the car's own volume keys, so no level here; the car shows its own.
            val (vl, vr) = edges[0]
            pill(vl, vr); caption("VOLUME", vl, vr)
            button(vl + 46f * u, "−") { stepVolume(-1) }
            button(vr - 46f * u, "+") { stepVolume(1) }
            val sx = (vl + vr) / 2f
            fill.color = Color.rgb(235, 240, 248)
            val speaker = android.graphics.Path().apply {
                moveTo(sx - 16f * u, cy - 8f * u); lineTo(sx - 6f * u, cy - 8f * u); lineTo(sx + 6f * u, cy - 20f * u)
                lineTo(sx + 6f * u, cy + 20f * u); lineTo(sx - 6f * u, cy + 8f * u); lineTo(sx - 16f * u, cy + 8f * u); close()
            }
            canvas.drawPath(speaker, fill)
            stroke.color = Color.rgb(235, 240, 248); stroke.strokeWidth = 3f * u
            for (r in listOf(12f, 22f)) canvas.drawArc(RectF(sx + 4f * u - r * u, cy - r * u, sx + 4f * u + r * u, cy + r * u), -45f, 90f, false, stroke)
            temp(PASS_TEMP, edges[1].first, edges[1].second, "PASSENGER")
            vent(PASS_VENT, edges[2].first, edges[2].second, "SEAT")
            fan(edges[3].first, edges[3].second)
            vent(DRIVER_VENT, edges[4].first, edges[4].second, "SEAT")
            temp(DRIVER_TEMP, edges[5].first, edges[5].second, "DRIVER")
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
