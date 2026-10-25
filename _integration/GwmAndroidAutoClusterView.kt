package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.roundToInt

/**
 * Android Auto-only GWM cluster skin: compact charcoal modules and an unobstructed map.
 * Derived from the CarPlay renderer to retain its live vehicle and media data lifecycle.
 * Google Maps owns the navigation card in the video; this view never draws another one.
 * Factory telltales are not redrawn or moved. Verify their clearances on the OFF-ROAD theme.
 */
internal class GwmAndroidAutoClusterView(context: Context) : View(context) {
    private var snapshot: GwmVehicleData.Snapshot? = null
    private var guidance: ClusterTurnGuidance? = null
    private var music = ClusterMusicState.Snapshot()
    private var musicReceivedAt = 0L
    private var stopMusic: (() -> Unit)? = null

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val secondary = Color.argb(178, 235, 240, 248)
    private val label = Color.argb(140, 235, 240, 248)
    /** The one accent, used sparingly: turn arrow, fuel level and music progress. */
    private val accent = Color.rgb(71, 222, 219)

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val fade = Paint()
    private val bandSheen = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val low = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 159, 10) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val glassRect = RectF()
    private val clip = Path()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Frosted dark band: mutes the map quickly so the lower row reads clearly.
        fade.shader = LinearGradient(0f, h * FADE_TOP, 0f, h.toFloat(),
            intArrayOf(Color.argb(0, 6, 10, 18), Color.argb(170, 6, 10, 18), Color.argb(212, 6, 10, 18), Color.argb(228, 6, 10, 18)),
            floatArrayOf(0f, 0.28f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bandSheen.shader = LinearGradient(0f, h * (FADE_TOP + 0.03f), 0f, h * (FADE_TOP + 0.09f),
            Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawLowerRow(canvas, w, h)
        drawHeaderPills(canvas, w, h)
        // Android Auto supplies its turn card in the phone video; never draw a duplicate.
        if (AirPlayPersistence.loadGwmMusicCard(context)) drawMusic(canvas, w, h)
    }

    // ---- glass ----------------------------------------------------------------------------

    /** Frosted card: soft shadow, translucent body, top sheen, hairline edge and top highlight. */
    private fun drawGlass(canvas: Canvas, box: RectF, radius: Float) {
        body.shader = null
        body.color = Color.rgb(17, 24, 32)
        canvas.drawRoundRect(box, radius, radius, body)
    }

    /** Small spaced capitals for labels (OUTSIDE, ODO, KM/H). */
    private fun labelPaint(size: Float) {
        text.typeface = medium
        text.textSize = size
        text.letterSpacing = 0.14f
        text.color = label
    }

    private fun valuePaint(size: Float, face: Typeface = regular) {
        text.typeface = face
        text.textSize = size
        text.letterSpacing = 0f
        text.color = Color.WHITE
    }

    /** One header surface behind the fixed factory gear; never redraw the gear itself. */
    private fun drawHeaderPills(canvas: Canvas, w: Float, h: Float) {
        val box = RectF(w * .405f, h * .012f, w * .655f, h * .104f)
        drawGlass(canvas, box, box.height() / 2f)
        val baseline = box.centerY() - run {
            valuePaint(h * .032f, medium)
            (text.ascent() + text.descent()) / 2f
        }
        text.textAlign = Paint.Align.LEFT
        val temperature = snapshot?.outsideTempC?.let {
            String.format(Locale.getDefault(), "%d°C", it.roundToInt())
        } ?: "—°C"
        canvas.drawText(temperature, w * .42f, baseline, text)
        text.textAlign = Paint.Align.RIGHT
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date())
        canvas.drawText(time, w * .64f, baseline, text)
    }

    /** Panels clear all factory telltales, including ECO at 79% and READY at the bottom centre. */
    private fun drawLowerRow(canvas: Canvas, w: Float, h: Float) {
        val data = snapshot ?: return
        val baseline = h * .89f
        val size = h * .035f
        data.speedKph?.let { speed ->
            valuePaint(h * .22f, medium)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(speed.roundToInt().toString(), w * .15f, h * .53f, text)
            valuePaint(h * .032f)
            canvas.drawText("km/h", w * .15f, h * .59f, text)
        }

        // No guessed adapter keys or simulated live power. A dash explicitly means unavailable.
        // Replace this state only after the H7's signed hybrid power signal is verified.
        val meter = RectF(w * .065f, h * .625f, w * .235f, h * .643f)
        drawGlass(canvas, RectF(meter.left - h*.012f, meter.top - h*.012f,
            meter.right + h*.012f, meter.bottom + h*.012f), h*.018f)
        canvas.drawRoundRect(meter, h*.009f, h*.009f, track)
        canvas.drawLine(meter.centerX(), meter.top-h*.004f, meter.centerX(),
            meter.bottom+h*.004f, stroke.apply { strokeWidth = h*.002f })
        valuePaint(h*.018f)
        text.color = secondary
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("CHARGE", meter.left, h*.683f, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("POWER", meter.right, h*.683f, text)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("—", meter.centerX(), h*.683f, text)

        val fuel = RectF(w*.23f, h*.835f, w*.465f, h*.91f)
        drawGlass(canvas, fuel, fuel.height()/2f)
        val icon = h*.038f
        val pumpX = fuel.left + h*.047f
        drawPump(canvas, pumpX, baseline-icon*.94f, icon)
        val triangle = Path().apply {
            moveTo(pumpX-h*.017f, baseline-icon*.50f)
            lineTo(pumpX-h*.007f, baseline-icon*.76f)
            lineTo(pumpX-h*.007f, baseline-icon*.24f)
            close()
        }
        canvas.drawPath(triangle, fill)
        val barLeft = pumpX+icon*1.15f
        val barWidth = w*.057f
        val barY = baseline-icon*.42f
        rect.set(barLeft, barY-h*.004f, barLeft+barWidth, barY+h*.004f)
        canvas.drawRoundRect(rect, h*.004f, h*.004f, track)
        data.fuelPercent?.let {
            rect.right=barLeft+barWidth*(it/100f).coerceIn(0f,1f)
            canvas.drawRoundRect(rect,h*.004f,h*.004f,if(it<15f) low else accentFill)
        }
        valuePaint(size)
        text.textAlign=Paint.Align.LEFT
        canvas.drawText(data.fuelPercent?.let { "${it.roundToInt()}%" } ?: "—%",
            barLeft+barWidth+w*.012f,baseline,text)
        text.textAlign=Paint.Align.RIGHT
        canvas.drawText(data.rangeKm?.let { "${it.roundToInt()} km" } ?: "— km",
            fuel.right-h*.025f,baseline,text)

        // The car owns the drive mode at x79%. Keep its area entirely clear.
        val odo = RectF(w*.835f,h*.835f,w*.988f,h*.91f)
        drawGlass(canvas,odo,odo.height()/2f)
        valuePaint(h*.030f)
        text.textAlign=Paint.Align.RIGHT
        canvas.drawText(data.odometerKm?.let {
            "ODO  ${NumberFormat.getIntegerInstance().format(it.roundToInt())} km"
        } ?: "ODO  — km",odo.right-h*.024f,baseline,text)
        text.letterSpacing=0f
    }

    // ---- navigation card ------------------------------------------------------------------

    private fun drawNavigation(canvas: Canvas, next: ClusterTurnGuidance, w: Float, h: Float) {
        val cw = w * 0.24f
        val ch = h * 0.205f
        val topLeft = AirPlayPersistence.loadGwmCardAnchor(context) == 1
        val left = if (topLeft) w * 0.06f else (w - cw) / 2f
        val top = if (topLeft) h * 0.13f else h * 0.105f
        rect.set(left, top, left + cw, top + ch)
        val box = RectF(rect)
        drawGlass(canvas, box, ch * 0.22f)

        val res = clusterTurnGlyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(accent) }
            glyphRes = res
        }
        val side = ch * 0.40f
        val gx = left + ch * 0.13f
        val gy = top + ch * 0.11f
        glyph?.setBounds(gx.toInt(), gy.toInt(), (gx + side).toInt(), (gy + side).toInt())
        glyph?.draw(canvas)

        val textLeft = left + ch * 0.68f
        val textWidth = left + cw - ch * 0.12f - textLeft
        text.textAlign = Paint.Align.LEFT
        valuePaint(ch * 0.205f, medium)
        canvas.drawText(fit(distanceLabel(next.distanceMeters), textWidth), textLeft, top + ch * 0.31f, text)
        valuePaint(ch * 0.15f, light)
        val road = if (next.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit, next.roundaboutExit) else next.road
        if (road.isNotEmpty()) canvas.drawText(fit(road, textWidth), textLeft, top + ch * 0.53f, text)

        canvas.drawRect(left + ch * 0.13f, top + ch * 0.655f, left + cw - ch * 0.13f, top + ch * 0.655f + 1.2f, hairline)
        val info = infoParts(next).joinToString("   ·   ")
        if (info.isNotEmpty()) {
            valuePaint(ch * 0.128f)
            text.color = secondary
            canvas.drawText(fit(info, left + cw - ch * 0.13f - (left + ch * 0.13f)), left + ch * 0.13f, top + ch * 0.86f, text)
            text.color = Color.WHITE
        }
    }

    // ---- now playing ----------------------------------------------------------------------

    private fun drawMusic(canvas: Canvas, w: Float, h: Float) {
        val info = music.info
        val title = info.title?.takeIf { it.isNotBlank() } ?: return
        val mw = w * 0.185f
        val mh = h * 0.23f
        val left = w * 0.70f // ends at 88.5%, before fixed factory warnings
        val top = h * 0.14f // bottom 37%, clear of Google's fixed card below
        val box = RectF(left, top, left + mw, top + mh)
        drawGlass(canvas, box, mh * 0.14f)

        val side = mh * 0.55f
        val ax = left + mh * 0.14f
        val ay = top + (mh - side) / 2f
        music.artwork?.takeUnless(Bitmap::isRecycled)?.let { bitmap ->
            rect.set(ax, ay, ax + side, ay + side)
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, side * 0.14f, side * 0.14f, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawBitmap(bitmap, null, rect, art)
            canvas.restore()
        }
        val textLeft = ax + side + mh * 0.13f
        val textWidth = left + mw - mh * 0.12f - textLeft
        if (textWidth <= 0f) return
        text.textAlign = Paint.Align.LEFT
        valuePaint(h * 0.032f, medium)
        canvas.drawText(fit(title, textWidth), textLeft, top + mh * 0.36f, text)
        info.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            valuePaint(h * 0.026f, regular)
            text.color = secondary
            canvas.drawText(fit(artist, textWidth), textLeft, top + mh * 0.57f, text)
            text.color = Color.WHITE
        }
        val duration = info.durationMillis?.takeIf { it > 0 } ?: return
        var elapsed = info.elapsedMillis ?: return
        if (info.playing) {
            elapsed += SystemClock.elapsedRealtime() - musicReceivedAt
            removeCallbacks(tick)
            postDelayed(tick, 1_000L)
        }
        val barH = mh * 0.028f
        val barTop = top + mh * 0.76f
        rect.set(textLeft, barTop, textLeft + textWidth, barTop + barH)
        canvas.drawRoundRect(rect, barH, barH, track)
        rect.right = textLeft + textWidth * (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        canvas.drawRoundRect(rect, barH, barH, accentFill)
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun infoParts(next: ClusterTurnGuidance): List<String> {
        val parts = mutableListOf<String>()
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let { epoch ->
            // Always 12-hour, whatever the phone or car is set to.
            parts += context.getString(R.string.turn_card_info_arrival,
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epoch * 1000L)))
        }
        next.remainingSeconds?.takeIf { it > 0 }?.let { total ->
            val hours = (total / 3600).toInt()
            val minutes = ((total % 3600) / 60).toInt()
            parts += if (hours > 0) context.getString(R.string.turn_card_info_duration_hm, hours, minutes)
            else context.getString(R.string.turn_card_info_duration_m, minutes)
        }
        next.remainingMeters?.takeIf { it > 0 }?.let { meters ->
            parts += context.getString(R.string.turn_card_info_km, meters / 1000.0)
        }
        return parts
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

    /** A thin fuel-pump outline: body, window and nozzle hose. */
    private fun drawPump(canvas: Canvas, left: Float, top: Float, size: Float) {
        stroke.strokeWidth = size * 0.09f
        rect.set(left, top, left + size * 0.62f, top + size)
        canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, stroke)
        rect.set(left + size * 0.15f, top + size * 0.17f, left + size * 0.47f, top + size * 0.38f)
        canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, fill)
        canvas.drawLine(left + size * 0.62f, top + size * 0.30f, left + size * 0.86f, top + size * 0.46f, stroke)
        canvas.drawLine(left + size * 0.86f, top + size * 0.46f, left + size * 0.86f, top + size * 0.86f, stroke)
    }

    private companion object {
        /** Where the lower fade starts, as a fraction of the panel height. */
        const val FADE_TOP = 0.74f
    }
}



