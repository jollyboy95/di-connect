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

    private var turnImage: Bitmap? = null

    /** Google's arrow for the next turn, drawn in the directions card instead of our own when present. */
    fun setTurnImage(next: Bitmap?) {
        if (next === turnImage) return
        turnImage = next
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
        bavBase?.recycle(); bavBase = null
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
        when (theme.layout) {
            ClusterTheme.Layout.MONO_LINE -> drawMonoLine(canvas, w, h, showMusic)
            ClusterTheme.Layout.TWIN_DIALS -> drawTwinDials(canvas, w, h, showMusic)
            ClusterTheme.Layout.SIDEBAR -> drawSidebar(canvas, w, h, showMusic)
            ClusterTheme.Layout.DECK -> drawDeck(canvas, w, h, showMusic)
            ClusterTheme.Layout.RIBBONS -> drawRibbons(canvas, w, h, showMusic)
            ClusterTheme.Layout.ORBIT -> drawOrbit(canvas, w, h, showMusic)
            ClusterTheme.Layout.ARCS -> drawArcs(canvas, w, h, showMusic)
            ClusterTheme.Layout.PERFORMANCE -> drawPerformance(canvas, w, h, showMusic)
            ClusterTheme.Layout.SPLIT -> drawSplit(canvas, w, h, showMusic)
            ClusterTheme.Layout.CENTRE -> drawCentre(canvas, w, h, showMusic)
            ClusterTheme.Layout.HYBRID_SPORT -> drawHybridSport(canvas, w, h, showMusic)
            ClusterTheme.Layout.VAPORWAVE_STEREO -> drawVaporwaveStereo(canvas, w, h, showMusic)
            ClusterTheme.Layout.MUSIC_LOUNGE -> drawMusicLounge(canvas, w, h, showMusic)
            ClusterTheme.Layout.TURBINE_LUXURY -> drawTurbineLuxury(canvas, w, h)
            ClusterTheme.Layout.PERFORMANCE_COCKPIT -> drawPerformanceCockpit(canvas, w, h)
            ClusterTheme.Layout.MUSIC_PANORAMA -> drawMusicPanorama(canvas, w, h)
            ClusterTheme.Layout.DAYLIGHT_FUTURE -> drawDaylightFuture(canvas, w, h)
            ClusterTheme.Layout.BAVARIAN -> drawBavarian(canvas, w, h, showMusic)
            ClusterTheme.Layout.CLASSIC -> {
            drawHeader(canvas, w, h)
            snapshot?.let { data ->
                drawSpeed(canvas, w, h, data.speedKph)
                drawFlow(canvas, w, h, data.batteryCurrentA)
                drawBottomRow(canvas, w, h, data)
            } ?: drawFlow(canvas, w, h, null)
            if (showMusic) drawMusic(canvas, w, h)
            }
        }
        if (connection == ClusterConnection.CARPLAY) guidance?.takeIf { it.icon != 0 }?.let { drawCard(canvas, w, h, it) }
        // Android Auto: Di-Connect's own directions card, when switched on or while the preview runs.
        else if (!preview && (NavDemo.active || ClusterThemeStore.directionsCard(context)))
            guidance?.takeIf { it.icon != 0 }?.let { drawCard(canvas, w, h, it, directionsBox(w, h, journeyLine(it) != null)) }
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

    // Every shaded draw first sets an opaque colour: a Paint's alpha scales its shader, so a gradient
    // drawn after a translucent colour came out see-through on the car (but not in the first preview).

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
                line.color = Color.BLACK; line.shader = LinearGradient(0f, box.top, 0f, box.bottom,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(46, 255, 255, 255)),
                    floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
                line.strokeWidth = 1.5f * s; canvas.drawRoundRect(box, r, r, line); line.shader = null
            }
            ClusterTheme.Panel.AURORA -> {
                fill.color = theme.surface; canvas.drawRoundRect(box, radius, radius, fill)
                line.color = Color.BLACK; line.shader = LinearGradient(box.left, 0f, box.right, 0f,
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
            ClusterTheme.Panel.FROST -> {
                fill.color = theme.surface; canvas.drawRoundRect(box, radius, radius, fill)
                line.color = Color.BLACK; line.shader = LinearGradient(0f, box.top, 0f, box.bottom, Color.argb(120, 255, 255, 255), Color.argb(24, 255, 255, 255), Shader.TileMode.CLAMP)
                line.strokeWidth = 1.4f * s; canvas.drawRoundRect(box, radius, radius, line); line.shader = null
            }
            ClusterTheme.Panel.LCD -> {
                val r = radius * .5f
                fill.color = theme.surface; canvas.drawRoundRect(box, r, r, fill)
                line.color = theme.edge; line.strokeWidth = 3f * s; canvas.drawRoundRect(box, r, r, line)
            }
            ClusterTheme.Panel.BONE -> {
                bone(canvas, box.left - 2f * s, box.top - 2f * s, box.right + 2f * s, box.bottom + 2f * s, theme.edge)
                bone(canvas, box.left, box.top, box.right, box.bottom, theme.surface)
            }
        }
    }

    /** Some styles keep their panel even when a theme otherwise draws none (the directions card). */
    private fun cardPanel(canvas: Canvas, box: RectF, radius: Float) {
        when (theme.panel) {
            ClusterTheme.Panel.NONE, ClusterTheme.Panel.BONE -> {
                fill.shader = null; fill.color = Color.argb(205, 0, 0, 0); canvas.drawRoundRect(box, radius, radius, fill)
            }
            // A full capsule on a tall card would cut its corners away; keep Ember's black, softly rounded.
            ClusterTheme.Panel.CAPSULE -> {
                fill.shader = null; fill.color = theme.surface; canvas.drawRoundRect(box, radius * 1.6f, radius * 1.6f, fill)
            }
            else -> panel(canvas, box, radius)
        }
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

    /**
     * Android Auto prints a small "Google Maps" label low on the left of its cluster video, about
     * 70-76 % down. A panel that ended inside it cut the words in half, so on Android Auto panels
     * that reach that far run on to [AA_LABEL_COVER] and hide it completely.
     */
    private fun coverBottom(normal: Float) =
        if (connection == ClusterConnection.ANDROID_AUTO) maxOf(normal, AA_LABEL_COVER) else normal

    private val speedCard get() = theme.speed == ClusterTheme.Speed.HEADED || theme.speed == ClusterTheme.Speed.DOTS ||
        theme.speed == ClusterTheme.Speed.SEGMENT

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
                rect.set(.07f * w, .255f * h, .255f * w, coverBottom(.715f) * h)
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
                rect.set(.05f * w, .2f * h, .25f * w, coverBottom(.72f) * h)
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
                rect.set(.05f * w, .2f * h, .25f * w, coverBottom(.72f) * h)
                panel(canvas, rect, .03f * h)
                line.color = Color.WHITE; line.strokeWidth = 3f * s
                canvas.drawLine(rect.left + 22f * s, rect.top + 60f * s, rect.left + 22f * s, rect.top + 22f * s, line)
                canvas.drawLine(rect.left + 22f * s, rect.top + 22f * s, rect.left + 60f * s, rect.top + 22f * s, line)
                fill.color = theme.charge; canvas.drawCircle(rect.right - 30f * s, rect.top + 30f * s, 6f * s, fill)
                val p = .024f * h
                dotDigits(canvas, value, cx - (value.length * 6 - 1) * p / 2f, .27f * h, p, theme.text)
                type(.024f * h, theme.secondary, 400, Paint.Align.CENTER); canvas.drawText("KM/H", cx, .5f * h, text)
            }
            ClusterTheme.Speed.SEGMENT -> {
                rect.set(.05f * w, .2f * h, .25f * w, coverBottom(.72f) * h)
                panel(canvas, rect, .03f * h)
                segmentDigits(canvas, value.replace('—', '-'), cx, .25f * h, .2f * h)
                type(.024f * h, theme.secondary, 700, Paint.Align.CENTER, .2f); canvas.drawText("KM/H", cx, .51f * h, text)
            }
            ClusterTheme.Speed.DOG_TAG -> {
                val ty = .43f * h
                val tr = .15f * h
                fill.color = Color.BLACK; fill.shader = RadialGradient(cx - tr * .3f, ty - tr * .4f, tr, theme.accent, Color.rgb(123, 63, 208), Shader.TileMode.CLAMP)
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
        fill.color = Color.BLACK; fill.shader = RadialGradient(0f, 0f, r,
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
                    fill.color = Color.BLACK; fill.shader = LinearGradient(mid, 0f, fx, 0f, Color.argb(100, 123, 108, 255), tip, Shader.TileMode.CLAMP)
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
            ClusterTheme.Flow.SEGMENT -> {
                val n = 16
                val seg = (r - l) / n
                val lit = (f * n / 2f).roundToInt()
                val ghost = Color.argb(30, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text))
                for (i in 0 until n) {
                    val off = i - n / 2
                    val on = kw != null && ((lit > 0 && off in 0 until lit) || (lit < 0 && off in lit until 0))
                    fill.color = if (on) theme.text else ghost
                    canvas.drawRect(l + i * seg + 2f * s, y - 10f * s, l + (i + 1) * seg - 2f * s, y + 10f * s, fill)
                }
                fill.color = theme.text; canvas.drawRect(mid - 1.5f * s, y - 16f * s, mid + 1.5f * s, y + 16f * s, fill)
                left = "CHG"; right = "PWR"
            }
            ClusterTheme.Flow.PAWS -> {
                rect.set(l - 30f * s, y - 30f * s, r + 30f * s, coverBottom(y / h + .09f) * h)
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

    private fun drawBottomRow(canvas: Canvas, w: Float, h: Float, data: GwmVehicleData.Snapshot, showFuel: Boolean = true) {
        val s = h / 720f
        val top = BOTTOM_TOP * h
        val bottom = BOTTOM_BOTTOM * h
        val mid = (top + bottom) / 2f
        type(.034f * h, theme.text, 500)
        val baseline = mid - (text.descent() + text.ascent()) / 2f
        if (showFuel) {
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
            canvas.drawText(fuel?.let { "${it.roundToInt()}%" } ?: "—", barLeft + barW + .01f * w, baseline, text)
            data.rangeKm?.let {
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText("${it.roundToInt()} km", fuelRight - (if (theme.panel == ClusterTheme.Panel.BONE) .045f else .024f) * h, baseline, text)
            }
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
        // The H7's filler is on the left; keep the direction visible in every pump style.
        val arrow = Path().apply {
            moveTo(left - size * .55f, top + size * .5f)
            lineTo(left - size * .2f, top + size * .25f)
            lineTo(left - size * .2f, top + size * .75f)
            close()
        }
        canvas.drawPath(arrow, fill)
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
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, .78f * h, 0f, h, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(200, 0, 0, 0), Color.argb(230, 0, 0, 0)),
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

    // ---- twin dials ------------------------------------------------------------------------

    /** Two round instruments on the left, a speedometer and a charge/power meter, like a classic cluster. */
    private fun drawTwinDials(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        drawHeader(canvas, w, h)
        val data = snapshot
        // Speedometer: 0-200 over 240 degrees.
        val scx = .125f * w
        val scy = .41f * h
        val sr = .24f * h
        dialFace(canvas, scx, scy, sr)
        val speed = data?.speedKph
        val a0 = Math.toRadians(150.0)
        val sweep = Math.toRadians(240.0)
        for (v in 0..200 step 10) {
            val a = a0 + sweep * v / 200.0
            val major = v % 20 == 0
            line.shader = null; line.color = if (speed != null && v <= speed) theme.accent else theme.secondary
            line.strokeWidth = (if (major) 3f else 1.5f) * s
            val outer = sr - 12f * s
            val inner = outer - (if (major) 22f else 12f) * s
            canvas.drawLine(scx + cos(a).toFloat() * outer, scy + sin(a).toFloat() * outer,
                scx + cos(a).toFloat() * inner, scy + sin(a).toFloat() * inner, line)
            if (major && v % 40 == 0) {
                type(.026f * h, theme.secondary, 500, Paint.Align.CENTER)
                val tr = inner - 22f * s
                canvas.drawText(v.toString(), scx + cos(a).toFloat() * tr, scy + sin(a).toFloat() * tr + .009f * h, text)
            }
        }
        needle(canvas, scx, scy, sr - 20f * s, a0 + sweep * ((speed ?: 0f).coerceIn(0f, 200f) / 200f))
        type(.075f * h, theme.text, 700, Paint.Align.CENTER)
        canvas.drawText(speed?.roundToInt()?.toString() ?: "—", scx, scy + .14f * h, text)
        type(.02f * h, theme.secondary, 600, Paint.Align.CENTER, .15f)
        canvas.drawText("KM/H", scx, scy + .175f * h, text)

        // Charge / power meter: needle up at zero, left for charge, right for power.
        val pcx = .29f * w
        val pcy = .47f * h
        val pr = .155f * h
        dialFace(canvas, pcx, pcy, pr)
        val kw = data?.batteryCurrentA?.let { GwmAndroidAutoClusterView.batteryKw(it) }
        val f = ((kw ?: 0f) / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
        val up = Math.toRadians(270.0)
        val half = Math.toRadians(110.0)
        line.shader = null; line.strokeWidth = 7f * s; line.strokeCap = Paint.Cap.BUTT
        val arc = RectF(pcx - pr + 18f * s, pcy - pr + 18f * s, pcx + pr - 18f * s, pcy + pr - 18f * s)
        line.color = Color.argb(60, Color.red(theme.charge), Color.green(theme.charge), Color.blue(theme.charge))
        canvas.drawArc(arc, 160f, 110f, false, line)
        line.color = Color.argb(60, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        canvas.drawArc(arc, 270f, 110f, false, line)
        if (kw != null && f != 0f) {
            line.color = if (f > 0) theme.accent else theme.charge
            canvas.drawArc(arc, 270f, (110f * f), false, line)
        }
        line.strokeCap = Paint.Cap.ROUND
        needle(canvas, pcx, pcy, pr - 26f * s, up + half * f)
        type(.017f * h, theme.charge, 700, Paint.Align.LEFT, .12f)
        canvas.drawText("CHG", pcx - pr + 26f * s, pcy + .07f * h, text)
        type(.017f * h, theme.accent, 700, Paint.Align.RIGHT, .12f)
        canvas.drawText("PWR", pcx + pr - 26f * s, pcy + .07f * h, text)
        type(.03f * h, theme.text, 700, Paint.Align.CENTER)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", pcx, pcy + .1f * h, text)
        data?.let { drawBottomRow(canvas, w, h, it) }
        if (showMusic) drawMusic(canvas, w, h)
    }

    private fun dialFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = height / 720f
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy - r * .3f, r * 1.2f, theme.surface,
            Color.argb(Color.alpha(theme.surface), 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill); fill.shader = null
        line.shader = null; line.color = theme.edge; line.strokeWidth = 3f * s; canvas.drawCircle(cx, cy, r, line)
        line.strokeWidth = 1f * s; canvas.drawCircle(cx, cy, r - 7f * s, line)
    }

    private fun needle(canvas: Canvas, cx: Float, cy: Float, length: Float, angle: Double) {
        val s = height / 720f
        val tx = cx + cos(angle).toFloat() * length
        val ty = cy + sin(angle).toFloat() * length
        line.shader = null
        line.color = Color.argb(70, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        line.strokeWidth = 10f * s; canvas.drawLine(cx, cy, tx, ty, line)
        line.color = theme.accent; line.strokeWidth = 4f * s; canvas.drawLine(cx, cy, tx, ty, line)
        fill.shader = null; fill.color = theme.text; canvas.drawCircle(cx, cy, 9f * s, fill)
        fill.color = theme.surface; canvas.drawCircle(cx, cy, 4f * s, fill)
    }

    // ---- sidebar ---------------------------------------------------------------------------

    /** One solid column down the left edge holds everything; the rest of the screen is map. */
    private fun drawSidebar(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val right = SIDEBAR_RIGHT * w
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, 0f, right, 0f, theme.surface,
            Color.argb(Color.alpha(theme.surface), Color.red(theme.surface) * 3 / 4, Color.green(theme.surface) * 3 / 4,
                Color.blue(theme.surface) * 3 / 4), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, right, h, fill); fill.shader = null
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.TRANSPARENT, theme.accent, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(right - 3f * s, 0f, right, h, fill); fill.shader = null
        val left = .02f * w
        val inner = right - .02f * w
        val data = snapshot
        // Time and temperature.
        type(.05f * h, theme.text, 600); canvas.drawText(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), left, .09f * h, text)
        data?.outsideTempC?.let { type(.032f * h, theme.secondary, 500, Paint.Align.RIGHT); canvas.drawText("${it.roundToInt()}°C", inner, .09f * h, text) }
        divider(canvas, left, inner, .125f * h)
        // Speed.
        sideLabel(canvas, context.getString(R.string.di_connect_theme_speed), left, .175f * h)
        type(.17f * h, theme.text, 800)
        val speed = data?.speedKph?.roundToInt()?.toString() ?: "—"
        canvas.drawText(speed, left - 4f * s, .335f * h, text)
        val sw = text.measureText(speed)
        type(.03f * h, theme.secondary, 600); canvas.drawText("km/h", left + sw + 6f * s, .335f * h, text)
        divider(canvas, left, inner, .375f * h)
        // Power.
        val kw = data?.batteryCurrentA?.let { GwmAndroidAutoClusterView.batteryKw(it) }
        sideLabel(canvas, "POWER", left, .425f * h)
        type(.036f * h, when { kw == null -> theme.secondary; kw >= 0 -> theme.accent; else -> theme.charge }, 700, Paint.Align.RIGHT)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", inner, .43f * h, text)
        val mid = (left + inner) / 2f
        rect.set(left, .465f * h, inner, .465f * h + 10f * s); fill.color = Color.argb(46, 255, 255, 255)
        canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        if (kw != null) {
            val fx = mid + (inner - left) / 2f * (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            rect.set(minOf(mid, fx), .465f * h, maxOf(mid, fx), .465f * h + 10f * s)
            fill.color = if (kw >= 0) theme.accent else theme.charge; canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        }
        fill.color = theme.text; canvas.drawRect(mid - 1.5f * s, .455f * h, mid + 1.5f * s, .465f * h + 20f * s, fill)
        divider(canvas, left, inner, .53f * h)
        // Fuel and range.
        sideLabel(canvas, "FUEL", left, .58f * h)
        val fuel = data?.fuelPercent
        type(.036f * h, theme.text, 700, Paint.Align.RIGHT)
        canvas.drawText(listOfNotNull(fuel?.let { "${it.roundToInt()}%" }, data?.rangeKm?.let { "${it.roundToInt()} km" })
            .joinToString("  ").ifEmpty { "—" }, inner, .585f * h, text)
        rect.set(left, .62f * h, inner, .62f * h + 10f * s); fill.color = Color.argb(46, 255, 255, 255)
        canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        if (fuel != null) {
            rect.right = left + (inner - left) * (fuel / 100f).coerceIn(.03f, 1f)
            fill.color = if (fuel < 15f) Color.rgb(255, 159, 10) else theme.charge; canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        }
        data?.odometerKm?.let {
            type(.024f * h, theme.secondary, 500)
            canvas.drawText("ODO  " + NumberFormat.getIntegerInstance().format(it.roundToInt()) + " km", left, .69f * h, text)
        }
        divider(canvas, left, inner, .735f * h)
        // Now playing.
        if (showMusic) music.info.title?.takeIf { it.isNotBlank() }?.let { title ->
            val side = .15f * h
            rect.set(left, .77f * h, left + side, .77f * h + side)
            val cover = music.artwork?.takeUnless(Bitmap::isRecycled)
            canvas.save(); path.reset(); path.addRoundRect(rect, 10f * s, 10f * s, Path.Direction.CW); canvas.clipPath(path)
            if (cover != null) canvas.drawBitmap(cover, null, rect, art) else { fill.color = Color.argb(60, 255, 255, 255); canvas.drawRect(rect, fill) }
            canvas.restore()
            val tx = left + side + .012f * w
            val tw = inner - tx
            type(.03f * h, theme.text, 700); canvas.drawText(fit(title, tw), tx, .82f * h, text)
            music.info.artist?.takeIf { it.isNotBlank() }?.let { type(.024f * h, theme.secondary, 500); canvas.drawText(fit(it, tw), tx, .865f * h, text) }
            progress()?.let { done ->
                rect.set(tx, .895f * h, inner, .895f * h + 4f * s); fill.color = Color.argb(50, 255, 255, 255); canvas.drawRoundRect(rect, 2f * s, 2f * s, fill)
                rect.right = tx + tw * done; fill.color = theme.accent; canvas.drawRoundRect(rect, 2f * s, 2f * s, fill)
            }
        }
    }

    private fun divider(canvas: Canvas, left: Float, right: Float, y: Float) {
        fill.shader = null; fill.color = theme.edge; canvas.drawRect(left, y, right, y + height / 720f, fill)
    }

    private fun sideLabel(canvas: Canvas, label: String, x: Float, y: Float) {
        type(.019f * height, theme.secondary, 700, Paint.Align.LEFT, .18f); canvas.drawText(label.uppercase(), x, y, text)
    }

    // ---- bottom deck -----------------------------------------------------------------------

    /** A console along the bottom edge with a raised centre for the speed; the top stays map. */
    private fun drawDeck(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val top = DECK_TOP * h
        val hump = DECK_HUMP * h
        path.reset()
        path.moveTo(0f, top); path.lineTo(.33f * w, top)
        path.cubicTo(.36f * w, top, .36f * w, hump, .39f * w, hump)
        path.lineTo(.61f * w, hump)
        path.cubicTo(.64f * w, hump, .64f * w, top, .67f * w, top)
        path.lineTo(w, top); path.lineTo(w, h); path.lineTo(0f, h); path.close()
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, hump, 0f, h, theme.surface,
            Color.argb(Color.alpha(theme.surface), Color.red(theme.surface) / 2, Color.green(theme.surface) / 2, Color.blue(theme.surface) / 2),
            Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill); fill.shader = null
        line.color = Color.BLACK; line.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.TRANSPARENT, theme.accent, theme.accent, Color.TRANSPARENT),
            floatArrayOf(0f, .3f, .7f, 1f), Shader.TileMode.CLAMP)
        line.strokeWidth = 2.5f * s; canvas.drawPath(path, line); line.shader = null
        val data = snapshot
        // Speed in the raised centre.
        // The car draws its own indicators over this strip: EV, the speed-limit sign and lane
        // assist low on the left (about 3-20 % across, 83-91 % down), READY under the middle
        // (46-58 % across, below 91 %) and the drive mode at 75-79 % across on the bottom line.
        // Everything here keeps out of those spots.
        val value = data?.speedKph?.roundToInt()?.toString() ?: "—"
        type(.13f * h, theme.text, 800, Paint.Align.CENTER)
        canvas.drawText(value, .5f * w, .875f * h, text)
        val half = text.measureText(value) / 2f
        type(.022f * h, theme.secondary, 600, Paint.Align.LEFT, .2f); canvas.drawText("KM/H", .5f * w + half + .006f * w, .875f * h, text)
        // Left wing, all on the bottom line under the car's indicators: fuel, then power.
        val row = .962f * h
        val kw = data?.batteryCurrentA?.let { GwmAndroidAutoClusterView.batteryKw(it) }
        val fl = .03f * w
        drawPump(canvas, fl, row - .03f * h, .03f * h)
        type(.03f * h, theme.text, 600)
        val fuelText = listOfNotNull(data?.fuelPercent?.let { "${it.roundToInt()}%" }, data?.rangeKm?.let { "${it.roundToInt()} km" })
            .joinToString("  ·  ").ifEmpty { "—" }
        canvas.drawText(fuelText, fl + .03f * h, row, text)
        val l = maxOf(fl + .03f * h + text.measureText(fuelText) + .025f * w, .17f * w)
        val r = l + .11f * w
        val mid = (l + r) / 2f
        val by = row - .012f * h
        rect.set(l, by - 4f * s, r, by + 4f * s); fill.shader = null; fill.color = Color.argb(46, 255, 255, 255); canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        if (kw != null) {
            val fx = mid + (r - l) / 2f * (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            rect.set(minOf(mid, fx), by - 4f * s, maxOf(mid, fx), by + 4f * s)
            fill.color = if (kw >= 0) theme.accent else theme.charge; canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        }
        fill.color = theme.text; canvas.drawRect(mid - 1f * s, by - 10f * s, mid + 1f * s, by + 10f * s, fill)
        type(.028f * h, when { kw == null -> theme.secondary; kw >= 0 -> theme.accent; else -> theme.charge }, 700)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", r + .01f * w, row, text)
        // Right wing, right-aligned so it ends clear of the drive-mode label: music, then the rest.
        val rr = .97f * w
        val room = rr - .8f * w
        if (showMusic) music.info.title?.takeIf { it.isNotBlank() }?.let { title ->
            type(.03f * h, theme.text, 700, Paint.Align.RIGHT)
            canvas.drawText(fit(listOfNotNull(title, music.info.artist?.takeIf { it.isNotBlank() }).joinToString(" — "), rr - .66f * w), rr, .9f * h, text)
        }
        type(.026f * h, theme.secondary, 500, Paint.Align.RIGHT)
        val info = listOfNotNull(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
            data?.outsideTempC?.let { "${it.roundToInt()}°C" },
            data?.odometerKm?.let { NumberFormat.getIntegerInstance().format(it.roundToInt()) + " km" }).joinToString("   ")
        canvas.drawText(fit(info, room), rr, row, text)
    }

    // ---- segment display -------------------------------------------------------------------

    /** Seven-segment digits with faint unlit segments behind them, like an old LCD or VFD. */
    private fun segmentDigits(canvas: Canvas, value: String, cx: Float, top: Float, digitH: Float) {
        val dw = digitH * .52f
        val gap = digitH * .14f
        val t = digitH * .11f
        val total = value.length * dw + (value.length - 1) * gap
        var x = cx - total / 2f
        val ghost = Color.argb(30, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text))
        for (c in value) {
            val lit = SEGMENTS[c] ?: ""
            for (seg in "abcdefg") segment(canvas, seg, x, top, dw, digitH, t, if (seg in lit) theme.text else ghost)
            x += dw + gap
        }
    }

    private fun segment(canvas: Canvas, seg: Char, x: Float, y: Float, w: Float, h: Float, t: Float, color: Int) {
        val half = h / 2f
        val skew = h * .06f
        fun sx(px: Float, py: Float) = px + skew * (1f - (py - y) / h)
        val (x0, y0, x1, y1) = when (seg) {
            'a' -> listOf(x + t, y, x + w - t, y + t)
            'b' -> listOf(x + w - t, y + t * .6f, x + w, y + half - t * .4f)
            'c' -> listOf(x + w - t, y + half + t * .4f, x + w, y + h - t * .6f)
            'd' -> listOf(x + t, y + h - t, x + w - t, y + h)
            'e' -> listOf(x, y + half + t * .4f, x + t, y + h - t * .6f)
            'f' -> listOf(x, y + t * .6f, x + t, y + half - t * .4f)
            else -> listOf(x + t, y + half - t / 2f, x + w - t, y + half + t / 2f)
        }
        path.reset()
        path.moveTo(sx(x0, y0), y0); path.lineTo(sx(x1, y0), y0); path.lineTo(sx(x1, y1), y1); path.lineTo(sx(x0, y1), y1); path.close()
        fill.shader = null; fill.color = color; canvas.drawPath(path, fill)
    }

    // ---- shared pieces for the expressive layouts ------------------------------------------

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** A stroke with a soft halo: a wide faint pass under a thin bright one. */
    private fun glowStroke(canvas: Canvas, p: Path, color: Int, width: Float, shader: Shader? = null) {
        line.strokeCap = Paint.Cap.ROUND
        line.color = Color.BLACK; line.shader = shader
        line.color = withAlpha(color, 46); line.strokeWidth = width * 4f; if (shader == null) canvas.drawPath(p, line)
        line.color = withAlpha(color, 90); line.strokeWidth = width * 2f; if (shader == null) canvas.drawPath(p, line)
        line.color = color; line.strokeWidth = width; canvas.drawPath(p, line)
        line.shader = null
    }

    /** Darkens the left of the screen so a large instrument reads over a bright map. */
    private fun leftShade(canvas: Canvas, w: Float, h: Float, until: Float, alpha: Int) {
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, 0f, until * w, 0f, intArrayOf(Color.argb(alpha, 0, 0, 0), Color.argb(alpha * 2 / 3, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, until * w, h, fill); fill.shader = null
    }

    private fun kwNow(): Float? = snapshot?.batteryCurrentA?.let { GwmAndroidAutoClusterView.batteryKw(it) }

    private fun kwColor(kw: Float?) = when { kw == null -> theme.secondary; kw >= 0 -> theme.accent; else -> theme.charge }

    /** Now playing as one line of spaced capitals over a waveform, with no card behind it. */
    private fun drawMusicLine(canvas: Canvas, w: Float, h: Float) {
        val s = h / 720f
        val title = music.info.title?.takeIf { it.isNotBlank() } ?: return
        val right = .96f * w
        type(.026f * h, theme.text, 600, Paint.Align.RIGHT, .14f)
        canvas.drawText(fit(title.uppercase(), .2f * w), right, .1f * h, text)
        music.info.artist?.takeIf { it.isNotBlank() }?.let {
            type(.019f * h, theme.secondary, 500, Paint.Align.RIGHT, .1f); canvas.drawText(fit(it, .2f * w), right, .14f * h, text)
        }
        val bars = 34
        val width = .13f * w
        val step = width / bars
        val done = progress() ?: 0f
        var seed = title.hashCode()
        for (i in 0 until bars) {
            seed = seed * 1103515245 + 12345
            val shape = sin(Math.PI * i / (bars - 1)).toFloat()
            val k = (.25f + .75f * ((seed ushr 16) and 255) / 255f) * (.35f + .65f * shape)
            val x = right - width + i * step + step / 2f
            val half = k * .028f * h
            line.shader = null; line.strokeCap = Paint.Cap.ROUND; line.strokeWidth = 2.2f * s
            line.color = if (i.toFloat() / bars <= done) theme.accent else withAlpha(theme.text, 90)
            canvas.drawLine(x, .19f * h - half, x, .19f * h + half, line)
        }
    }

    // ---- ribbons ----------------------------------------------------------------------------

    /**
     * Light ribbons sweep down the left edge around the speed and trail off along the bottom.
     * The outer ribbons glow in the power colour as the car pulls, the inner ones in the charge
     * colour while it regenerates.
     */
    private fun drawRibbons(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val data = snapshot
        val cx = .165f * w
        val cy = .45f * h
        darkGlow(canvas, cx, cy, .19f * h, 1.25f, 1.05f, Color.rgb(3, 6, 16))
        val kw = kwNow()
        val f = ((kw ?: 0f) / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
        val measure = android.graphics.PathMeasure()
        val piece = Path()
        for (i in 0 until 7) {
            val r = (.25f + i * .034f) * h
            val warm = i >= 3
            val color = if (warm) theme.accent else theme.charge
            // The arc: from the top, anticlockwise down the left side to just past the bottom.
            path.reset()
            rect.set(cx - r, cy - r, cx + r, cy + r)
            path.arcTo(rect, 292f, -192f, true)
            val shade = LinearGradient(0f, cy - r, 0f, cy + r, intArrayOf(Color.TRANSPARENT, withAlpha(color, 200), color),
                floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
            val width = (if (i == 3) 3.4f else 2f) * s
            line.color = withAlpha(color, 40); line.strokeWidth = width * 5f; line.shader = null; canvas.drawPath(path, line)
            glowStroke(canvas, path, color, width, shade)
            // The tail: leaves the bottom heading right and fades out under the map.
            val a = Math.toRadians(100.0)
            val ex = cx + r * cos(a).toFloat()
            val ey = cy + r * sin(a).toFloat()
            val endX = (.42f + i * .018f) * w
            val endY = minOf(ey + .05f * h, .985f * h)
            piece.reset(); piece.moveTo(ex, ey)
            piece.cubicTo(ex + .08f * w, ey + .03f * h, endX - .1f * w, endY, endX, endY)
            val tail = LinearGradient(ex, 0f, endX, 0f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            line.strokeWidth = width; line.color = Color.BLACK; line.shader = tail; line.color = color; canvas.drawPath(piece, line); line.shader = null
            // Live energy: a brighter run along the ribbons in use, as long as the flow is strong.
            if (kw != null && ((warm && f > 0f) || (!warm && f < 0f))) {
                measure.setPath(path, false)
                val len = measure.length * kotlin.math.abs(f)
                piece.reset(); measure.getSegment(measure.length - len, measure.length, piece, true)
                line.color = Color.WHITE; line.strokeWidth = width * 1.2f; canvas.drawPath(piece, line)
            }
        }
        // Speed scale on the inside of the innermost ribbon, 0 at the bottom to 200 at the top.
        val speed = data?.speedKph
        val sr = .21f * h
        for (v in 0..200 step 10) {
            val a = Math.toRadians(112.0 + 136.0 * v / 200.0)
            val major = v % 50 == 0
            line.shader = null; line.strokeWidth = (if (major) 2.4f else 1.2f) * s
            line.color = if (speed != null && v <= speed) theme.accent else withAlpha(theme.text, 70)
            val k = (if (major) 16f else 9f) * s
            canvas.drawLine(cx + cos(a).toFloat() * sr, cy + sin(a).toFloat() * sr,
                cx + cos(a).toFloat() * (sr - k), cy + sin(a).toFloat() * (sr - k), line)
            if (major) {
                type(.018f * h, theme.secondary, 500, Paint.Align.CENTER)
                canvas.drawText(v.toString(), cx + cos(a).toFloat() * (sr - 32f * s), cy + sin(a).toFloat() * (sr - 32f * s) + .006f * h, text)
            }
        }
        type(.2f * h, theme.text, 300, Paint.Align.CENTER)
        canvas.drawText(speed?.roundToInt()?.toString() ?: "—", cx + .02f * w, cy + .07f * h, text)
        type(.026f * h, theme.secondary, 500, Paint.Align.CENTER, .1f)
        canvas.drawText("km/h", cx + .02f * w, cy + .115f * h, text)
        type(.024f * h, kwColor(kw), 700, Paint.Align.CENTER)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", cx + .02f * w, cy + .16f * h, text)
        drawHeader(canvas, w, h)
        data?.let { drawBottomRow(canvas, w, h, it) }
        if (showMusic) drawMusicLine(canvas, w, h)
    }

    // ---- orbit ------------------------------------------------------------------------------

    /** A tilted glowing ring around a large thin speed; power runs round the ring as a comet. */
    private fun drawOrbit(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val data = snapshot
        val cx = .15f * w
        val cy = .46f * h
        darkGlow(canvas, cx, cy, .17f * h, 1.3f, 1f, Color.rgb(4, 7, 14))
        // A faint upright dial behind the ring.
        val dr = .2f * h
        line.shader = null; line.color = withAlpha(theme.text, 60); line.strokeWidth = 1f * s
        canvas.drawCircle(cx, cy, dr, line)
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val k = (if (i % 5 == 0) 12f else 5f) * s
            line.strokeWidth = (if (i % 5 == 0) 1.6f else 1f) * s
            canvas.drawLine(cx + cos(a).toFloat() * dr, cy + sin(a).toFloat() * dr,
                cx + cos(a).toFloat() * (dr - k), cy + sin(a).toFloat() * (dr - k), line)
        }
        // The ring itself, tilted.
        canvas.save()
        canvas.rotate(-14f, cx, cy)
        val oval = RectF(cx - .125f * w, cy - .085f * h, cx + .125f * w, cy + .085f * h)
        val ring = android.graphics.SweepGradient(cx, cy, intArrayOf(theme.charge, Color.WHITE, theme.accent, withAlpha(theme.charge, 60), theme.charge), null)
        path.reset(); path.addOval(oval, Path.Direction.CW)
        line.color = withAlpha(theme.charge, 50); line.strokeWidth = 12f * s; canvas.drawPath(path, line)
        glowStroke(canvas, path, theme.charge, 2.6f * s, ring)
        val kw = kwNow()
        if (kw != null) {
            val f = (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            if (f != 0f) {
                line.shader = null; line.strokeCap = Paint.Cap.ROUND
                line.color = withAlpha(kwColor(kw), 90); line.strokeWidth = 14f * s; canvas.drawArc(oval, 90f, 150f * f, false, line)
                line.color = kwColor(kw); line.strokeWidth = 5f * s; canvas.drawArc(oval, 90f, 150f * f, false, line)
            }
        }
        canvas.restore()
        type(.21f * h, theme.text, 200, Paint.Align.CENTER)
        canvas.drawText(data?.speedKph?.roundToInt()?.toString() ?: "—", cx, cy + .075f * h, text)
        type(.02f * h, theme.secondary, 500, Paint.Align.CENTER, .3f)
        canvas.drawText("KM/H", cx, cy - .11f * h, text)
        type(.024f * h, kwColor(kw), 600, Paint.Align.CENTER)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", cx, cy + .15f * h, text)
        drawHeader(canvas, w, h)
        data?.let { drawBottomRow(canvas, w, h, it) }
        if (showMusic) drawMusicLine(canvas, w, h)
    }

    // ---- arcs -------------------------------------------------------------------------------

    /** A speed ring with brackets of light beside it: power on the inner arc, fuel on the outer. */
    private fun drawArcs(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val data = snapshot
        leftShade(canvas, w, h, .36f, 190)
        val cx = .12f * w
        val cy = .48f * h
        val r = .19f * h
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy, r, Color.argb(235, 6, 10, 22), Color.argb(235, 12, 22, 44), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill); fill.shader = null
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW)
        glowStroke(canvas, path, theme.accent, 2.5f * s)
        // Chevrons either side of the ring.
        for (dir in listOf(-1f, 1f)) {
            val x = cx + dir * (r + 14f * s)
            path.reset(); path.moveTo(x, cy - 10f * s); path.lineTo(x + dir * 10f * s, cy); path.lineTo(x, cy + 10f * s)
            line.shader = null; line.color = theme.accent; line.strokeWidth = 3f * s; canvas.drawPath(path, line)
        }
        type(.16f * h, theme.text, 300, Paint.Align.CENTER)
        canvas.drawText(data?.speedKph?.roundToInt()?.toString() ?: "—", cx, cy + .055f * h, text)
        type(.024f * h, theme.secondary, 500, Paint.Align.CENTER); canvas.drawText("km/h", cx, cy + .1f * h, text)
        val kw = kwNow()
        type(.022f * h, kwColor(kw), 700, Paint.Align.CENTER)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", cx, cy - .085f * h, text)
        // Power: up from the middle for power, down for charge.
        line.strokeCap = Paint.Cap.ROUND
        val inner = RectF(cx - .27f * h, cy - .27f * h, cx + .27f * h, cy + .27f * h)
        line.shader = null; line.color = withAlpha(theme.text, 40); line.strokeWidth = 8f * s; canvas.drawArc(inner, -48f, 96f, false, line)
        if (kw != null) {
            val f = (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            line.color = withAlpha(kwColor(kw), 80); line.strokeWidth = 16f * s; canvas.drawArc(inner, 0f, -48f * f, false, line)
            line.color = kwColor(kw); line.strokeWidth = 8f * s; canvas.drawArc(inner, 0f, -48f * f, false, line)
        }
        line.color = theme.text; line.strokeWidth = 2f * s
        canvas.drawLine(cx + .255f * h, cy, cx + .285f * h, cy, line)
        // Fuel: fills upward from the bottom.
        val outer = RectF(cx - .32f * h, cy - .32f * h, cx + .32f * h, cy + .32f * h)
        line.color = withAlpha(theme.text, 40); line.strokeWidth = 8f * s; canvas.drawArc(outer, 48f, -96f, false, line)
        data?.fuelPercent?.let { fuel ->
            line.color = if (fuel < 15f) Color.rgb(255, 159, 10) else FUEL_GOLD
            canvas.drawArc(outer, 48f, -96f * (fuel / 100f).coerceIn(.02f, 1f), false, line)
        }
        drawPump(canvas, cx + .32f * h * cos(Math.toRadians(52.0)).toFloat() - .012f * h,
            cy + .32f * h * sin(Math.toRadians(52.0)).toFloat(), .028f * h)
        // A thin outer ring of ticks.
        line.color = withAlpha(theme.accent, 120); line.strokeWidth = 1.2f * s
        for (i in -8..8) {
            val a = Math.toRadians(i * 6.0)
            canvas.drawLine(cx + cos(a).toFloat() * .36f * h, cy + sin(a).toFloat() * .36f * h,
                cx + cos(a).toFloat() * (.36f * h + (if (i % 4 == 0) 12f else 6f) * s), cy + sin(a).toFloat() * (.36f * h + (if (i % 4 == 0) 12f else 6f) * s), line)
        }
        // Fuel and range written up the side.
        val reading = listOfNotNull(data?.fuelPercent?.let { "${it.roundToInt()}%" }, data?.rangeKm?.let { "${it.roundToInt()} km" }).joinToString("  •  ")
        if (reading.isNotEmpty()) {
            canvas.save(); canvas.rotate(-90f, cx + .43f * h, cy)
            type(.03f * h, theme.text, 600, Paint.Align.CENTER, .06f); canvas.drawText(reading, cx + .43f * h, cy + .01f * h, text)
            canvas.restore()
        }
        drawHeader(canvas, w, h)
        data?.let { drawBottomRow(canvas, w, h, it, showFuel = false) }
        if (showMusic) drawMusic(canvas, w, h)
    }

    // ---- performance ------------------------------------------------------------------------

    /** A big segmented arc gauge that heats from green to red as speed climbs. */
    private fun drawPerformance(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val data = snapshot
        leftShade(canvas, w, h, .34f, 200)
        val cx = .15f * w
        val cy = .5f * h
        val r = .29f * h
        val speed = data?.speedKph ?: 0f
        val segments = 40
        val start = 150f
        val sweep = 240f
        val each = sweep / segments
        val band = RectF(cx - r, cy - r, cx + r, cy + r)
        line.shader = null; line.strokeCap = Paint.Cap.BUTT; line.strokeWidth = .055f * h
        for (i in 0 until segments) {
            val t = i / (segments - 1f)
            val hue = 120f * (1f - t)
            val color = Color.HSVToColor(floatArrayOf(hue, .95f, 1f))
            val lit = data?.speedKph != null && t <= speed / 200f
            line.color = if (lit) color else withAlpha(color, 34)
            canvas.drawArc(band, start + i * each + .8f, each - 1.6f, false, line)
        }
        line.strokeCap = Paint.Cap.ROUND
        // Hairline rim and the scale inside it.
        line.color = withAlpha(theme.text, 110); line.strokeWidth = 1.5f * s
        val rim = RectF(cx - r - .04f * h, cy - r - .04f * h, cx + r + .04f * h, cy + r + .04f * h)
        canvas.drawArc(rim, start, sweep, false, line)
        for (v in 0..200 step 20) {
            val a = Math.toRadians((start + sweep * v / 200f).toDouble())
            type(.022f * h, theme.secondary, 600, Paint.Align.CENTER)
            val tr = r - .07f * h
            canvas.drawText(v.toString(), cx + cos(a).toFloat() * tr, cy + sin(a).toFloat() * tr + .008f * h, text)
        }
        type(.17f * h, theme.text, 800, Paint.Align.CENTER); text.textSkewX = -.08f
        canvas.drawText(data?.speedKph?.roundToInt()?.toString() ?: "—", cx, cy + .05f * h, text)
        type(.024f * h, theme.accent, 700, Paint.Align.CENTER, .2f); canvas.drawText("KM/H", cx, cy + .095f * h, text)
        // Power in the gap at the bottom of the gauge.
        val kw = kwNow()
        val l = cx - .1f * w * .55f
        val rr = cx + .1f * w * .55f
        val y = cy + .17f * h
        val mid = (l + rr) / 2f
        rect.set(l, y - 4f * s, rr, y + 4f * s); fill.shader = null; fill.color = withAlpha(theme.text, 46); canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        if (kw != null) {
            val fx = mid + (rr - l) / 2f * (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            rect.set(minOf(mid, fx), y - 4f * s, maxOf(mid, fx), y + 4f * s); fill.color = kwColor(kw); canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        }
        fill.color = theme.text; canvas.drawRect(mid - 1f * s, y - 9f * s, mid + 1f * s, y + 9f * s, fill)
        type(.024f * h, kwColor(kw), 700, Paint.Align.CENTER)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", cx, y - .025f * h, text)
        drawHeader(canvas, w, h)
        data?.let { drawBottomRow(canvas, w, h, it) }
        if (showMusic) drawMusic(canvas, w, h)
    }

    // ---- split ------------------------------------------------------------------------------

    /**
     * The instruments fill the left half and the map the right half (Android Auto draws the map for
     * that window). Keeps out of the car's own icons low on the left (3-22 % across, 82-92 % down).
     */
    private fun drawSplit(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val edge = (ClusterTheme.SPLIT_MAP.first) * w
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, 0f, edge, h, theme.surface,
            Color.argb(Color.alpha(theme.surface), Color.red(theme.surface) * 2 / 3, Color.green(theme.surface) * 2 / 3,
                Color.blue(theme.surface) * 2 / 3), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, edge, h, fill); fill.shader = null
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.TRANSPARENT, theme.accent, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(edge - 2f * s, 0f, edge, h, fill); fill.shader = null
        val data = snapshot
        val left = .035f * w
        val right = edge - .03f * w
        // Clock and temperature.
        type(.045f * h, theme.text, 600)
        canvas.drawText(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), left, .1f * h, text)
        data?.outsideTempC?.let { type(.04f * h, theme.secondary, 500, Paint.Align.RIGHT); canvas.drawText("${it.roundToInt()}°C", right, .1f * h, text) }
        // Speed, large, on the left of the panel.
        val speedX = .14f * w
        type(.3f * h, theme.text, 700, Paint.Align.CENTER)
        canvas.drawText(data?.speedKph?.roundToInt()?.toString() ?: "—", speedX, .5f * h, text)
        type(.03f * h, theme.secondary, 600, Paint.Align.CENTER, .2f); canvas.drawText("KM/H", speedX, .57f * h, text)
        // Power and fuel on the right of the panel.
        val col = .29f * w
        val kw = kwNow()
        sideLabel(canvas, "POWER", col, .25f * h)
        type(.05f * h, kwColor(kw), 700); canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", col, .32f * h, text)
        val mid = (col + right) / 2f
        rect.set(col, .355f * h, right, .355f * h + 10f * s); fill.color = withAlpha(theme.text, 46); canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        if (kw != null) {
            val fx = mid + (right - col) / 2f * (kw / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            rect.set(minOf(mid, fx), .355f * h, maxOf(mid, fx), .355f * h + 10f * s); fill.color = kwColor(kw); canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        }
        fill.color = theme.text; canvas.drawRect(mid - 1.5f * s, .345f * h, mid + 1.5f * s, .355f * h + 20f * s, fill)
        sideLabel(canvas, "FUEL", col, .46f * h)
        val fuel = data?.fuelPercent
        type(.05f * h, theme.text, 700); canvas.drawText(fuel?.let { "${it.roundToInt()}%" } ?: "—", col, .53f * h, text)
        data?.rangeKm?.let { type(.032f * h, theme.secondary, 500, Paint.Align.RIGHT); canvas.drawText("${it.roundToInt()} km", right, .53f * h, text) }
        rect.set(col, .565f * h, right, .565f * h + 10f * s); fill.color = withAlpha(theme.text, 46); canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        if (fuel != null) {
            rect.right = col + (right - col) * (fuel / 100f).coerceIn(.03f, 1f)
            fill.color = if (fuel < 15f) Color.rgb(255, 159, 10) else theme.charge; canvas.drawRoundRect(rect, 5f * s, 5f * s, fill)
        }
        divider(canvas, left, right, .64f * h)
        // Now playing, across the panel above the car's icons.
        if (showMusic) music.info.title?.takeIf { it.isNotBlank() }?.let { title ->
            val side = .12f * h
            val top = .67f * h
            rect.set(left, top, left + side, top + side)
            val cover = music.artwork?.takeUnless(Bitmap::isRecycled)
            canvas.save(); path.reset(); path.addRoundRect(rect, 10f * s, 10f * s, Path.Direction.CW); canvas.clipPath(path)
            if (cover != null) canvas.drawBitmap(cover, null, rect, art) else { fill.color = withAlpha(theme.text, 50); canvas.drawRect(rect, fill) }
            canvas.restore()
            val tx = left + side + .015f * w
            type(.034f * h, theme.text, 700); canvas.drawText(fit(title, right - tx), tx, top + .05f * h, text)
            music.info.artist?.takeIf { it.isNotBlank() }?.let { type(.026f * h, theme.secondary, 500); canvas.drawText(fit(it, right - tx), tx, top + .095f * h, text) }
            progress()?.let { done ->
                rect.set(tx, top + .112f * h, right, top + .112f * h + 4f * s); fill.color = withAlpha(theme.text, 50); canvas.drawRoundRect(rect, 2f * s, 2f * s, fill)
                rect.right = tx + (right - tx) * done; fill.color = theme.accent; canvas.drawRoundRect(rect, 2f * s, 2f * s, fill)
            }
        }
        // Odometer bottom right of the panel, clear of the car's icons on the left.
        data?.odometerKm?.let {
            type(.028f * h, theme.secondary, 500, Paint.Align.RIGHT)
            // READY sits from 46 % across on the bottom line, so end well before it.
            canvas.drawText("ODO  " + NumberFormat.getIntegerInstance().format(it.roundToInt()) + " km", .43f * w, .955f * h, text)
        }
    }

    // ---- centre stage -----------------------------------------------------------------------

    /**
     * Like the car's own cluster: a speedometer on the left, a power dial on the right and the map
     * through the middle (Android Auto draws it for that window), with soft fades at its edges.
     */
    private fun drawCentre(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val s = h / 720f
        val (from, to) = ClusterTheme.CENTRE_MAP
        val l = from * w
        val r = to * w
        val shade = Color.argb(255, Color.red(theme.surface), Color.green(theme.surface), Color.blue(theme.surface))
        fill.color = shade; fill.shader = null
        canvas.drawRect(0f, 0f, l, h, fill)
        canvas.drawRect(r, 0f, w, h, fill)
        val fade = .045f * w
        fill.color = Color.BLACK; fill.shader = LinearGradient(l, 0f, l + fade, 0f, shade, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(l, 0f, l + fade, h, fill)
        fill.color = Color.BLACK; fill.shader = LinearGradient(r - fade, 0f, r, 0f, Color.TRANSPARENT, shade, Shader.TileMode.CLAMP)
        canvas.drawRect(r - fade, 0f, r, h, fill); fill.shader = null
        val data = snapshot
        // Speedometer: 0-220 over 240 degrees.
        val sx = .125f * w
        val cy = .5f * h
        val dr = .3f * h
        val speed = data?.speedKph
        val a0 = Math.toRadians(150.0)
        val sweep = Math.toRadians(240.0)
        for (v in 0..220 step 10) {
            val a = a0 + sweep * v / 220.0
            val major = v % 20 == 0
            line.shader = null; line.strokeCap = Paint.Cap.ROUND
            line.color = if (speed != null && v <= speed) theme.accent else withAlpha(theme.text, if (major) 230 else 120)
            line.strokeWidth = (if (major) 5f else 2f) * s
            val outer = dr
            val inner = dr - (if (major) 30f else 16f) * s
            canvas.drawLine(sx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, sx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner, line)
            if (major) {
                type(.026f * h, theme.text, 600, Paint.Align.CENTER)
                val tr = dr - 54f * s
                canvas.drawText(v.toString(), sx + cos(a).toFloat() * tr, cy + sin(a).toFloat() * tr + .009f * h, text)
            }
        }
        // A sweep of colour along the rim up to the current speed.
        val rim = RectF(sx - dr - 10f * s, cy - dr - 10f * s, sx + dr + 10f * s, cy + dr + 10f * s)
        line.color = withAlpha(theme.accent, 70); line.strokeWidth = 6f * s; line.strokeCap = Paint.Cap.BUTT
        canvas.drawArc(rim, 150f, 240f, false, line)
        if (speed != null) { line.color = theme.accent; canvas.drawArc(rim, 150f, 240f * (speed / 220f).coerceIn(0f, 1f), false, line) }
        line.strokeCap = Paint.Cap.ROUND
        needle(canvas, sx, cy, dr - 34f * s, a0 + sweep * ((speed ?: 0f).coerceIn(0f, 220f) / 220f))
        type(.11f * h, theme.text, 700, Paint.Align.CENTER)
        canvas.drawText(speed?.roundToInt()?.toString() ?: "—", sx, cy + .2f * h, text)
        type(.022f * h, theme.secondary, 600, Paint.Align.CENTER, .2f); canvas.drawText("KM/H", sx, cy + .245f * h, text)

        // Power dial: needle up at zero, left for charge, right for power.
        val px = .875f * w
        val kw = kwNow()
        val f = ((kw ?: 0f) / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
        val photo = if (theme.speed == ClusterTheme.Speed.DOG_TAG && !preview) ClusterThemeStore.coPilot(context) else null
        if (theme.speed == ClusterTheme.Speed.DOG_TAG) {
            val pr = dr * .62f
            if (photo != null) {
                canvas.save(); path.reset(); path.addCircle(px, cy, pr, Path.Direction.CW); canvas.clipPath(path)
                rect.set(px - pr, cy - pr, px + pr, cy + pr); canvas.drawBitmap(photo, null, rect, art); canvas.restore()
            } else pugFace(canvas, px, cy, pr * 2f)
            line.shader = null; line.color = theme.edge; line.strokeWidth = 4f * s; canvas.drawCircle(px, cy, pr, line)
        }
        for (i in -10..10) {
            val a = Math.toRadians(270.0 + 11.0 * i)
            val major = i % 5 == 0
            line.color = when {
                kw != null && i > 0 && i <= f * 10 -> theme.accent
                kw != null && i < 0 && i >= f * 10 -> theme.charge
                else -> withAlpha(theme.text, if (major) 230 else 120)
            }
            line.strokeWidth = (if (major) 5f else 2.5f) * s
            val inner = dr - (if (major) 30f else 18f) * s
            canvas.drawLine(px + cos(a).toFloat() * dr, cy + sin(a).toFloat() * dr, px + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner, line)
        }
        needle(canvas, px, cy, dr - 30f * s, Math.toRadians(270.0 + 110.0 * f))
        type(.02f * h, theme.charge, 700, Paint.Align.CENTER, .12f)
        canvas.drawText("CHG", px + cos(Math.toRadians(150.0)).toFloat() * (dr - 46f * s), cy + sin(Math.toRadians(150.0)).toFloat() * (dr - 46f * s), text)
        type(.02f * h, theme.accent, 700, Paint.Align.CENTER, .12f)
        canvas.drawText("PWR", px + cos(Math.toRadians(30.0)).toFloat() * (dr - 46f * s), cy + sin(Math.toRadians(30.0)).toFloat() * (dr - 46f * s), text)
        type(.034f * h, kwColor(kw), 700, Paint.Align.CENTER)
        canvas.drawText(kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", px, cy + .23f * h, text)

        // Fuel under the speedometer and odometer under the power dial, on the bottom line below
        // the car's own icons.
        val row = .962f * h
        drawPump(canvas, .025f * w, row - .03f * h, .03f * h)
        val bl = .025f * w + .045f * h
        val bw = .07f * w
        rect.set(bl, row - .018f * h, bl + bw, row - .006f * h); fill.shader = null; fill.color = withAlpha(theme.text, 46)
        canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        data?.fuelPercent?.let { fuel ->
            rect.right = bl + bw * (fuel / 100f).coerceIn(.03f, 1f)
            fill.color = if (fuel < 15f) Color.rgb(255, 159, 10) else theme.charge; canvas.drawRoundRect(rect, 4f * s, 4f * s, fill)
        }
        type(.03f * h, theme.text, 600)
        canvas.drawText(listOfNotNull(data?.fuelPercent?.let { "${it.roundToInt()}%" }, data?.rangeKm?.let { "${it.roundToInt()} km" })
            .joinToString("  ").ifEmpty { "—" }, bl + bw + .01f * w, row, text)
        data?.odometerKm?.let {
            type(.028f * h, theme.secondary, 500, Paint.Align.RIGHT)
            canvas.drawText("ODO  " + NumberFormat.getIntegerInstance().format(it.roundToInt()) + " km", .975f * w, row, text)
        }
        drawHeader(canvas, w, h)
        if (showMusic) studioMusic(canvas, RectF(.78f * w, .77f * h, .97f * w, .84f * h), false)
    }

    // Studio layouts deliberately share only data helpers, not their instrument geometry.
    // All use already-tested negotiated windows; no full-width video is cropped into a split.
    private fun studioPanel(canvas: Canvas, w: Float, h: Float, centre: Boolean = false) {
        fill.shader = null; fill.color = Color.rgb(Color.red(theme.surface), Color.green(theme.surface), Color.blue(theme.surface))
        if (centre) {
            canvas.drawRect(0f, 0f, .25f * w, h, fill)
            canvas.drawRect(.75f * w, 0f, w, h, fill)
        } else canvas.drawRect(0f, 0f, .5f * w, h, fill)
    }

    private fun studioText(canvas: Canvas, value: String, x: Float, y: Float, size: Float,
        color: Int = theme.text, weight: Int = 500, width: Float = Float.MAX_VALUE,
        align: Paint.Align = Paint.Align.LEFT) {
        type(size, color, weight, align)
        canvas.drawText(fit(value, width), x, y, text)
    }

    /** Shared real fuel/odometer row below the H7's immovable telltales. */
    private fun studioFooter(canvas: Canvas, w: Float, h: Float, centre: Boolean = false) {
        val data = snapshot
        val fuel = data?.fuelPercent?.let { "${it.roundToInt()}%" } ?: "—"
        val range = data?.rangeKm?.let { "${it.roundToInt()} km" } ?: "— km"
        drawPump(canvas, .024f * w, .935f * h, .028f * h)
        studioText(canvas, "$fuel  $range", .046f * w, .962f * h, .027f * h,
            width = (if (centre) .19f else .21f) * w)
        val odo = data?.odometerKm?.let { NumberFormat.getIntegerInstance().format(it.roundToInt()) } ?: "—"
        studioText(canvas, "ODO  $odo km", (if (centre) .975f else .438f) * w, .962f * h,
            .024f * h, theme.secondary, width = .18f * w, align = Paint.Align.RIGHT)
    }

    /** Bipolar power: only the side of zero matching the signed battery reading is lit. */
    private fun studioFlow(canvas: Canvas, box: RectF, segments: Boolean) {
        val kw = kwNow()
        val f = kw?.let { (it / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f) }
        val mid = box.centerX()
        fill.shader = null
        if (segments) {
            val count = 36
            val step = box.width() / count
            for (i in 0 until count) {
                val position = (i + .5f) / count * 2f - 1f
                val lit = f != null && if (f >= 0f) position > 0f && position <= f else position < 0f && position >= f
                fill.color = if (lit) { if (position < 0f) theme.charge else theme.accent } else withAlpha(theme.text, 35)
                canvas.drawRect(box.left + i * step, box.top, box.left + (i + .72f) * step, box.bottom, fill)
            }
        } else {
            fill.color = withAlpha(theme.text, 35); canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, fill)
            f?.let {
                fill.color = kwColor(kw)
                val end = mid + box.width() / 2f * it
                canvas.drawRoundRect(RectF(minOf(mid, end), box.top, maxOf(mid, end), box.bottom), box.height() / 2f, box.height() / 2f, fill)
            }
        }
        fill.color = theme.text
        canvas.drawRect(mid - height / 720f, box.top - 3f * height / 720f, mid + height / 720f, box.bottom + 3f * height / 720f, fill)
    }

    /** Artwork is centre-cropped, never stretched. The empty state has no invented playback data. */
    private fun studioAlbum(canvas: Canvas, box: RectF, radius: Float) {
        val bitmap = music.artwork?.takeUnless(Bitmap::isRecycled)
        canvas.save()
        val clip = Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        fill.shader = null; fill.color = withAlpha(theme.accent, 35); canvas.drawRect(box, fill)
        if (bitmap != null) {
            val scale = maxOf(box.width() / bitmap.width, box.height() / bitmap.height)
            val bw = bitmap.width * scale
            val bh = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF(box.centerX() - bw / 2f, box.centerY() - bh / 2f,
                box.centerX() + bw / 2f, box.centerY() + bh / 2f), art)
        } else {
            studioText(canvas, "♪", box.centerX(), box.centerY() + box.height() * .16f,
                box.height() * .55f, theme.accent, align = Paint.Align.CENTER)
        }
        canvas.restore()
    }

    private fun studioMusic(canvas: Canvas, box: RectF, large: Boolean) {
        val h = height.toFloat()
        val side = minOf(box.height(), box.width() * (if (large) .48f else .25f))
        studioAlbum(canvas, RectF(box.left, box.top, box.left + side, box.top + side), .014f * h)
        val x = box.left + side + .025f * h
        val tw = (box.right - x).coerceAtLeast(1f)
        val title = music.info.title?.takeIf { it.isNotBlank() } ?: "—"
        studioText(canvas, title, x, box.top + side * .38f, minOf((if (large) .043f else .034f) * h, side * .30f),
            weight = 700, width = tw)
        music.info.artist?.takeIf { it.isNotBlank() }?.let {
            studioText(canvas, it, x, box.top + side * .70f, minOf(.027f * h, side * .23f), theme.secondary, width = tw)
        }
        progress()?.let { done ->
            val y = box.top + side * .88f
            fill.shader = null; fill.color = withAlpha(theme.text, 40)
            canvas.drawRoundRect(RectF(x, y, box.right, y + .007f * h), .0035f * h, .0035f * h, fill)
            fill.color = theme.accent
            canvas.drawRoundRect(RectF(x, y, x + tw * done, y + .007f * h), .0035f * h, .0035f * h, fill)
        }
    }

    private fun studioRpm(canvas: Canvas, box: RectF) {
        val rpm = snapshot?.engineRpm
        val h = height.toFloat()
        studioText(canvas, "ENGINE RPM", box.left, box.top, .018f * h, theme.secondary)
        studioText(canvas, rpm?.roundToInt()?.toString() ?: "—", box.right, box.top,
            .024f * h, theme.text, align = Paint.Align.RIGHT)
        val step = box.width() / 30
        fill.shader = null
        for (i in 0 until 30) {
            fill.color = if (rpm != null && (i + .5f) / 30 <= (rpm / 6000f).coerceIn(0f, 1f)) theme.accent else withAlpha(theme.text, 30)
            canvas.drawRect(box.left + i * step, box.top + .016f * h,
                box.left + (i + .76f) * step, box.bottom, fill)
        }
    }

    private fun drawHybridSport(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        studioPanel(canvas, w, h)
        val s = h / 720f
        // A clipped shoulder and vertical speed ladder give this its own silhouette.
        val shoulder = Path().apply {
            moveTo(.025f * w, .15f * h); lineTo(.465f * w, .15f * h)
            lineTo(.435f * w, .60f * h); lineTo(.025f * w, .60f * h); close()
        }
        fill.color = withAlpha(theme.accent, 15); canvas.drawPath(shoulder, fill)
        line.shader = null; line.strokeWidth = 2f * s; line.color = theme.edge; canvas.drawPath(shoulder, line)
        val speed = snapshot?.speedKph
        for (i in 0..10) {
            val y = (.20f + i * .032f) * h
            line.color = if (speed != null && speed >= (10 - i) * 20) theme.accent else withAlpha(theme.text, 45)
            line.strokeWidth = 3f * s; canvas.drawLine(.038f * w, y, .053f * w, y, line)
        }
        studioText(canvas, speed?.roundToInt()?.toString() ?: "—", .078f * w, .475f * h, .28f * h, weight = 800)
        studioText(canvas, "km/h", .335f * w, .475f * h, .032f * h, theme.secondary)
        studioText(canvas, "BATTERY FLOW", .065f * w, .565f * h, .022f * h, theme.secondary)
        val kw = kwNow()
        studioText(canvas, kw?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", .435f * w,
            .565f * h, .032f * h, kwColor(kw), align = Paint.Align.RIGHT)
        studioFlow(canvas, RectF(.065f * w, .61f * h, .435f * w, .635f * h), true)
        studioText(canvas, "CHARGE", .065f * w, .675f * h, .018f * h, theme.charge)
        studioText(canvas, "DRAW", .435f * w, .675f * h, .018f * h, theme.accent, align = Paint.Align.RIGHT)
        if (showMusic) studioMusic(canvas, RectF(.065f * w, .705f * h, .435f * w, .795f * h), false)
        studioRpm(canvas, RectF(.065f * w, .125f * h, .435f * w, .16f * h))
        drawHeader(canvas, w, h)
        studioFooter(canvas, w, h)
    }

    private fun drawVaporwaveStereo(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        studioPanel(canvas, w, h)
        val s = h / 720f
        // Flat LCD cells and a cassette deck, with no analogue gauges or sport geometry.
        studioRpm(canvas, RectF(.035f * w, .135f * h, .465f * w, .17f * h))
        val speedBox = RectF(.035f * w, .19f * h, .255f * w, .49f * h)
        panel(canvas, speedBox, .012f * h)
        segmentDigits(canvas, snapshot?.speedKph?.roundToInt()?.toString() ?: "—", .14f * w, .20f * h, .22f * h)
        studioText(canvas, "km/h", .14f * w, .475f * h, .025f * h, theme.secondary, align = Paint.Align.CENTER)
        studioText(canvas, "BATTERY FLOW", .28f * w, .205f * h, .020f * h, theme.secondary)
        studioText(canvas, kwNow()?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—", .28f * w,
            .28f * h, .037f * h, kwColor(kwNow()))
        studioFlow(canvas, RectF(.28f * w, .33f * h, .465f * w, .36f * h), true)
        studioText(canvas, "CHG / DRAW", .28f * w, .415f * h, .019f * h, theme.charge)
        val deck = RectF(.035f * w, .53f * h, .465f * w, .79f * h)
        panel(canvas, deck, .018f * h)
        if (showMusic) studioMusic(canvas, RectF(deck.left + .018f * w, deck.top + .025f * h,
            deck.right - .018f * w, deck.bottom - .025f * h), false)
        line.color = theme.accent; line.strokeWidth = 2f * s; line.shader = null
        canvas.drawLine(.035f * w, .815f * h, .465f * w, .815f * h, line)
        drawHeader(canvas, w, h)
        studioFooter(canvas, w, h)
    }

    private fun drawMusicLounge(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        studioPanel(canvas, w, h)
        // Speed stays prominent while album art is the main lower-panel feature.
        studioText(canvas, snapshot?.speedKph?.roundToInt()?.toString() ?: "—", .035f * w,
            .31f * h, .18f * h, weight = 300)
        studioText(canvas, "km/h", .20f * w, .30f * h, .028f * h, theme.secondary)
        studioText(canvas, kwNow()?.let { GwmAndroidAutoClusterView.formatKw(it) } ?: "—",
            .465f * w, .24f * h, .037f * h, kwColor(kwNow()), align = Paint.Align.RIGHT)
        studioText(canvas, "BATTERY FLOW", .465f * w, .295f * h, .018f * h, theme.secondary, align = Paint.Align.RIGHT)
        if (showMusic) studioMusic(canvas, RectF(.035f * w, .375f * h, .465f * w, .78f * h), true)
        drawHeader(canvas, w, h)
        studioFooter(canvas, w, h)
    }

    /** Metallic radial instrument shared by the turbine and performance concepts. */
    private fun conceptDial(canvas: Canvas, cx: Float, cy: Float, r: Float, value: Float?, maximum: Float,
                            turbine: Boolean = false) {
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(Color.rgb(7,12,19), Color.rgb(24,34,45), Color.rgb(5,10,17)),
            floatArrayOf(0f,.86f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,r,fill); fill.shader=null
        line.shader=null; line.strokeWidth=r*.012f; line.color=Color.rgb(205,214,225)
        canvas.drawCircle(cx,cy,r,line)
        if (turbine) for (i in 0 until 36) {
            val a=i*Math.PI/18
            val b=a+.23
            line.color=Color.argb(90,113,140,162);line.strokeWidth=r*.018f
            canvas.drawLine(cx+(cos(a)*r*.46).toFloat(),cy+(sin(a)*r*.46).toFloat(),
                cx+(cos(b)*r*.94).toFloat(),cy+(sin(b)*r*.94).toFloat(),line)
        }
        for(i in 0..40) {
            val a=Math.toRadians(140.0+i*6.5)
            val major=i%5==0
            line.color=if(major) theme.text else withAlpha(theme.text,105)
            line.strokeWidth=r*(if(major) .014f else .006f)
            val inner=if(major) .83f else .89f
            canvas.drawLine(cx+(cos(a)*r*inner).toFloat(),cy+(sin(a)*r*inner).toFloat(),
                cx+(cos(a)*r*.95).toFloat(),cy+(sin(a)*r*.95).toFloat(),line)
            if(major) studioText(canvas, (maximum*i/40).roundToInt().toString(),
                cx+(cos(a)*r*.72).toFloat(),cy+(sin(a)*r*.72).toFloat()+r*.035f,r*.10f,
                align=Paint.Align.CENTER)
        }
        value?.let {
            val a=Math.toRadians(140.0+(it/maximum).coerceIn(0f,1f)*260)
            line.color=theme.accent;line.strokeWidth=r*.018f
            canvas.drawLine(cx,cy,cx+(cos(a)*r*.81).toFloat(),cy+(sin(a)*r*.81).toFloat(),line)
        }
        fill.color=theme.text;canvas.drawCircle(cx,cy,r*.035f,fill)
    }

    private fun drawTurbineLuxury(canvas: Canvas,w:Float,h:Float) {
        val r=.38f*h
        conceptDial(canvas,.17f*w,.46f*h,r,snapshot?.speedKph,240f,true)
        conceptDial(canvas,.83f*w,.46f*h,r,kwNow()?.let { (it+60f).coerceIn(0f,120f) },120f,true)
        for(right in listOf(false,true)) {
            val x=if(right) .83f*w else .17f*w
            fill.color=Color.rgb(7,12,19);canvas.drawCircle(x,.46f*h,r*.40f,fill)
            studioText(canvas,if(right) kwNow()?.roundToInt()?.toString() ?: "—" else snapshot?.speedKph?.roundToInt()?.toString() ?: "—",
                x,.49f*h,.12f*h,weight=400,align=Paint.Align.CENTER)
            studioText(canvas,if(right) "kW" else "km/h",x,.55f*h,.026f*h,theme.secondary,align=Paint.Align.CENTER)
        }
        drawHeader(canvas,w,h);studioFooter(canvas,w,h,true)
    }

    private fun drawPerformanceCockpit(canvas:Canvas,w:Float,h:Float) {
        fill.color=Color.rgb(10,12,15);canvas.drawRect(0f,0f,.65f*w,h,fill)
        conceptDial(canvas,.465f*w,.46f*h,.39f*h,snapshot?.engineRpm,8000f)
        studioText(canvas,snapshot?.speedKph?.roundToInt()?.toString() ?: "—",.13f*w,.43f*h,.20f*h,weight=500,align=Paint.Align.CENTER)
        studioText(canvas,"km/h",.13f*w,.49f*h,.033f*h,theme.secondary,align=Paint.Align.CENTER)
        studioFlow(canvas,RectF(.035f*w,.62f*h,.25f*w,.68f*h),false)
        studioText(canvas,snapshot?.engineRpm?.roundToInt()?.toString() ?: "—",.465f*w,.70f*h,.055f*h,align=Paint.Align.CENTER)
        drawHeader(canvas,w,h);studioFooter(canvas,w,h,true)
    }

    private fun drawMusicPanorama(canvas:Canvas,w:Float,h:Float) {
        fill.color=Color.rgb(7,25,31);canvas.drawRect(0f,0f,.62f*w,h,fill)
        fill.shader=LinearGradient(.54f*w,0f,.62f*w,0f,Color.TRANSPARENT,Color.argb(80,0,0,0),Shader.TileMode.CLAMP)
        canvas.drawRect(.54f*w,0f,.62f*w,h,fill);fill.shader=null
        studioText(canvas,snapshot?.speedKph?.roundToInt()?.toString() ?: "—",.035f*w,.47f*h,.20f*h,weight=300)
        studioText(canvas,"km/h",.045f*w,.53f*h,.03f*h,theme.secondary)
        studioMusic(canvas,RectF(.20f*w,.26f*h,.59f*w,.68f*h),true)
        drawHeader(canvas,w,h);studioFooter(canvas,w,h,true)
    }

    private fun drawDaylightFuture(canvas:Canvas,w:Float,h:Float) {
        // Pale instruments fade into the real projected map; no fabricated road imagery.
        fill.shader=LinearGradient(0f,0f,w,0f,intArrayOf(Color.rgb(243,246,247),Color.rgb(243,246,247),
            Color.TRANSPARENT,Color.TRANSPARENT,Color.rgb(243,246,247),Color.rgb(243,246,247)),
            floatArrayOf(0f,.20f,.34f,.66f,.80f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,fill);fill.shader=null
        studioText(canvas,snapshot?.speedKph?.roundToInt()?.toString() ?: "—",.15f*w,.48f*h,.23f*h,weight=500,align=Paint.Align.CENTER)
        studioText(canvas,"km/h",.15f*w,.55f*h,.035f*h,theme.secondary,align=Paint.Align.CENTER)
        studioText(canvas,snapshot?.rangeKm?.roundToInt()?.toString() ?: "—",.85f*w,.48f*h,.15f*h,weight=500,align=Paint.Align.CENTER)
        studioText(canvas,"km",.85f*w,.55f*h,.035f*h,theme.secondary,align=Paint.Align.CENTER)
        studioFlow(canvas,RectF(.78f*w,.65f*h,.96f*w,.70f*h),true)
        drawHeader(canvas,w,h);studioFooter(canvas,w,h,true)
    }

    // ---- Isar Drive: chevron instruments over a full-width map ------------------------------
    // Speed on the left, battery flow on the right, each a chevron pointing outwards with its own
    // scale, in the style of modern German clusters. No badge or logo. The instruments use the
    // full height and run under the car's own symbols, which the car draws on top.

    private val bavTop = .025f
    private val bavTip = .47f
    private val bavBottom = .965f
    private val bavSpeedMarks = floatArrayOf(0f, 30f, 60f, 100f, 150f, 200f, 260f)
    private val bavKwFull = 60f
    /** Everything that does not move, drawn once per size so a data update only repaints the readings. */
    private var bavBase: Bitmap? = null

    private fun bavChevron(y: Float, tipX: Float, slope: Float) = tipX + slope * kotlin.math.abs(y - bavTip)
    private fun bavRail(y: Float) = bavChevron(y, .015f, .30f)
    private fun bavScale(y: Float) = bavChevron(y, .107f, .30f)
    /** The well's far side points the other way, so each well is a long hexagon. */
    private fun bavWellEnd(y: Float) = .35f - .22f * kotlin.math.abs(y - bavTip)
    private fun bavY(t: Float) = bavBottom - (bavBottom - bavTop) * t
    private fun bavX(right: Boolean, x: Float, w: Float) = (if (right) 1f - x else x) * w
    private fun bavPath(right: Boolean, w: Float, h: Float, from: Float, to: Float, x: (Float) -> Float) = Path().apply {
        moveTo(bavX(right, x(from), w), from * h); lineTo(bavX(right, x(bavTip), w), bavTip * h)
        lineTo(bavX(right, x(to), w), to * h)
    }
    private fun bavSpeedT(v: Float): Float {
        val c = v.coerceIn(0f, bavSpeedMarks.last())
        for (i in 1 until bavSpeedMarks.size) if (c <= bavSpeedMarks[i])
            return (i - 1 + (c - bavSpeedMarks[i - 1]) / (bavSpeedMarks[i] - bavSpeedMarks[i - 1])) / (bavSpeedMarks.size - 1)
        return 1f
    }
    private fun bavKwT(kw: Float) = (kw.coerceIn(-bavKwFull, bavKwFull) + bavKwFull) / (2f * bavKwFull)
    private fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun drawBavarian(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        val base = bavBase?.takeIf { it.width == w.toInt() && it.height == h.toInt() }
            ?: Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).also {
                bavBase?.recycle()
                bavBase = it
                drawBavarianBase(Canvas(it), w, h)
            }
        canvas.drawBitmap(base, 0f, 0f, null)

        // Lit band from the scale's zero to the reading, brightest at the reading.
        for (right in listOf(false, true)) {
            val reading = if (right) kwNow() else snapshot?.speedKph
            val from = if (right) bavKwT(0f) else 0f
            val to = reading?.let { if (right) bavKwT(it) else bavSpeedT(it) } ?: from
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            if (hi <= lo) continue
            val colour = if (right) kwColor(reading) else theme.accent
            val blocks = 56
            val path = Path()
            for (i in 0 until blocks) {
                val t0 = maxOf(i / blocks.toFloat(), lo)
                val t1 = minOf((i + .78f) / blocks, hi)
                if (t1 <= t0) continue
                val mid = ((t0 + t1) / 2f - lo) / (hi - lo)
                val progress = if (to < from) 1f - mid else mid
                val ya = bavY(t0)
                val yb = bavY(t1)
                path.rewind()
                path.moveTo(bavX(right, bavRail(ya) + .028f, w), ya * h); path.lineTo(bavX(right, bavScale(ya) - .014f, w), ya * h)
                path.lineTo(bavX(right, bavScale(yb) - .014f, w), yb * h); path.lineTo(bavX(right, bavRail(yb) + .028f, w), yb * h)
                path.close()
                fill.shader = null
                fill.color = alpha(colour, (80 + 175 * progress).roundToInt())
                canvas.drawPath(path, fill)
            }
        }

        // Readings in the wells.
        studioText(canvas, snapshot?.speedKph?.roundToInt()?.toString() ?: "—", .238f * w, .55f * h, .21f * h,
            weight = 500, align = Paint.Align.CENTER)
        studioText(canvas, "km/h", .238f * w, .635f * h, .04f * h, theme.secondary, 400, align = Paint.Align.CENTER)
        val kw = kwNow()?.roundToInt()
        studioText(canvas, kw?.let { if (it < 0) "−${-it}" else "$it" } ?: "—", .762f * w, .55f * h, .21f * h,
            weight = 500, width = .17f * w, align = Paint.Align.CENTER)
        studioText(canvas, "kW", .762f * w, .635f * h, .04f * h, theme.secondary, 400, align = Paint.Align.CENTER)

        // Temperature and time in the top corners.
        snapshot?.outsideTempC?.let {
            studioText(canvas, "${it.roundToInt()}°C", .03f * w, .075f * h, .036f * h, weight = 500)
        }
        studioText(canvas, SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), .97f * w, .075f * h, .036f * h,
            weight = 500, align = Paint.Align.RIGHT)

        // Fuel as slanted segments, then the range, bottom right.
        val lit = snapshot?.fuelPercent?.let { (it / 100f * 8f).roundToInt().coerceIn(0, 8) } ?: 0
        val step = .0105f * w
        val bar = Path()
        for (i in 0 until 8) {
            val x = .815f * w + i * step
            bar.rewind()
            bar.moveTo(x + .003f * w, .93f * h); bar.lineTo(x + step - .0015f * w, .93f * h)
            bar.lineTo(x + step - .0045f * w, .956f * h); bar.lineTo(x, .956f * h); bar.close()
            fill.color = if (i < lit) theme.accent else Color.argb(70, 170, 190, 210)
            canvas.drawPath(bar, fill)
        }
        studioText(canvas, snapshot?.rangeKm?.let { "${it.roundToInt()} km" } ?: "— km", .975f * w, .958f * h,
            .036f * h, weight = 500, align = Paint.Align.RIGHT)

        if (showMusic) studioMusic(canvas, RectF(.025f * w, .915f * h, .33f * w, .99f * h), false)
    }

    private fun drawBavarianBase(canvas: Canvas, w: Float, h: Float) {
        val s = h / 720f
        val ink = theme.surface
        // Shade the map's edges so the instruments read; the middle stays clear. This also covers
        // the small "Google Maps" label and recentre button Android Auto prints on the right.
        fill.color = Color.BLACK
        for (right in listOf(false, true)) {
            val a = if (right) w else 0f
            val b = if (right) .63f * w else .37f * w
            fill.shader = LinearGradient(a, 0f, b, 0f, intArrayOf(alpha(ink, 245), alpha(ink, 225), alpha(ink, 0)),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(minOf(a, b), 0f, maxOf(a, b), h, fill)
        }
        fill.shader = LinearGradient(0f, 0f, 0f, .14f * h, intArrayOf(alpha(ink, 210), alpha(ink, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, .14f * h, fill)
        fill.shader = LinearGradient(0f, .84f * h, 0f, h, intArrayOf(alpha(ink, 0), alpha(ink, 235)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, .84f * h, w, h, fill)
        fill.shader = null
        val oldJoin = line.strokeJoin
        line.strokeJoin = Paint.Join.MITER

        for (right in listOf(false, true)) {
            // Dark well holding the reading.
            val well = Path().apply {
                moveTo(bavX(right, bavScale(bavTop) + .03f, w), bavTop * h)
                lineTo(bavX(right, bavScale(bavTip) + .022f, w), bavTip * h)
                lineTo(bavX(right, bavScale(bavBottom) + .03f, w), bavBottom * h)
                lineTo(bavX(right, bavWellEnd(bavBottom), w), bavBottom * h)
                lineTo(bavX(right, bavWellEnd(bavTip), w), bavTip * h)
                lineTo(bavX(right, bavWellEnd(bavTop), w), bavTop * h)
                close()
            }
            fill.shader = LinearGradient(bavX(right, .13f, w), 0f, bavX(right, .35f, w), 0f,
                intArrayOf(Color.argb(235, 3, 7, 13), Color.argb(175, 3, 7, 13), Color.argb(20, 3, 7, 13)),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(well, fill); fill.shader = null
            line.shader = null; line.color = Color.argb(120, 165, 200, 235); line.strokeWidth = 1.5f * s
            canvas.drawPath(bavPath(right, w, h, bavTop, bavBottom) { bavScale(it) + if (it == bavTip) .022f else .03f }, line)
            line.color = Color.argb(45, 165, 200, 235)
            canvas.drawPath(bavPath(right, w, h, bavTop, bavBottom, ::bavWellEnd), line)

            // Soft, faceted diagonal accent inside each instrument: a premium glass bevel,
            // not a solid panel, so the projected map remains visible through it.
            val bevel = Path().apply {
                moveTo(bavX(right, .155f, w), .43f * h)
                lineTo(bavX(right, .19f, w), .37f * h)
                lineTo(bavX(right, .30f, w), .75f * h)
                lineTo(bavX(right, .25f, w), .75f * h)
                close()
            }
            fill.shader = LinearGradient(bavX(right, .17f, w), .4f * h,
                bavX(right, .29f, w), .75f * h,
                intArrayOf(Color.TRANSPARENT, alpha(if (right) theme.charge else theme.accent, 90),
                    Color.argb(160, 213, 232, 242)), floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(bevel, fill)
            fill.shader = null

            // Ticks and figures along the scale.
            val align = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            for (i in 0..24) {
                val t = i / 24f
                val y = bavY(t)
                val major = if (right) i % 6 == 0 else i % 4 == 0
                line.color = if (major) Color.argb(235, 226, 236, 246) else Color.argb(110, 200, 215, 232)
                line.strokeWidth = (if (major) 2.2f else 1.2f) * s
                val x1 = bavScale(y) - .012f
                val x0 = x1 - if (major) .026f else .013f
                canvas.drawLine(bavX(right, x0, w), y * h, bavX(right, x1, w), y * h, line)
                if (major) {
                    val label = if (right) (-bavKwFull + 2f * bavKwFull * t).roundToInt().let { if (it < 0) "−${-it}" else "$it" }
                        else bavSpeedMarks[i / 4].roundToInt().toString()
                    studioText(canvas, label, bavX(right, bavScale(y) + .004f, w), y * h + .012f * h, .033f * h,
                        theme.secondary, 500, align = align)
                }
            }

            // Brushed-metal rail outside, with a fine highlight and an accent edge.
            line.color = Color.BLACK; line.strokeWidth = 16f * s
            line.shader = LinearGradient(0f, bavTop * h, 0f, bavBottom * h,
                intArrayOf(Color.rgb(64, 78, 94), Color.rgb(226, 233, 241), Color.rgb(118, 133, 150), Color.rgb(56, 68, 82)),
                floatArrayOf(0f, .45f, .62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(bavPath(right, w, h, bavTop - .01f, bavBottom + .01f, ::bavRail), line)
            line.shader = null
            line.color = Color.argb(210, 242, 246, 250); line.strokeWidth = 1.6f * s
            canvas.drawPath(bavPath(right, w, h, bavTop, bavBottom) { bavRail(it) + .008f }, line)
            line.color = alpha(theme.accent, 150); line.strokeWidth = 2.4f * s
            canvas.drawPath(bavPath(right, w, h, bavTop + .03f, bavBottom - .03f) { bavRail(it) - .011f }, line)
        }
        line.strokeJoin = oldJoin

        // Notch around the car's own gear letter.
        val g = GEAR_X * w
        val notch = Path().apply {
            moveTo(g - .09f * w, 0f); lineTo(g - .06f * w, .112f * h); lineTo(g + .06f * w, .112f * h); lineTo(g + .09f * w, 0f)
        }
        fill.color = alpha(ink, 235); canvas.drawPath(Path(notch).apply { close() }, fill)
        line.color = Color.argb(150, 190, 210, 230); line.strokeWidth = 1.5f * s; canvas.drawPath(notch, line)
        line.color = alpha(theme.accent, 200); line.strokeWidth = 2.5f * s
        canvas.drawLine(g - .02f * w, .112f * h, g + .02f * w, .112f * h, line)

        // Fine lines either side of the car's READY on the bottom line.
        for (side in listOf(-1f, 1f)) {
            val a = .5f * w + side * .085f * w
            val b = .5f * w + side * .2f * w
            line.shader = LinearGradient(a, 0f, b, 0f, alpha(theme.accent, 200), alpha(theme.accent, 0), Shader.TileMode.CLAMP)
            line.color = Color.BLACK; line.strokeWidth = 2f * s
            canvas.drawLine(a, .9f * h, b, .9f * h, line)
            line.shader = null
        }
    }


    // ---- CarPlay directions card ----------------------------------------------------------

    /**
     * Where Di-Connect's directions card goes on Android Auto: top centre under the clock, like the
     * car's own navigation card, or top centre of the map for a theme with its own map window.
     */
    private fun directionsBox(w: Float, h: Float, withJourney: Boolean): RectF {
        when (theme.layout) {
            ClusterTheme.Layout.TURBINE_LUXURY -> return RectF(.32f*w, .14f*h, .68f*w, (if(withJourney) .43f else .35f)*h)
            ClusterTheme.Layout.PERFORMANCE_COCKPIT -> return RectF(.67f*w, .60f*h, .98f*w, (if(withJourney) .89f else .81f)*h)
            ClusterTheme.Layout.MUSIC_PANORAMA -> return RectF(.65f*w, .18f*h, .97f*w, (if(withJourney) .47f else .39f)*h)
            ClusterTheme.Layout.DAYLIGHT_FUTURE -> return RectF(.29f*w, .58f*h, .72f*w, (if(withJourney) .87f else .79f)*h)
            else -> Unit
        }

        // Isar Drive: a little lower, clear of the gear notch.
        if (theme.layout == ClusterTheme.Layout.BAVARIAN)
            return RectF(.37f * w, .14f * h, .63f * w, (if (withJourney) .43f else .35f) * h)
        // Music Lounge keeps its guidance on the map side, away from the music instruments.
        if (theme.layout == ClusterTheme.Layout.MUSIC_LOUNGE)
            return RectF(.69f * w, (if (withJourney) .59f else .67f) * h, .97f * w, .88f * h)
        val (from, to) = theme.mapWindow ?: (0f to 1f)
        val centre = (from + to) / 2f * w
        val half = .135f * w
        // Taller only when the phone sent journey details (time left, distance, arrival).
        return RectF(centre - half, .12f * h, centre + half, (if (withJourney) .41f else .33f) * h)
    }

    /** "12min · 9.7 km · Arrive 2:54 pm" from whatever journey details the phone sent, or null. */
    private fun journeyLine(next: ClusterTurnGuidance): String? {
        val parts = mutableListOf<String>()
        next.remainingSeconds?.takeIf { it > 0 }?.let { seconds ->
            val hours = (seconds / 3600).toInt()
            val minutes = ((seconds % 3600) / 60).toInt()
            parts += if (hours > 0) context.getString(R.string.turn_card_info_duration_hm, hours, minutes)
                else context.getString(R.string.turn_card_info_duration_m, minutes)
        }
        next.remainingMeters?.takeIf { it > 0 }?.let { parts += context.getString(R.string.turn_card_info_km, it / 1000f) }
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let {
            parts += context.getString(R.string.turn_card_info_arrival, SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it * 1000L)))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    private fun drawCard(canvas: Canvas, w: Float, h: Float, next: ClusterTurnGuidance,
                         box: RectF = RectF(CARD_LEFT * w, CARD_TOP * h, CARD_RIGHT * w, CARD_BOTTOM * h)) {
        val s = h / 720f
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
        val image = turnImage?.takeUnless { it.isRecycled || NavDemo.active }
        if (image != null) {
            rect.set(gx, gy, gx + side, gy + side); canvas.drawBitmap(image, null, rect, art)
        } else {
            icon?.setBounds(gx.toInt(), gy.toInt(), (gx + side).toInt(), (gy + side).toInt())
            icon?.draw(canvas)
        }
        val textLeft = gx + side + .025f * h
        val textWidth = box.right - .03f * h - textLeft
        type(.055f * h, theme.text, 700)
        canvas.drawText(fit(distanceLabel(next.distanceMeters), textWidth), textLeft, gy + .06f * h, text)
        val road = if (next.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit, next.roundaboutExit) else next.road
        if (road.isNotEmpty()) {
            type(.032f * h, theme.text, 400)
            canvas.drawText(fit(road, textWidth), textLeft, gy + .11f * h, text)
        }
        // Android Auto sends no arrival time with the turn, so the short card stops at the road.
        if (box.height() < .25f * h) return
        fill.shader = null; fill.color = Color.argb(46, 255, 255, 255)
        canvas.drawRect(box.left + .03f * h, box.top + .19f * h, box.right - .03f * h, box.top + .19f * h + 1.2f * s, fill)
        journeyLine(next)?.let { info ->
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
        const val AA_LABEL_COVER = .775f
        private val FUEL_GOLD = Color.rgb(255, 196, 64)
        const val SIDEBAR_RIGHT = .205f
        const val DECK_TOP = .84f
        const val DECK_HUMP = .75f
        /** CarPlay only: where Google's card sits in Android Auto. */
        const val CARD_LEFT = .755f
        const val CARD_RIGHT = .985f
        const val CARD_TOP = .41f
        const val CARD_BOTTOM = .70f

        private val SEGMENTS = mapOf(
            '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
            '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g",
        )

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

