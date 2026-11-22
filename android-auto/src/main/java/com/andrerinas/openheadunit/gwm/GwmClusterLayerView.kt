package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.hud.BydGuidance
import com.andrerinas.openheadunit.hud.ClusterTurnCardView
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The GWM H7 cluster layer drawn over Android Auto's live cluster map (map-first, after the
 * approved Aston Martin / CarPlay Ultra-style layout). Adapted from DiPlay's GwmClusterHudView
 * (jollyboy95/d-play-gwm-h7 @ 1460810, GPL-3.0-or-later) to DiAuto's guidance and media sources.
 *
 * Layout, in panel percent, around the stock OFF-ROAD theme's telltales (verify on the car):
 * - gear indicator at the top centre is left clear (0–10 %);
 * - navigation card top centre, 10.5–30.5 %, from Android Auto's navigation metadata;
 * - music card top right, 10–28.5 %, from Android Auto's media channel;
 * - instrument strip from 76 % down: fuel and range left, speed centred, odometer right. The car's
 *   lamp row (READY under the speed, limit/assist bottom left, ECO bottom right) draws above it.
 *
 * Only real values are drawn. A value the car does not report shows a dash; when the whole data
 * feed stops, the strip says so instead of freezing the last numbers.
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
internal class GwmClusterLayerView(context: Context, private val settings: GwmSettings) : View(context) {
    private var snapshot: GwmVehicleData.Snapshot? = null
    /** True once the vehicle feed has delivered anything this session: null afterwards is "stale". */
    private var hadVehicleData = false
    private var guidance: BydGuidance? = null
    private var music = GwmMusicState.Snapshot()
    private var stopMusic: (() -> Unit)? = null

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val dim = Color.rgb(170, 180, 194)
    private val faint = Color.rgb(110, 118, 130)

    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(222, 14, 20, 26) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(48, 255, 255, 255)
    }
    private val bar = Paint().apply { color = Color.argb(236, 8, 14, 18) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val low = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 159, 10) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private var glyph: Drawable? = null
    private var glyphRes = 0
    private val tick = Runnable { invalidate() }

    fun setSnapshot(next: GwmVehicleData.Snapshot?) {
        if (next != null) hadVehicleData = true
        if (next == snapshot) return
        snapshot = next
        invalidate()
    }

    fun setGuidance(next: BydGuidance?) {
        if (next == guidance) return
        guidance = next
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopMusic = GwmMusicState.observe {
            music = it
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
        if (settings.vehicleStrip) drawBottomBar(canvas, w, h)
        if (settings.navCard) guidance?.let { drawNavigation(canvas, it, w, h) }
        if (settings.musicCard) drawMusic(canvas, w, h)
    }

    private fun drawBottomBar(canvas: Canvas, w: Float, h: Float) {
        val data = snapshot
        // The bar runs from 76 % down; our row sits in its upper part so the car's own lamp row
        // (READY under the speed, speed limit under the fuel, drive mode right) shows beneath it.
        val top = h * 0.76f
        val bh = h * 0.165f
        val mid = h * 0.865f
        canvas.drawRect(0f, top, w, h, bar)
        canvas.drawRect(0f, top, w, top + 1.5f, line)
        val cx = w / 2f
        for (x in floatArrayOf(cx - w * 0.105f, cx + w * 0.105f)) {
            canvas.drawRect(x, mid - bh * 0.30f, x + 1.5f, mid + bh * 0.30f, line)
        }

        // Speed, centred.
        val baseline = mid + bh * 0.25f
        text.typeface = bold
        text.textSize = bh * 0.70f
        text.textAlign = Paint.Align.RIGHT
        val speed = data?.speedKph
        text.color = if (speed != null) Color.WHITE else faint
        canvas.drawText(speed?.roundToInt()?.toString() ?: DASH, cx + bh * 0.30f, baseline, text)
        text.typeface = medium
        text.textSize = bh * 0.24f
        text.textAlign = Paint.Align.LEFT
        text.color = dim
        // The adapter can answer with no values at all; that is "no data", not a live reading.
        val anyValue = data != null && (data.speedKph != null || data.fuelPercent != null ||
            data.rangeKm != null || data.odometerKm != null)
        val unit = when {
            anyValue -> context.getString(R.string.aa_gwm_unit_kmh)
            hadVehicleData -> context.getString(R.string.aa_gwm_data_stale)
            else -> context.getString(R.string.aa_gwm_data_unavailable)
        }
        canvas.drawText(unit, cx + bh * 0.45f, baseline, text)

        // Fuel bar and range, left: starting right of the car's own lamps at the bottom left (EV badge,
        // speed-limit sign, driver-assist icons), which r5 on the H7 showed over a bar at 3.5 %.
        val left = w * FUEL_LEFT
        val icon = bh * 0.38f
        val fuel = data?.fuelPercent
        drawPump(canvas, left, mid - icon / 2f, icon, if (fuel != null) Color.WHITE else faint)
        val barLeft = left + icon * 1.35f
        val barW = w * 0.07f
        val barH = bh * 0.07f
        rect.set(barLeft, mid - barH / 2f, barLeft + barW, mid + barH / 2f)
        canvas.drawRoundRect(rect, barH, barH, track)
        if (fuel != null) {
            rect.right = barLeft + barW * (fuel / 100f).coerceIn(0.03f, 1f)
            canvas.drawRoundRect(rect, barH, barH, if (fuel < LOW_FUEL_PERCENT) low else fill)
        }
        val range = data?.rangeKm
        text.typeface = medium
        text.textSize = bh * 0.28f
        text.textAlign = Paint.Align.LEFT
        text.color = if (range != null) Color.WHITE else faint
        canvas.drawText(range?.let { String.format(Locale.getDefault(), "%d km", it.roundToInt()) } ?: "$DASH km",
            barLeft + barW + w * 0.014f, mid + bh * 0.10f, text)

        // Odometer, right.
        val odo = data?.odometerKm
        text.textSize = bh * 0.25f
        text.textAlign = Paint.Align.RIGHT
        val value = odo?.let { NumberFormat.getIntegerInstance().format(it.roundToInt()) + " km" } ?: "$DASH km"
        val right = w - w * 0.022f
        text.color = if (odo != null) Color.WHITE else faint
        canvas.drawText(value, right, mid + bh * 0.09f, text)
        text.color = dim
        canvas.drawText("ODO  ", right - text.measureText(value), mid + bh * 0.09f, text)
        text.color = Color.WHITE
    }

    private fun drawNavigation(canvas: Canvas, next: BydGuidance, w: Float, h: Float) {
        val cw = w * 0.235f
        val ch = h * 0.20f
        val left = (w - cw) / 2f
        val top = h * 0.105f
        val radius = ch * 0.16f
        // Arrival, time and distance left only when the phone reports them in its navigation data;
        // without them the card ends under the road name instead of leaving an empty row.
        val info = GwmFormat.tripParts(context, next).joinToString("  ·  ")
        val cardHeight = if (info.isNotEmpty()) ch else ch * 0.66f
        rect.set(left, top, left + cw, top + cardHeight)
        canvas.drawRoundRect(rect, radius, radius, glass)
        edge.strokeWidth = h * 0.002f
        canvas.drawRoundRect(rect, radius, radius, edge)

        val res = ClusterTurnCardView.glyphRes(next.clusterIcon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(Color.WHITE) }
            glyphRes = res
        }
        val side = ch * 0.40f
        val gx = left + ch * 0.12f
        val gy = top + ch * 0.10f
        glyph?.setBounds(gx.toInt(), gy.toInt(), (gx + side).toInt(), (gy + side).toInt())
        glyph?.draw(canvas)

        val textLeft = left + ch * 0.66f
        val textWidth = left + cw - ch * 0.10f - textLeft
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = ch * 0.20f
        canvas.drawText(fit(GwmFormat.distance(context, next.distanceMeters), textWidth), textLeft, top + ch * 0.30f, text)
        text.typeface = sans
        text.textSize = ch * 0.155f
        val road = GwmFormat.road(context, next)
        if (road.isNotEmpty()) canvas.drawText(fit(road, textWidth), textLeft, top + ch * 0.53f, text)

        if (info.isNotEmpty()) {
            canvas.drawRect(left + ch * 0.12f, top + ch * 0.655f, left + cw - ch * 0.12f, top + ch * 0.655f + 1.5f, line)
            text.typeface = medium
            text.textSize = ch * 0.135f
            canvas.drawText(fit(info, left + cw - ch * 0.12f - (left + ch * 0.12f)), left + ch * 0.12f, top + ch * 0.87f, text)
        }
    }

    private fun drawMusic(canvas: Canvas, w: Float, h: Float) {
        val info = music
        val title = info.title?.takeIf { it.isNotBlank() } ?: return
        val mw = w * 0.17f
        val mh = h * 0.185f
        val left = w - mw - w * 0.015f
        val top = h * 0.10f
        val radius = mh * 0.14f
        rect.set(left, top, left + mw, top + mh)
        canvas.drawRoundRect(rect, radius, radius, glass)
        edge.strokeWidth = h * 0.002f
        canvas.drawRoundRect(rect, radius, radius, edge)

        val side = mh * 0.76f
        val ax = left + mh * 0.12f
        val ay = top + (mh - side) / 2f
        val artwork = info.artwork?.takeUnless(Bitmap::isRecycled)
        rect.set(ax, ay, ax + side, ay + side)
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, side * 0.08f, side * 0.08f, Path.Direction.CW)
        canvas.clipPath(clip)
        if (artwork != null) canvas.drawBitmap(artwork, null, rect, art) else canvas.drawRect(rect, track)
        canvas.restore()

        val textLeft = ax + side + mh * 0.12f
        val textWidth = left + mw - mh * 0.10f - textLeft
        if (textWidth <= 0f) return
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        text.typeface = bold
        text.textSize = mh * 0.16f
        canvas.drawText(fit(title, textWidth), textLeft, top + mh * 0.36f, text)
        info.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            text.typeface = sans
            text.textSize = mh * 0.135f
            text.color = dim
            canvas.drawText(fit(artist, textWidth), textLeft, top + mh * 0.58f, text)
            text.color = Color.WHITE
        }
        val duration = info.durationMillis?.takeIf { it > 0 } ?: return
        val elapsed = info.positionNow() ?: return
        if (info.playing) {
            removeCallbacks(tick)
            postDelayed(tick, 1_000L)
        }
        val barH = mh * 0.035f
        val barTop = top + mh * 0.76f
        rect.set(textLeft, barTop, textLeft + textWidth, barTop + barH)
        canvas.drawRoundRect(rect, barH, barH, track)
        rect.right = textLeft + textWidth * (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        canvas.drawRoundRect(rect, barH, barH, fill)
    }

    private fun fit(value: String, maxWidth: Float): String {
        if (text.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 0 && text.measureText(value.take(end) + "…") > maxWidth) end--
        return value.take(end) + "…"
    }

    /** A plain fuel-pump outline: body, window and nozzle hose. */
    private fun drawPump(canvas: Canvas, left: Float, top: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = size * 0.11f
        rect.set(left, top, left + size * 0.62f, top + size)
        canvas.drawRoundRect(rect, size * 0.08f, size * 0.08f, stroke)
        rect.set(left + size * 0.14f, top + size * 0.16f, left + size * 0.48f, top + size * 0.40f)
        fill.color = color
        canvas.drawRect(rect, fill)
        fill.color = Color.WHITE
        canvas.drawLine(left + size * 0.62f, top + size * 0.30f, left + size * 0.86f, top + size * 0.46f, stroke)
        canvas.drawLine(left + size * 0.86f, top + size * 0.46f, left + size * 0.86f, top + size * 0.86f, stroke)
        stroke.color = Color.WHITE
    }

    private companion object {
        const val DASH = "—"
        const val LOW_FUEL_PERCENT = 15f
        /** Clear of the stock bottom-left lamps (to about 18 % of the panel on the OFF-ROAD theme). */
        const val FUEL_LEFT = 0.205f
    }
}

