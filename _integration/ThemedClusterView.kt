package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws any [ClusterTheme] on the instrument cluster, for Android Auto or CarPlay.
 *
 * Positions follow the cluster's classic slots: header pill centred on the car's gear letter,
 * speed and battery flow on the left, fuel and odometer on the bottom row, music top right. On
 * CarPlay the theme also draws the directions card, where Google's card sits in Android Auto.
 * The car's own warning lights are drawn above this layer, so nothing here can hide them.
 */
internal class ThemedClusterView(
    context: Context,
    val theme: ClusterTheme,
    private val connection: ClusterConnection,
    private val preview: Boolean = false,
) : View(context) {
    private var snapshot: GwmVehicleData.Snapshot? = null
    private var guidance: ClusterTurnGuidance? = null
    private var music = ClusterMusicState.Snapshot()
    private var musicReceivedAt = 0L
    private var stopMusic: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val roundabout = HudRoundaboutGlyph(theme.accent)
    private var glyph: Drawable? = null
    private var glyphRes = 0
    private val tick = Runnable { invalidate() }
    private val clockTick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    fun setSnapshot(next: GwmVehicleData.Snapshot?) {
        if (next == snapshot) return
        snapshot = next
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (next == guidance) return
        guidance = next
        invalidate()
    }

    /** Sample music for gallery previews, which are never attached to a window. */
    fun setPreviewMusic(next: ClusterMusicState.Snapshot) {
        music = next
        musicReceivedAt = SystemClock.elapsedRealtime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (preview) return
        post(clockTick)
        stopMusic = ClusterMusicState.observe {
            music = it
            musicReceivedAt = SystemClock.elapsedRealtime()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        stopMusic?.invoke()
        stopMusic = null
        removeCallbacks(tick)
        removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }

    // ---- frame ----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (theme.dim > 0) canvas.drawColor(Color.argb(theme.dim, 0, 0, 0))
        val showMusic = preview || AirPlayPersistence.loadGwmMusicCard(context)
        if (theme.layout == ClusterTheme.Layout.MONO_LINE) {
            drawMonoLine(canvas, w, h, showMusic)
        } else {
            drawHeader(canvas, w, h)
            snapshot?.let { data ->
                drawSpeed(canvas, w, h, data.speedKph)
                drawFlow(canvas, w, h, data.batteryCurrentA)
                drawBottomRow(canvas, w, h, data)
            } ?: drawFlow(canvas, w, h, null)
            if (showMusic) drawMusic(canvas, w, h)
        }
        if (connection == ClusterConnection.CARPLAY) guidance?.takeIf { it.icon != 0 }?.let { drawCard(canvas, w, h, it) }
    }

    // ---- type and panels ------------------------------------------------------------------

    private fun face(weight: Int): Typeface = when (theme.face) {
        ClusterTheme.Face.SERIF -> Typeface.create(Typeface.SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        ClusterTheme.Face.MONO -> Typeface.create(Typeface.MONOSPACE, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        ClusterTheme.Face.SANS -> when {
            weight <= 200 -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            weight <= 300 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            weight <= 400 -> Typeface.create("sans-serif", Typeface.NORMAL)
            weight <= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            weight <= 800 -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        }
    }

    private fun type(size: Float, color: Int, weight: Int = 400, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        text.typeface = face(weight)
        text.textSize = size
        text.color = color
        text.textAlign = align
        text.letterSpacing = spacing
        text.textSkewX = 0f
        text.textScaleX = 1f
        text.shader = null
    }

    private fun panel(canvas: Canvas, box: RectF, radius: Float) {
        val s = height / 720f
        fill.shader = null
        line.shader = null
        when (theme.panel) {
            ClusterTheme.Panel.NONE -> Unit
            ClusterTheme.Panel.PILL -> { fill.color = theme.surface; canvas.drawRoundRect(box, radius, radius, fill) }
            ClusterTheme.Panel.GOLD_LINE -> {
                fill.color = theme.surface; canvas.drawRoundRect(box, radius, radius, fill)
                line.color = theme.edge; line.strokeWidth = 1.5f * s; canvas.drawRoundRect(box, radius, radius, line)
            }
            ClusterTheme.Panel.CARD -> { fill.color = theme.surface; canvas.drawRoundRect(box, radius * 1.4f, radius * 1.4f, fill) }
            ClusterTheme.Panel.CAPSULE -> {
                fill.color = theme.surface; val r = minOf(box.height(), box.width()) / 2f; canvas.drawRoundRect(box, r, r, fill)
            }
            ClusterTheme.Panel.WIDGET -> {
                val r = radius * 1.6f
                fill.color = theme.surface; canvas.drawRoundRect(box, r, r, fill)
                line.shader = LinearGradient(0f, box.top, 0f, box.bottom,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(46, 255, 255, 255)),
                    floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
                line.strokeWidth = 1.5f * s; canvas.drawRoundRect(box, r, r, line); line.shader = null
            }
            ClusterTheme.Panel.AURORA -> {
                fill.color = theme.surface; canvas.drawRoundRect(box, radius, radius, fill)
                line.shader = LinearGradient(box.left, 0f, box.right, 0f,
                    intArrayOf(Color.rgb(62, 242, 200), Color.rgb(123, 108, 255), Color.rgb(255, 95, 200)), null, Shader.TileMode.CLAMP)
                line.strokeWidth = 2f * s; canvas.drawRoundRect(box, radius, radius, line); line.shader = null
            }
            ClusterTheme.Panel.CARBON -> {
                val k = box.height() * .18f
                path.reset(); path.moveTo(box.left + k, box.top); path.lineTo(box.right + k, box.top)
                path.lineTo(box.right - k, box.bottom); path.lineTo(box.left - k, box.bottom); path.close()
                fill.color = theme.surface; canvas.drawPath(path, fill)
                canvas.save(); canvas.clipPath(path)
                line.color = Color.argb(16, 255, 255, 255); line.strokeWidth = 2f * s
                var x = box.left - box.height()
                while (x < box.right + box.height()) { canvas.drawLine(x, box.bottom, x + box.height(), box.top, line); x += 9f * s }
                fill.color = theme.accent; canvas.drawRect(box.left - k, box.bottom - 5f * s, box.right + k, box.bottom, fill)
                canvas.restore()
            }
            ClusterTheme.Panel.BRACKET -> {
                fill.color = theme.surface; canvas.drawRect(box, fill)
                val arm = 14f * s
                line.color = theme.edge; line.strokeWidth = 2.5f * s
                for ((x, dir) in listOf(box.left to 1f, box.right to -1f)) {
                    canvas.drawLine(x, box.top, x + dir * arm, box.top, line); canvas.drawLine(x, box.top, x, box.bottom, line)
                    canvas.drawLine(x, box.bottom, x + dir * arm, box.bottom, line)
                }
            }
            ClusterTheme.Panel.DOT_GRID -> {
                fill.color = theme.surface; canvas.drawRoundRect(box, radius, radius, fill)
                canvas.save(); canvas.clipRect(box)
                fill.color = Color.argb(16, 255, 255, 255)
                var y = box.top + 6f * s
                while (y < box.bottom) { var x = box.left + 6f * s; while (x < box.right) { canvas.drawCircle(x, y, 1.3f * s, fill); x += 12f * s }; y += 12f * s }
                canvas.restore()
                line.color = theme.edge; line.strokeWidth = 1f * s; canvas.drawRoundRect(box, radius, radius, line)
            }
            ClusterTheme.Panel.BONE -> {
                bone(canvas, box.left - 2f * s, box.top - 2f * s, box.right + 2f * s, box.bottom + 2f * s, theme.edge)
                bone(canvas, box.left, box.top, box.right, box.bottom, theme.surface)
            }
        }
    }

    /** Some styles keep their panel even when a theme otherwise draws none (the directions card). */
    private fun cardPanel(canvas: Canvas, box: RectF, radius: Float) {
        if (theme.panel == ClusterTheme.Panel.NONE || theme.panel == ClusterTheme.Panel.BONE) {
            fill.shader = null; fill.color = Color.argb(205, 0, 0, 0); canvas.drawRoundRect(box, radius, radius, fill)
        } else panel(canvas, box, radius)
    }

    private fun bone(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val h = y1 - y0
        val r = h * .3f
        path.reset()
        path.addRoundRect(x0 + r * .9f, y0 + h * .18f, x1 - r * .9f, y0 + h * .82f, h * .18f, h * .18f, Path.Direction.CW)
        for ((x, y) in listOf(x0 + r to y0 + r, x0 + r to y1 - r, x1 - r to y0 + r, x1 - r to y1 - r)) path.addCircle(x, y, r, Path.Direction.CW)
        fill.shader = null; fill.color = color; canvas.drawPath(path, fill)
    }

    // ---- header ---------------------------------------------------------------------------

    private fun drawHeader(canvas: Canvas, w: Float, h: Float) {
        val temp = snapshot?.outsideTempC?.let { "${it.roundToInt()}°C" } ?: ""
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val gear = GEAR_X * w
        val gap = .052f * w
        type(.031f * h, theme.text, if (theme.face == ClusterTheme.Face.SANS) 500 else 600)
        val half = maxOf(text.measureText(temp), text.measureText(time)) + gap + .018f * w
        rect.set(gear - half, .012f * h, gear + half, .104f * h)
        panel(canvas, rect, rect.height() / 2f)
        val baseline = .058f * h - (text.descent() + text.ascent()) / 2f
        text.textAlign = Paint.Align.RIGHT; canvas.drawText(temp, gear - gap, baseline, text)
        text.textAlign = Paint.Align.LEFT; canvas.drawText(time, gear + gap, baseline, text)
    }

    // ---- speed ----------------------------------------------------------------------------

    private val speedCard get() = theme.speed == ClusterTheme.Speed.HEADED || theme.speed == ClusterTheme.Speed.DOTS

    private fun drawSpeed(canvas: Canvas, w: Float, h: Float, speedKph: Float?) {
        val s = h / 720f
        val cx = SPEED_X * w
        val value = speedKph?.roundToInt()?.toString() ?: "—"
        val unit = if (theme.face == ClusterTheme.Face.MONO || theme.speed == ClusterTheme.Speed.ITALIC) "KM/H" else "km/h"
        when (theme.speed) {
            ClusterTheme.Speed.PLAIN -> {
                type(.22f * h, theme.text, if (theme.dim > 0) 200 else 500, Paint.Align.CENTER)
                canvas.drawText(value, cx, .53f * h, text)
                type(.032f * h, theme.secondary, 400, Paint.Align.CENTER); canvas.drawText(unit, cx, .59f * h, text)
            }
            ClusterTheme.Speed.DIAL -> {
                val cy = .385f * h
                val r = .205f * h
                fill.shader = null; fill.color = theme.surface; canvas.drawCircle(cx, cy, r, fill)
                line.shader = null; line.color = theme.edge; line.strokeWidth = 1.5f * s; canvas.drawCircle(cx, cy, r, line)
                val a0 = Math.PI * .75
                val a1 = Math.PI * 2.25
                val shown = speedKph ?: 0f
                for (v in 0..180 step 5) {
                    val a = a0 + (a1 - a0) * v / 180.0
                    val major = v % 20 == 0
                    line.color = if (v <= shown) theme.accent else Color.argb(90, 244, 236, 220)
                    line.strokeWidth = (if (major) 2.4f else 1.2f) * s
                    val inner = r - (if (major) 32f else 24f) * s
                    canvas.drawLine(cx + cos(a).toFloat() * (r - 16f * s), cy + sin(a).toFloat() * (r - 16f * s),
                        cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner, line)
                    if (major && v % 40 == 0) {
                        type(.018f * h, theme.secondary, 500, Paint.Align.CENTER)
                        canvas.drawText(v.toString(), cx + cos(a).toFloat() * (r - 48f * s), cy + sin(a).toFloat() * (r - 48f * s) + .006f * h, text)
                    }
                }
                val an = a0 + (a1 - a0) * shown.coerceIn(0f, 180f) / 180.0
                line.color = theme.accent; line.strokeWidth = 3f * s
                canvas.drawLine(cx + cos(an).toFloat() * (r - 58f * s), cy + sin(an).toFloat() * (r - 58f * s),
                    cx + cos(an).toFloat() * (r - 14f * s), cy + sin(an).toFloat() * (r - 14f * s), line)
                type(.16f * h, theme.text, 300, Paint.Align.CENTER); canvas.drawText(value, cx, cy + .055f * h, text)
                type(.024f * h, theme.accent, 500, Paint.Align.CENTER, .2f); canvas.drawText("KM/H", cx, cy + .11f * h, text)
            }
            ClusterTheme.Speed.ITALIC -> {
                rect.set(.07f * w, .255f * h, .255f * w, .715f * h)
                panel(canvas, rect, 0f)
                type(.25f * h, theme.text, 900, Paint.Align.CENTER); text.textSkewX = -.2f
                canvas.drawText(value, cx, .53f * h, text)
                type(.026f * h, theme.accent, 800, Paint.Align.CENTER); text.textSkewX = -.2f
                canvas.drawText(unit, cx + .06f * w, .585f * h, text)
            }
            ClusterTheme.Speed.TAPE -> drawTape(canvas, w, h, speedKph, value)
            ClusterTheme.Speed.GLOW -> {
                darkGlow(canvas, cx, .44f * h, .16f * h, 1.25f, .8f, Color.rgb(10, 14, 40))
                type(.24f * h, theme.text, 300, Paint.Align.CENTER)
                text.shader = LinearGradient(0f, .3f * h, 0f, .55f * h, Color.WHITE, Color.rgb(185, 198, 255), Shader.TileMode.CLAMP)
                canvas.drawText(value, cx, .53f * h, text)
                type(.03f * h, theme.secondary, 400, Paint.Align.CENTER); canvas.drawText(unit, cx, .59f * h, text)
            }
            ClusterTheme.Speed.HEADED -> {
                rect.set(.05f * w, .2f * h, .25f * w, .72f * h)
                panel(canvas, rect, .03f * h)
                type(.022f * h, theme.secondary, 600); canvas.drawText(context.getString(R.string.di_connect_theme_speed), rect.left + 26f * s, rect.top + 38f * s, text)
                type(.19f * h, theme.text, 800); canvas.drawText(value, rect.left + 24f * s, .5f * h, text)
                val dw = text.measureText(value)
                type(.03f * h, theme.secondary, 600); canvas.drawText(unit, rect.left + 32f * s + dw, .5f * h, text)
            }
            ClusterTheme.Speed.CONDENSED -> {
                darkGlow(canvas, cx, .44f * h, .15f * h, 1.3f, .75f, Color.BLACK)
                type(.26f * h, theme.text, 900, Paint.Align.CENTER); text.textScaleX = .82f
                canvas.drawText(value, cx - .012f * w, .53f * h, text)
                rect.set(cx + .055f * w, .5f * h, cx + .105f * w, .545f * h)
                fill.shader = null; fill.color = theme.surface; canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                type(.022f * h, theme.accent, 800, Paint.Align.CENTER)
                canvas.drawText("KM/H", rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2f, text)
            }
            ClusterTheme.Speed.DOTS -> {
                rect.set(.05f * w, .2f * h, .25f * w, .72f * h)
                panel(canvas, rect, .03f * h)
                line.color = Color.WHITE; line.strokeWidth = 3f * s
                canvas.drawLine(rect.left + 22f * s, rect.top + 60f * s, rect.left + 22f * s, rect.top + 22f * s, line)
                canvas.drawLine(rect.left + 22f * s, rect.top + 22f * s, rect.left + 60f * s, rect.top + 22f * s, line)
                fill.color = theme.charge; canvas.drawCircle(rect.right - 30f * s, rect.top + 30f * s, 6f * s, fill)
                val p = .024f * h
                dotDigits(canvas, value, cx - (value.length * 6 - 1) * p / 2f, .27f * h, p, theme.text)
                type(.024f * h, theme.secondary, 400, Paint.Align.CENTER); canvas.drawText("KM/H", cx, .5f * h, text)
            }
            ClusterTheme.Speed.DOG_TAG -> {
                val ty = .43f * h
                val tr = .15f * h
                fill.shader = RadialGradient(cx - tr * .3f, ty - tr * .4f, tr, theme.accent, Color.rgb(123, 63, 208), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, ty, tr, fill); fill.shader = null
                line.color = Color.rgb(216, 216, 222); line.strokeWidth = 6f * s; canvas.drawCircle(cx, ty, tr - 3f * s, line)
                val pr = .075f * h
                val py = ty - tr - .02f * h
                val photo = if (preview) null else ClusterThemeStore.coPilot(context)
                if (photo != null) {
                    canvas.save(); path.reset(); path.addCircle(cx, py, pr, Path.Direction.CW); canvas.clipPath(path)
                    rect.set(cx - pr, py - pr, cx + pr, py + pr); canvas.drawBitmap(photo, null, rect, art); canvas.restore()
                    line.color = theme.edge; line.strokeWidth = 4f * s; canvas.drawCircle(cx, py, pr, line)
                } else pugFace(canvas, cx, py, .16f * h)
                type(.14f * h, theme.text, 900, Paint.Align.CENTER); canvas.drawText(value, cx, ty + .05f * h, text)
                type(.022f * h, theme.text, 700, Paint.Align.CENTER); canvas.drawText(unit, cx, ty + .095f * h, text)
            }
        }
    }

    private fun drawTape(canvas: Canvas, w: Float, h: Float, speedKph: Float?, value: String) {
        val s = h / 720f
        val tx = .085f * w
        val top = .2f * h
        val bottom = .58f * h
        val cy = .43f * h
        val perKm = 7.5f * s
        val shown = speedKph ?: 0f
        fill.shader = null; fill.color = theme.surface; canvas.drawRect(tx - 6f * s, top, tx + .05f * w, bottom, fill)
        canvas.save(); canvas.clipRect(tx - 6f * s, top, tx + .06f * w, bottom)
        line.shader = null; line.color = theme.accent; line.strokeWidth = 2f * s
        type(.022f * h, theme.accent, 400)
        for (v in 0..200 step 2) {
            val y = cy - (v - shown) * perKm
            if (y < top - 10f * s || y > bottom + 10f * s) continue
            val major = v % 10 == 0
            canvas.drawLine(tx + .05f * w, y, tx + .05f * w - (if (major) 22f else 11f) * s, y, line)
            if (major) canvas.drawText(v.toString(), tx, y - (text.descent() + text.ascent()) / 2f, text)
        }
        canvas.restore()
        canvas.drawLine(tx - 6f * s, top, tx - 6f * s, bottom, line)
        val bx = .14f * w
        val bw = .115f * w
        val bh = .15f * h
        path.reset(); path.moveTo(bx - 18f * s, cy); path.lineTo(bx, cy - bh / 2f); path.lineTo(bx + bw, cy - bh / 2f)
        path.lineTo(bx + bw, cy + bh / 2f); path.lineTo(bx, cy + bh / 2f); path.close()
        fill.color = Color.argb(217, 2, 14, 8); canvas.drawPath(path, fill); canvas.drawPath(path, line)
        type(.13f * h, theme.text, 700, Paint.Align.CENTER)
        canvas.drawText(value, bx + bw / 2f, cy - (text.descent() + text.ascent()) / 2f, text)
        type(.022f * h, theme.secondary, 400, Paint.Align.CENTER); canvas.drawText("KM/H", bx + bw / 2f, cy + bh / 2f + .03f * h, text)
    }

    private fun darkGlow(canvas: Canvas, cx: Float, cy: Float, r: Float, sx: Float, sy: Float, color: Int) {
        canvas.save(); canvas.translate(cx, cy); canvas.scale(sx, sy)
        fill.shader = RadialGradient(0f, 0f, r,
            intArrayOf(Color.argb(216, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, r, fill); fill.shader = null
        canvas.restore()
    }

    private fun dotDigits(canvas: Canvas, value: String, x0: Float, y0: Float, p: Float, color: Int) {
        fill.shader = null; fill.color = color
        var x = x0
        for (ch in value) {
            DIGITS[ch]?.forEachIndexed { row, bits ->
                bits.forEachIndexed { col, bit -> if (bit == '1') canvas.drawCircle(x + col * p + p / 2f, y0 + row * p + p / 2f, p * .4f, fill) }
            }
            x += 6 * p
        }
    }

    private fun pugFace(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val fawn = Color.rgb(233, 203, 160)
        val black = Color.rgb(28, 25, 24)
        fill.shader = null
        fill.color = black
        for (k in listOf(-1f, 1f)) {
            path.reset(); path.moveTo(cx + k * s * .28f, cy - s * .36f)
            path.quadTo(cx + k * s * .62f, cy - s * .46f, cx + k * s * .56f, cy - s * .08f)
            path.quadTo(cx + k * s * .44f, cy - s * .2f, cx + k * s * .28f, cy - s * .36f)
            canvas.drawPath(path, fill)
        }
        fill.color = fawn; rect.set(cx - s * .46f, cy - s * .4f, cx + s * .46f, cy + s * .4f); canvas.drawOval(rect, fill)
        fill.color = black; rect.set(cx - s * .27f, cy - s * .08f, cx + s * .27f, cy + s * .34f); canvas.drawOval(rect, fill)
        for (k in listOf(-1f, 1f)) {
            fill.color = black; canvas.drawCircle(cx + k * s * .2f, cy - s * .06f, s * .12f, fill)
            fill.color = Color.rgb(74, 42, 24); canvas.drawCircle(cx + k * s * .2f, cy - s * .06f, s * .085f, fill)
            fill.color = Color.WHITE; canvas.drawCircle(cx + k * s * .2f - s * .03f, cy - s * .09f, s * .03f, fill)
        }
        fill.color = Color.BLACK; rect.set(cx - s * .09f, cy - s * .01f, cx + s * .09f, cy + s * .11f); canvas.drawOval(rect, fill)
        fill.color = Color.rgb(245, 143, 176); rect.set(cx - s * .05f, cy + s * .17f, cx + s * .11f, cy + s * .37f)
        canvas.drawArc(rect, 0f, 180f, true, fill)
    }

    // ---- battery flow ---------------------------------------------------------------------

    private fun drawFlow(canvas: Canvas, w: Float, h: Float, currentA: Float?) {
        val s = h / 720f
        val l = BAR_LEFT * w
        val r = BAR_RIGHT * w
        val mid = (l + r) / 2f
        val y = .634f * h
        val kw = currentA?.let { GwmAndroidAutoClusterView.batteryKw(it) }
        val f = ((kw ?: 0f) / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
        val fx = mid + f * (r - l) / 2f
        val label = kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—"
        val kwY = if (speedCard) .6f * h else .689f * h
        var left = "CHARGE"
        var right = "POWER"
        fill.shader = null; line.shader = null
        when (theme.flow) {
            ClusterTheme.Flow.BAR -> {
                if (!speedCard) { rect.set(l - .014f * h, y - .021f * h, r + .014f * h, y + .021f * h); panel(canvas, rect, .021f * h) }
                rect.set(l, y - 5f * s, r, y + 5f * s); fill.color = Color.argb(60, 255, 255, 255); canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
                if (kw != null) { rect.set(minOf(mid, fx), y - 5f * s, maxOf(mid, fx), y + 5f * s); fill.color = if (f >= 0) theme.accent else theme.charge; canvas.drawRoundRect(rect, 5f * s, 5f * s, fill) }
                fill.color = theme.text; canvas.drawRect(mid - 1f * s, y - 9f * s, mid + 1f * s, y + 9f * s, fill)
            }
            ClusterTheme.Flow.HAIRLINE -> {
                line.color = theme.edge; line.strokeWidth = 1.2f * s; canvas.drawLine(l, y, r, y, line)
                for (i in -6..6) { val x = mid + (r - l) / 2f * i / 6f; val k = (if (i % 3 == 0) 7f else 3f) * s; canvas.drawLine(x, y - k, x, y + k, line) }
                if (kw != null) {
                    line.color = if (f >= 0) theme.accent else theme.charge; line.strokeWidth = 4f * s; canvas.drawLine(mid, y, fx, y, line)
                    fill.color = theme.text; canvas.drawCircle(fx, y, 6f * s, fill)
                }
            }
            ClusterTheme.Flow.LED -> {
                val n = 20
                val seg = (r - l) / n
                val lit = (f * n / 2f).roundToInt()
                for (i in 0 until n) {
                    val off = i - n / 2
                    fill.color = when {
                        kw != null && lit > 0 && off in 0 until lit -> when { off < n / 4 -> Color.rgb(56, 240, 122); off < n * 2 / 5 -> Color.rgb(255, 210, 58); else -> theme.accent }
                        kw != null && lit < 0 && off in lit until 0 -> theme.charge
                        else -> Color.argb(26, 255, 255, 255)
                    }
                    val x = l + i * seg
                    path.reset(); path.moveTo(x + 2f * s + 2f * s, y - 9f * s); path.lineTo(x + seg - 2f * s + 2f * s, y - 9f * s)
                    path.lineTo(x + seg - 2f * s - 2f * s, y + 9f * s); path.lineTo(x + 2f * s - 2f * s, y + 9f * s); path.close()
                    canvas.drawPath(path, fill)
                }
                fill.color = theme.text; canvas.drawRect(mid - 1f * s, y - 15f * s, mid + 1f * s, y + 15f * s, fill)
                left = "REGEN"; right = "BOOST"
            }
            ClusterTheme.Flow.LADDER -> {
                fill.color = theme.surface; canvas.drawRect(l - 10f * s, y - 30f * s, r + 10f * s, y + .07f * h, fill)
                line.color = theme.accent; line.strokeWidth = 2f * s; canvas.drawLine(l, y, r, y, line)
                for (i in -6..6) { val x = mid + (r - l) / 2f * i / 6f; val k = (if (i == 0) 14f else if (i % 3 == 0) 9f else 5f) * s; canvas.drawLine(x, y - k, x, y, line) }
                if (kw != null) {
                    line.strokeWidth = 5f * s; canvas.drawLine(mid, y, fx, y, line)
                    path.reset(); path.moveTo(fx, y + 3f * s); path.lineTo(fx - 8f * s, y + 16f * s); path.lineTo(fx + 8f * s, y + 16f * s); path.close()
                    fill.color = theme.accent; canvas.drawPath(path, fill)
                }
                left = "CHG"; right = "PWR"
            }
            ClusterTheme.Flow.ORB -> {
                rect.set(l - .014f * h, y - .021f * h, r + .014f * h, y + .021f * h); panel(canvas, rect, .021f * h)
                rect.set(l, y - 5f * s, r, y + 5f * s); fill.color = Color.argb(30, 255, 255, 255); canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
                if (kw != null) {
                    val tip = if (f >= 0) theme.accent else theme.charge
                    fill.shader = LinearGradient(mid, 0f, fx, 0f, Color.argb(100, 123, 108, 255), tip, Shader.TileMode.CLAMP)
                    rect.set(minOf(mid, fx), y - 5f * s, maxOf(mid, fx), y + 5f * s); canvas.drawRoundRect(rect, 5f * s, 5f * s, fill); fill.shader = null
                    fill.color = Color.argb(90, Color.red(tip), Color.green(tip), Color.blue(tip)); canvas.drawCircle(fx, y, 13f * s, fill)
                    fill.color = Color.WHITE; canvas.drawCircle(fx, y, 7f * s, fill)
                }
            }
            ClusterTheme.Flow.DOTS -> {
                val n = 25
                val lit = (f * 12).roundToInt()
                for (i in 0 until n) {
                    val off = i - 12
                    fill.color = when {
                        off == 0 -> theme.charge
                        kw != null && lit > 0 && off in 1..lit -> theme.text
                        kw != null && lit < 0 && off in lit..-1 -> theme.charge
                        else -> Color.argb(46, 255, 255, 255)
                    }
                    canvas.drawCircle(l + i * (r - l) / (n - 1), y, (if (off == 0) 5f else 4f) * s, fill)
                }
                left = "CHG"; right = "PWR"
            }
            ClusterTheme.Flow.PAWS -> {
                rect.set(l - 30f * s, y - 30f * s, r + 30f * s, y + .09f * h)
                fill.color = theme.surface; canvas.drawRoundRect(rect, 24f * s, 24f * s, fill)
                line.color = theme.edge; line.strokeWidth = 2f * s; canvas.drawRoundRect(rect, 24f * s, 24f * s, line)
                val lit = (f * 4).roundToInt()
                for (i in 0 until 9) {
                    val off = i - 4
                    val x = l + i * (r - l) / 8f
                    if (off == 0) { bone(canvas, x - 16f * s, y - 9f * s, x + 16f * s, y + 9f * s, Color.rgb(255, 246, 232)); continue }
                    val color = when {
                        kw != null && lit > 0 && off in 1..lit -> theme.accent
                        kw != null && lit < 0 && off in lit..-1 -> theme.charge
                        else -> Color.argb(70, 255, 246, 232)
                    }
                    paw(canvas, x, y, 30f * s, color)
                }
            }
        }
        val labelY = if (theme.flow == ClusterTheme.Flow.LADDER) y - 12f * s
            else if (theme.flow == ClusterTheme.Flow.PAWS) y + .055f * h else .684f * h
        type(.017f * h, theme.secondary, 600, Paint.Align.LEFT, .12f)
        if (theme.flow == ClusterTheme.Flow.PAWS) text.color = theme.charge
        canvas.drawText(left, if (theme.flow == ClusterTheme.Flow.PAWS) l - 12f * s else l, labelY, text)
        text.textAlign = Paint.Align.RIGHT
        if (theme.flow == ClusterTheme.Flow.PAWS) text.color = theme.accent
        canvas.drawText(right, if (theme.flow == ClusterTheme.Flow.PAWS) r + 12f * s else r, labelY, text)
        type(.024f * h, when { kw == null -> theme.secondary; f >= 0 -> theme.accent; else -> theme.charge }, 700, Paint.Align.CENTER)
        if (theme.flow == ClusterTheme.Flow.LED) text.textSkewX = -.2f
        if (theme.flow == ClusterTheme.Flow.PAWS) text.color = theme.text
        canvas.drawText(label, mid, if (theme.flow == ClusterTheme.Flow.LADDER) .693f * h else if (theme.flow == ClusterTheme.Flow.PAWS) y + .06f * h else kwY, text)
    }

    private fun paw(canvas: Canvas, x: Float, y: Float, s: Float, color: Int) {
        fill.shader = null; fill.color = color
        rect.set(x - s * .32f, y + s * .18f - s * .26f, x + s * .32f, y + s * .18f + s * .26f); canvas.drawOval(rect, fill)
        for ((dx, dy) in listOf(-.32f to -.12f, -.12f to -.34f, .12f to -.34f, .32f to -.12f)) {
            rect.set(x + dx * s - s * .11f, y + dy * s - s * .14f, x + dx * s + s * .11f, y + dy * s + s * .14f); canvas.drawOval(rect, fill)
        }
    }

    // ---- bottom row -----------------------------------------------------------------------

    private fun drawBottomRow(canvas: Canvas, w: Float, h: Float, data: GwmVehicleData.Snapshot) {
        val s = h / 720f
        val top = BOTTOM_TOP * h
        val bottom = BOTTOM_BOTTOM * h
        val mid = (top + bottom) / 2f
        rect.set(.045f * w, top, .255f * w, bottom)
        val fuelRight = rect.right
        panel(canvas, rect, rect.height() / 2f)
        val icon = .034f * h
        val pumpX = rect.left + .047f * h
        drawPump(canvas, pumpX, mid - icon / 2f, icon)
        val barLeft = pumpX + icon * 1.25f
        val barW = .05f * w
        val fuel = data.fuelPercent
        rect.set(barLeft, mid - 4f * s, barLeft + barW, mid + 4f * s); fill.shader = null; fill.color = Color.argb(50, 255, 255, 255)
        canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        if (fuel != null) {
            rect.right = barLeft + barW * (fuel / 100f).coerceIn(.03f, 1f)
            fill.color = if (fuel < 15f) Color.rgb(255, 159, 10) else theme.charge; canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        }
        type(.034f * h, theme.text, 500)
        val baseline = mid - (text.descent() + text.ascent()) / 2f
        canvas.drawText(fuel?.let { "${it.roundToInt()}%" } ?: "—", barLeft + barW + .01f * w, baseline, text)
        data.rangeKm?.let {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText("${it.roundToInt()} km", fuelRight - (if (theme.panel == ClusterTheme.Panel.BONE) .045f else .024f) * h, baseline, text)
        }
        data.odometerKm?.let { odo ->
            val value = NumberFormat.getIntegerInstance().format(odo.roundToInt()) + " km"
            type(.034f * h, theme.text, 500)
            val valueWidth = text.measureText(value)
            type(.02f * h, theme.secondary, 600, Paint.Align.LEFT, .15f)
            val labelWidth = text.measureText("ODO")
            val inset = if (theme.panel == ClusterTheme.Panel.BONE) .04f * h else .024f * h
            rect.set(ODO_LEFT * w, top, ODO_LEFT * w + inset * 2f + labelWidth + .012f * w + valueWidth, bottom)
            panel(canvas, rect, rect.height() / 2f)
            type(.02f * h, theme.secondary, 600, Paint.Align.LEFT, .15f)
            canvas.drawText("ODO", rect.left + inset, baseline, text)
            type(.034f * h, theme.text, 500)
            canvas.drawText(value, rect.left + inset + labelWidth + .012f * w, baseline, text)
        }
    }

    private fun drawPump(canvas: Canvas, left: Float, top: Float, size: Float) {
        line.shader = null; line.color = theme.text; line.strokeWidth = size * .09f
        rect.set(left, top, left + size * .62f, top + size); canvas.drawRoundRect(rect, size * .1f, size * .1f, line)
        fill.shader = null; fill.color = theme.text
        canvas.drawRect(left + size * .15f, top + size * .17f, left + size * .47f, top + size * .38f, fill)
        canvas.drawLine(left + size * .62f, top + size * .3f, left + size * .86f, top + size * .46f, line)
        canvas.drawLine(left + size * .86f, top + size * .46f, left + size * .86f, top + size * .86f, line)
    }

    // ---- music ----------------------------------------------------------------------------

    private fun progress(): Float? {
        val info = music.info
        val duration = info.durationMillis?.takeIf { it > 0 } ?: return null
        var elapsed = info.elapsedMillis ?: return null
        if (info.playing && !preview) {
            elapsed += SystemClock.elapsedRealtime() - musicReceivedAt
            removeCallbacks(tick); postDelayed(tick, 1_000L)
        }
        return (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun drawMusic(canvas: Canvas, w: Float, h: Float) {
        val s = h / 720f
        val title = music.info.title?.takeIf { it.isNotBlank() } ?: return
        rect.set(MUSIC_LEFT * w, MUSIC_TOP * h, MUSIC_RIGHT * w, MUSIC_BOTTOM * h)
        val box = RectF(rect)
        if (theme.panel == ClusterTheme.Panel.BONE) {
            fill.shader = null; fill.color = theme.surface; canvas.drawRoundRect(box, 26f * s, 26f * s, fill)
            line.color = theme.edge; line.strokeWidth = 2f * s; canvas.drawRoundRect(box, 26f * s, 26f * s, line)
        } else cardPanel(canvas, box, 22f * s)
        val side = .11f * h
        val ax = box.centerX() - side / 2f
        val ay = box.top + 16f * s
        rect.set(ax, ay, ax + side, ay + side)
        val cover = music.artwork?.takeUnless(Bitmap::isRecycled)
        canvas.save(); path.reset(); path.addRoundRect(rect, 12f * s, 12f * s, Path.Direction.CW); canvas.clipPath(path)
        if (cover != null) canvas.drawBitmap(cover, null, rect, art)
        else { fill.shader = null; fill.color = Color.argb(60, 255, 255, 255); canvas.drawRect(rect, fill) }
        canvas.restore()
        progress()?.let { done ->
            rect.set(ax, ay + side + 8f * s, ax + side, ay + side + 12f * s); fill.color = Color.argb(50, 255, 255, 255); canvas.drawRoundRect(rect, 2f * s, 2f * s, fill)
            rect.right = ax + side * done; fill.color = theme.accent; canvas.drawRoundRect(rect, 2f * s, 2f * s, fill)
        }
        type(.03f * h, theme.text, 700, Paint.Align.CENTER)
        canvas.drawText(fit(title, box.width() - 24f * s), box.centerX(), ay + side + 42f * s, text)
        music.info.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            type(.018f * h, theme.secondary, 500, Paint.Align.CENTER)
            canvas.drawText(fit(artist, box.width() - 24f * s), box.centerX(), ay + side + 64f * s, text)
        }
    }

    // ---- one-line layout ------------------------------------------------------------------

    private fun drawMonoLine(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        fill.shader = LinearGradient(0f, .78f * h, 0f, h, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(200, 0, 0, 0), Color.argb(230, 0, 0, 0)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, .78f * h, w, h, fill); fill.shader = null
        val y = .86f * h
        val data = snapshot
        type(.07f * h, theme.text, 700)
        val speed = data?.speedKph?.roundToInt()?.toString() ?: "—"
        canvas.drawText(speed, .2f * w, y, text)
        val sw = text.measureText(speed)
        type(.022f * h, theme.secondary, 500); canvas.drawText("km/h", .2f * w + sw + 8f * s, y, text)
        val kw = data?.batteryCurrentA?.let { GwmAndroidAutoClusterView.batteryKw(it) }
        type(.026f * h, theme.accent, 500); canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", .29f * w, y, text)
        type(.026f * h, theme.secondary, 500)
        val fuel = listOfNotNull(data?.fuelPercent?.let { "${it.roundToInt()}%" }, data?.rangeKm?.let { "${it.roundToInt()} km" }).joinToString(" · ")
        canvas.drawText(fuel, .36f * w, y, text)
        if (showMusic) music.info.title?.takeIf { it.isNotBlank() }?.let { title ->
            type(.026f * h, theme.text, 500)
            canvas.drawText(fit(listOfNotNull(title, music.info.artist).joinToString(" — "), .2f * w), .58f * w, y, text)
        }
        type(.026f * h, theme.secondary, 500)
        canvas.drawText(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), .83f * w, y, text)
        data?.outsideTempC?.let { canvas.drawText("${it.roundToInt()}°C", .9f * w, y, text) }
        val l = .2f * w
        val r = .74f * w
        val mid = (l + r) / 2f
        fill.color = Color.argb(46, 255, 255, 255); canvas.drawRect(l, y + 18f * s, r, y + 20f * s, fill)
        if (kw != null) {
            val fx = mid + (r - l) / 2f * (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            fill.color = if (kw >= 0) theme.accent else theme.charge; canvas.drawRect(minOf(mid, fx), y + 17f * s, maxOf(mid, fx), y + 21f * s, fill)
        }
        fill.color = theme.text; canvas.drawRect(mid - 1f * s, y + 12f * s, mid + 1f * s, y + 26f * s, fill)
        data?.odometerKm?.let {
            type(.02f * h, theme.secondary, 500, Paint.Align.RIGHT)
            canvas.drawText("ODO " + NumberFormat.getIntegerInstance().format(it.roundToInt()) + " km", .97f * w, .97f * h, text)
        }
    }

    // ---- CarPlay directions card ----------------------------------------------------------

    private fun drawCard(canvas: Canvas, w: Float, h: Float, next: ClusterTurnGuidance) {
        val s = h / 720f
        val box = RectF(CARD_LEFT * w, CARD_TOP * h, CARD_RIGHT * w, CARD_BOTTOM * h)
        cardPanel(canvas, box, .03f * h)
        val side = .1f * h
        val gx = box.left + .03f * h
        val gy = box.top + .03f * h
        val icon = if (next.icon in HudRoundaboutGlyph.ICONS) {
            roundabout.exitNumber = next.roundaboutExit; glyphRes = 0; roundabout
        } else {
            val res = clusterTurnGlyphRes(next.icon)
            if (res != glyphRes) { glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(theme.accent) }; glyphRes = res }
            glyph
        }
        icon?.setBounds(gx.toInt(), gy.toInt(), (gx + side).toInt(), (gy + side).toInt())
        icon?.draw(canvas)
        val textLeft = gx + side + .025f * h
        val textWidth = box.right - .03f * h - textLeft
        type(.055f * h, theme.text, 700)
        canvas.drawText(fit(distanceLabel(next.distanceMeters), textWidth), textLeft, gy + .06f * h, text)
        val road = if (next.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit, next.roundaboutExit) else next.road
        if (road.isNotEmpty()) {
            type(.032f * h, theme.text, 400)
            canvas.drawText(fit(road, textWidth), textLeft, gy + .11f * h, text)
        }
        fill.shader = null; fill.color = Color.argb(46, 255, 255, 255)
        canvas.drawRect(box.left + .03f * h, box.top + .19f * h, box.right - .03f * h, box.top + .19f * h + 1.2f * s, fill)
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let { epoch ->
            val info = context.getString(R.string.turn_card_info_arrival, SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epoch * 1000L)))
            type(.028f * h, theme.secondary, 500)
            canvas.drawText(fit(info, box.width() - .06f * h), box.left + .03f * h, box.top + .25f * h, text)
        }
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, (meters / 10) * 10)
        else -> context.getString(R.string.turn_card_distance_km, meters / 100 / 10f)
    }

    private fun fit(value: String, maxWidth: Float): String {
        if (text.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 0 && text.measureText(value.take(end) + "…") > maxWidth) end--
        return value.take(end) + "…"
    }

    internal companion object {
        const val GEAR_X = .501f
        const val SPEED_X = .15f
        const val BAR_LEFT = .065f
        const val BAR_RIGHT = .235f
        const val BOTTOM_TOP = .905f
        const val BOTTOM_BOTTOM = .975f
        const val ODO_LEFT = .825f
        const val MUSIC_LEFT = .758f
        const val MUSIC_RIGHT = .884f
        const val MUSIC_TOP = .125f
        const val MUSIC_BOTTOM = .379f
        /** CarPlay only: where Google's card sits in Android Auto. */
        const val CARD_LEFT = .755f
        const val CARD_RIGHT = .985f
        const val CARD_TOP = .41f
        const val CARD_BOTTOM = .70f

        private val DIGITS = mapOf(
            '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
            '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
            '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
            '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
            '—' to listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
        )
    }
}
