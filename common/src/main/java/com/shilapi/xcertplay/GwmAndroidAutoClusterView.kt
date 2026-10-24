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
import kotlin.math.roundToInt

/**
 * Android Auto-only GWM cluster skin: inset speed, aqua accents and matching charcoal surfaces.
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
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawLowerRow(canvas, w, h)
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

    // ---- lower row ------------------------------------------------------------------------

    /**
     * Slim lower row laid out around the H7 stock theme with its lamps along the bottom: the car's
     * EV, speed-limit and lane icons in the left fifth, READY under the centre, ECO near 79 %.
     */
    private fun drawLowerRow(canvas: Canvas, w: Float, h: Float) {
        val data = snapshot ?: return
        // The information rail and music card share one matte near-black surface.
        body.shader = null
        body.color = Color.rgb(17, 24, 32)
        // The factory speed-limit/assist lamps stay outside the rail.
        rect.set(w * 0.23f, h * 0.82f, w * 0.988f, h * 0.91f)
        canvas.drawRoundRect(rect, h * 0.018f, h * 0.018f, body)
        val baseline = h * 0.875f
        val labelSize = h * 0.024f
        val valueSize = h * 0.038f

        // Soft local contrast behind speed; do not recolour the phone video.
        shade.shader = RadialGradient(w * 0.08f, h * 0.49f, w * 0.28f,
            intArrayOf(Color.argb(205, 7, 16, 25), Color.argb(120, 7, 16, 25), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(0f, h * 0.17f, w * 0.38f, h * 0.79f)
        canvas.drawRect(0f, h * 0.17f, w * 0.38f, h * 0.79f, shade)
        canvas.restore()
        shade.shader = null

        // Inset from the panel edge to clear the steering wheel (approved on-car reference).
        data.speedKph?.let { speed ->
            val x = w * 0.15f
            valuePaint(h * 0.22f, medium)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(speed.roundToInt().toString(), x, h * 0.53f, text)
            valuePaint(h * 0.032f)
            canvas.drawText("km/h", x, h * 0.59f, text)
            rect.set(x - w * 0.012f, h * 0.62f, x + w * 0.012f, h * 0.629f)
            canvas.drawRoundRect(rect, h * 0.0045f, h * 0.0045f, accentFill)
        }

        // Fuel and range between the car's lane icon (about 18 %) and the speed.
        val fuelLeft = w * 0.24f
        val icon = h * 0.042f
        data.fuelPercent?.let { fuel ->
            drawPump(canvas, fuelLeft, baseline - icon * 0.95f, icon)
            val barLeft = fuelLeft + icon * 1.25f
            val barW = w * 0.07f
            val barH = h * 0.008f
            val barY = baseline - icon * 0.45f
            rect.set(barLeft, barY - barH / 2f, barLeft + barW, barY + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, track)
            rect.right = barLeft + barW * (fuel / 100f).coerceIn(0.03f, 1f)
            canvas.drawRoundRect(rect, barH, barH, if (fuel < 15f) low else accentFill)
        }
        data.rangeKm?.let { range ->
            valuePaint(valueSize)
            text.textAlign = Paint.Align.LEFT
            val x = fuelLeft + icon * 1.25f + w * 0.07f + w * 0.012f
            val value = String.format(Locale.getDefault(), "%d", range.roundToInt())
            canvas.drawText(value, x, baseline, text)
            val valueWidth = text.measureText(value)
            labelPaint(labelSize)
            canvas.drawText(" KM", x + valueWidth, baseline, text)
        }

        // Outside temperature between the speed and the car's ECO lamp.
        data.outsideTempC?.let { temp ->
            val x = w * 0.56f
            labelPaint(labelSize)
            text.textAlign = Paint.Align.LEFT
            canvas.drawText("OUTSIDE", x, baseline, text)
            val labelWidth = text.measureText("OUTSIDE  ")
            valuePaint(valueSize)
            canvas.drawText(String.format(Locale.getDefault(), "%d°C", temp.roundToInt()), x + labelWidth, baseline, text)
        }

        // Odometer right of ECO.
        data.odometerKm?.let { odo ->
            val right = w - w * 0.022f
            valuePaint(valueSize)
            text.textAlign = Paint.Align.RIGHT
            val value = NumberFormat.getIntegerInstance().format(odo.roundToInt())
            labelPaint(labelSize)
            val unitWidth = text.measureText(" KM")
            canvas.drawText(" KM", right, baseline, text)
            valuePaint(valueSize)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(value, right - unitWidth, baseline, text)
            val valueWidth = text.measureText(value)
            labelPaint(labelSize)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText("ODO  ", right - unitWidth - valueWidth, baseline, text)
        }
        text.letterSpacing = 0f
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
        val mw = w * 0.17f
        val mh = h * 0.14f
        val left = w * 0.71f // ends at 88%, before the stock right-side warning region
        val top = h * 0.23f // bottom 37%, leaving a gap before Google's card at ~40%
        val box = RectF(left, top, left + mw, top + mh)
        drawGlass(canvas, box, mh * 0.14f)

        val side = mh * 0.72f
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
        valuePaint(mh * 0.155f, medium)
        canvas.drawText(fit(title, textWidth), textLeft, top + mh * 0.36f, text)
        info.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            valuePaint(mh * 0.13f, light)
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
