package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.util.Date

/**
 * The GWM head-up display strip: turn arrow, distance to the turn, road name and arrival time,
 * only while a route is active. The car already projects speed and its own readouts, so nothing
 * else is drawn; the window is opaque black (invisible on the windscreen) and empty with no route.
 */
internal class GwmHeadUpView(context: Context) : View(context) {
    enum class Anchor { LEFT, CENTRE, RIGHT }

    private var guidance: ClusterTurnGuidance? = null
    private var anchor = Anchor.CENTRE

    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 210, 224)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val etaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val etaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(160, 172, 188)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var arrowTint = HudThemeStyle.PLAIN.arrow
    private var style: HudThemeStyle? = null
    private var stopObserving: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopObserving = ClusterThemeStore.observe(context) { post { invalidate() } }
    }

    override fun onDetachedFromWindow() {
        stopObserving?.invoke()
        stopObserving = null
        super.onDetachedFromWindow()
    }

    /** Takes the CarPlay cluster theme's colour and digits (see [HudThemeStyle]). */
    private fun applyStyle(next: HudThemeStyle) {
        if (next == style) return
        style = next
        arrowTint = next.arrow
        roundabout.tint = next.arrow
        glyphRes = 0
        distancePaint.color = next.text
        distancePaint.typeface = next.typeface
        etaPaint.color = next.text
        etaPaint.typeface = next.typeface
        roadPaint.color = next.muted
        etaLabelPaint.color = next.muted
    }
    private var glyph: Drawable? = null
    private var glyphRes = 0
    private val roundabout = HudRoundaboutGlyph(arrowTint)

    /** Roundabouts get the neutral numbered roundabout; everything else the usual turn arrow. */
    private fun glyphFor(next: ClusterTurnGuidance): Drawable? {
        if (next.icon in HudRoundaboutGlyph.ICONS) {
            roundabout.exitNumber = next.roundaboutExit
            glyphRes = 0
            return roundabout
        }
        val res = clusterTurnGlyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(arrowTint) }
            glyphRes = res
        }
        return glyph
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (next == guidance) return
        guidance = next
        invalidate()
    }

    fun setAnchor(next: Anchor) {
        anchor = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance?.takeIf { it.icon != 0 } ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        applyStyle(HudThemeStyle.of(context, ClusterConnection.CARPLAY))

        val unit = h * 0.5f
        distancePaint.textSize = unit * 0.62f
        roadPaint.textSize = unit * 0.30f
        etaPaint.textSize = unit * 0.40f
        etaLabelPaint.textSize = unit * 0.24f

        val distance = distanceLabel(next.distanceMeters)
        val road = roadLabel(next)
        val eta = next.arrivalEpochSeconds?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(Date(it * 1000L)) // always 12-hour
        }
        val arrow = unit * 0.95f
        val gap = unit * 0.28f
        // A small, squarish HUD area (just the arrow window) gets the stacked layout instead.
        if (w < h * 2.2f) {
            if (anchor == Anchor.LEFT) drawInNavWindow(canvas, next, distance, eta, w, h)
            else drawStacked(canvas, next, distance, eta, w, h)
            return
        }
        val textW = maxOf(distancePaint.measureText(distance), minOf(roadPaint.measureText(road), unit * 3.2f))
        val etaW = eta?.let { maxOf(etaPaint.measureText(it), etaLabelPaint.measureText(etaLabel)) + gap * 2f } ?: 0f
        val rowW = arrow + gap + textW + etaW
        val left = when (anchor) {
            Anchor.LEFT -> h * 0.15f
            Anchor.CENTRE -> (w - rowW) / 2f
            Anchor.RIGHT -> w - rowW - h * 0.15f
        }.coerceIn(0f, (w - rowW).coerceAtLeast(0f))
        val midY = h / 2f

        glyph = glyphFor(next)
        glyph?.setBounds(left.toInt(), (midY - arrow / 2f).toInt(), (left + arrow).toInt(), (midY + arrow / 2f).toInt())
        glyph?.draw(canvas)

        val textLeft = left + arrow + gap
        canvas.drawText(distance, textLeft, midY + unit * 0.05f, distancePaint)
        if (road.isNotEmpty()) {
            canvas.drawText(ellipsize(road, unit * 3.2f, roadPaint), textLeft, midY + unit * 0.48f, roadPaint)
        }
        eta?.let {
            val etaLeft = textLeft + textW + gap * 2f
            canvas.drawText(etaLabel, etaLeft, midY - unit * 0.18f, etaLabelPaint)
            canvas.drawText(it, etaLeft, midY + unit * 0.30f, etaPaint)
        }
    }

    /** Arrow on top, distance under it, arrival time last; everything centred and sized to fit. */
    /**
     * The H7 HUD shows display 3 (480x240) only through its nav-arrow window, measured with a
     * 10x5 grid as cells A3-C4: x 0-30 %, y 40-80 % (144x96 px). Arrow and distance on one line,
     * arrival time below, all inside that window with a small margin.
     */
    private fun drawInNavWindow(canvas: Canvas, next: ClusterTurnGuidance, distance: String, eta: String?, w: Float, h: Float) {
        val inset = h * 0.02f
        val left = w * NAV_WINDOW_LEFT + inset
        val right = w * NAV_WINDOW_RIGHT - inset
        val top = h * NAV_WINDOW_TOP + inset
        val bottom = h * NAV_WINDOW_BOTTOM - inset
        val boxW = right - left
        val boxH = bottom - top
        val lineOne = if (eta != null) boxH * 0.64f else boxH
        val arrow = minOf(lineOne * 0.92f, boxW * 0.40f)

        glyph = glyphFor(next)
        val arrowTop = top + (lineOne - arrow) / 2f
        glyph?.setBounds(left.toInt(), arrowTop.toInt(), (left + arrow).toInt(), (arrowTop + arrow).toInt())
        glyph?.draw(canvas)

        val textLeft = left + arrow + boxW * 0.05f
        val textWidth = right - textLeft
        distancePaint.textAlign = Paint.Align.LEFT
        distancePaint.textSize = lineOne * 0.48f
        while (distancePaint.textSize > 8f && distancePaint.measureText(distance) > textWidth) distancePaint.textSize *= 0.92f
        val distanceBaseline = top + lineOne / 2f - (distancePaint.descent() + distancePaint.ascent()) / 2f
        canvas.drawText(distance, textLeft, distanceBaseline, distancePaint)

        eta?.let {
            val lineTwoTop = top + lineOne
            val lineTwo = bottom - lineTwoTop
            // "ARRIVE 7:33 PM": a small grey label so the time reads as the arrival time.
            val label = etaLabel.uppercase()
            etaPaint.textAlign = Paint.Align.LEFT
            etaLabelPaint.textAlign = Paint.Align.LEFT
            etaPaint.textSize = lineTwo * 0.72f
            fun rowWidth(): Float {
                etaLabelPaint.textSize = etaPaint.textSize * 0.62f
                return etaLabelPaint.measureText(label) + etaPaint.textSize * 0.3f + etaPaint.measureText(it)
            }
            while (etaPaint.textSize > 8f && rowWidth() > boxW) etaPaint.textSize *= 0.92f
            val etaBaseline = lineTwoTop + lineTwo / 2f - (etaPaint.descent() + etaPaint.ascent()) / 2f
            canvas.drawText(label, left, etaBaseline, etaLabelPaint)
            val timeLeft = left + etaLabelPaint.measureText(label) + etaPaint.textSize * 0.3f
            canvas.drawText(it, timeLeft, etaBaseline, etaPaint)
        }
    }

    private companion object {
        const val NAV_WINDOW_LEFT = 0f
        // Measured on the windscreen with the parked grid: visible to ~25 % across, 40-80 % down.
        const val NAV_WINDOW_RIGHT = 0.25f
        const val NAV_WINDOW_TOP = 0.42f
        const val NAV_WINDOW_BOTTOM = 0.78f
    }

    private fun drawStacked(canvas: Canvas, next: ClusterTurnGuidance, distance: String, eta: String?, w: Float, h: Float) {
        // On the H7 the car's own HUD layer covers most of display 3 and leaves the nav-arrow window
        // on the left, so the position setting picks the column the stack is centred in.
        val (regionLeft, regionWidth) = when (anchor) {
            Anchor.LEFT -> 0f to w * 0.42f
            Anchor.CENTRE -> 0f to w
            Anchor.RIGHT -> w * 0.58f to w * 0.42f
        }
        val lines = if (eta != null) 3.0f else 2.3f
        val unit = minOf(h / lines, regionWidth * 0.62f)
        val arrow = unit * 1.05f
        val total = arrow + unit * 0.75f + (if (eta != null) unit * 0.55f else 0f)
        var y = (h - total) / 2f
        val cx = regionLeft + regionWidth / 2f
        glyph = glyphFor(next)
        glyph?.setBounds((cx - arrow / 2f).toInt(), y.toInt(), (cx + arrow / 2f).toInt(), (y + arrow).toInt())
        glyph?.draw(canvas)
        y += arrow
        distancePaint.textSize = unit * 0.55f
        distancePaint.textAlign = Paint.Align.CENTER
        val shown = ellipsize(distance, regionWidth * 0.96f, distancePaint)
        canvas.drawText(shown, cx, y + unit * 0.62f, distancePaint)
        distancePaint.textAlign = Paint.Align.LEFT
        y += unit * 0.75f
        eta?.let {
            etaPaint.textSize = unit * 0.36f
            etaPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(ellipsize("$etaLabel $it", regionWidth * 0.96f, etaPaint), cx, y + unit * 0.42f, etaPaint)
            etaPaint.textAlign = Paint.Align.LEFT
        }
    }

    private val etaLabel: String get() = context.getString(R.string.settings_gwm_hud_arrival)

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, (meters / 10) * 10)
        else -> context.getString(R.string.turn_card_distance_km, meters / 100 / 10f)
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit, next.roundaboutExit) else next.road

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return text.take(end) + "…"
    }
}