/** Shared text for the cluster card and the head-up strip. */
internal object GwmFormat {
    fun distance(context: Context, meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.aa_turn_card_now)
        meters < 1000 -> context.getString(R.string.aa_turn_card_distance_m, (meters / 10) * 10)
        else -> context.getString(R.string.aa_turn_card_distance_km, meters / 100 / 10f)
    }

    fun road(context: Context, next: BydGuidance): String =
        if (next.roundaboutExit in 1..9) context.getString(R.string.aa_turn_card_exit, next.roundaboutExit) else next.road

    /**
     * Arrival time, as now plus the phone's reported time to arrival; null when not reported.
     * Follows the head unit's 12/24-hour clock setting, like the car's own clock.
     */
    fun arrival(context: Context, next: BydGuidance, nowMillis: Long = System.currentTimeMillis()): String? =
        next.remainingSeconds.takeIf { it > 0 }?.let {
            android.text.format.DateFormat.getTimeFormat(context).format(Date(nowMillis + it * 1000L))
        }

    fun tripParts(context: Context, next: BydGuidance): List<String> {
        val parts = mutableListOf<String>()
        arrival(context, next)?.let { parts += context.getString(R.string.aa_turn_card_info_arrival, it) }
        if (next.remainingSeconds > 0) {
            val hours = next.remainingSeconds / 3600
            val minutes = (next.remainingSeconds % 3600) / 60
            parts += if (hours > 0) context.getString(R.string.aa_turn_card_info_duration_hm, hours, minutes)
            else context.getString(R.string.aa_turn_card_info_duration_m, minutes)
        }
        if (next.remainingMeters > 0) parts += context.getString(R.string.aa_turn_card_info_km, next.remainingMeters / 1000.0)
        return parts
    }
}
