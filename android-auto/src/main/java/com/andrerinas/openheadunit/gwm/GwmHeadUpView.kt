package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.hud.BydGuidance
import com.andrerinas.openheadunit.hud.ClusterTurnCardView

/**
 * The GWM head-up display's arrow area: turn arrow, distance to the turn and arrival time, only
 * while a route is active. The car already projects speed and its own readouts, and apps can only
 * draw in the arrow area, so nothing else is drawn and the background stays transparent.
 * Adapted from DiPlay's GwmHeadUpView (jollyboy95/d-play-gwm-h7 @ 1460810, GPL-3.0-or-later).
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
internal class GwmHeadUpView(context: Context) : View(context) {
    enum class Anchor { LEFT, CENTRE, RIGHT }

    private var guidance: BydGuidance? = null
    private var anchor = Anchor.CENTRE

    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val etaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val etaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(160, 172, 188)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val arrowTint = Color.rgb(90, 200, 250)
    private var glyph: Drawable? = null
    private var glyphRes = 0

    fun setGuidance(next: BydGuidance?) {
        if (next == guidance) return
        guidance = next
        invalidate()
    }

    /** Draw only inside this part of the display: the arrow window the windscreen shows. */
    fun setArea(next: GwmSettings.HudArea) {
        if (next == area) return
        area = next
        invalidate()
    }

    private var area = GwmSettings.HudArea(0, 0, 100, 100)

    fun setAnchor(next: Anchor) {
        if (next == anchor) return
        anchor = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        if (width <= 0 || height <= 0) return
        val left = width * area.left / 100f
        val top = height * area.top / 100f
        val w = width * area.width / 100f
        val h = height * area.height / 100f
        val distance = GwmFormat.distance(context, next.distanceMeters)
        val eta = GwmFormat.arrival(context, next)
        canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, 0f, w, h)
        // A small, squarish arrow area gets the stacked layout.
        if (w < h * 2.2f) drawStacked(canvas, next, distance, eta, w, h) else drawRow(canvas, next, distance, eta, w, h)
        canvas.restore()
    }

    private fun drawRow(canvas: Canvas, next: BydGuidance, distance: String, eta: String?, w: Float, h: Float) {
        val unit = h * 0.5f
        distancePaint.textSize = unit * 0.62f
        etaPaint.textSize = unit * 0.40f
        etaLabelPaint.textSize = unit * 0.24f
        val arrow = unit * 0.95f
        val gap = unit * 0.28f
        val etaLabel = context.getString(R.string.aa_gwm_hud_arrival)
        val textW = distancePaint.measureText(distance)
        val etaW = eta?.let { maxOf(etaPaint.measureText(it), etaLabelPaint.measureText(etaLabel)) + gap * 2f } ?: 0f
        val rowW = arrow + gap + textW + etaW
        val left = when (anchor) {
            Anchor.LEFT -> h * 0.15f
            Anchor.CENTRE -> (w - rowW) / 2f
            Anchor.RIGHT -> w - rowW - h * 0.15f
        }.coerceIn(0f, (w - rowW).coerceAtLeast(0f))
        val midY = h / 2f
        drawArrow(canvas, next, left, midY - arrow / 2f, arrow)
        val textLeft = left + arrow + gap
        canvas.drawText(distance, textLeft, midY + unit * 0.22f, distancePaint)
        eta?.let {
            val etaLeft = textLeft + textW + gap * 2f
            canvas.drawText(etaLabel, etaLeft, midY - unit * 0.18f, etaLabelPaint)
            canvas.drawText(it, etaLeft, midY + unit * 0.30f, etaPaint)
        }
    }

    /**
     * The H7's arrow window (about 144x96 px), after DiPlay's CarPlay HUD: arrow and distance on
     * the first line, the street under it, then the arrival time. Lines that the phone does not
     * report are left out and the rest grow to use the space. A margin keeps text off the edges.
     */
    private fun drawStacked(canvas: Canvas, next: BydGuidance, distance: String, eta: String?, w: Float, h: Float) {
        val pad = h * 0.06f
        // A wider margin on the right: the windscreen cuts the arrow window short on that side.
        val usableW = w - pad * 3f
        val usableH = h - pad * 2f
        // Google Maps often names the next road "towards <place>"; in a 144 px window the place is
        // the useful part, so the prefix goes.
        val road = GwmFormat.road(context, next).trim().removePrefix("towards ").removePrefix("Towards ").trim()
        val extraLines = (if (road.isNotEmpty()) 1 else 0) + (if (eta != null) 1 else 0)
        // The first line gets the most room; each extra line about a quarter of the height.
        val lineH = when (extraLines) {
            0 -> usableH
            1 -> usableH * 0.58f
            else -> usableH * 0.46f
        }
        val smallH = if (extraLines == 0) 0f else (usableH - lineH) / extraLines

        var arrow = lineH * 0.92f
        distancePaint.textSize = lineH * 0.72f
        val gap = arrow * 0.18f
        val wanted = arrow + gap + distancePaint.measureText(distance)
        if (wanted > usableW) {
            val k = usableW / wanted
            arrow *= k
            distancePaint.textSize *= k
        }
        val rowW = arrow + gap + distancePaint.measureText(distance)
        val left = pad + offset(usableW, rowW)
        val top = pad + if (extraLines == 0) (usableH - lineH) / 2f else 0f
        val mid = top + lineH / 2f
        drawArrow(canvas, next, left, mid - arrow / 2f, arrow)
        canvas.drawText(distance, left + arrow + gap, mid - (distancePaint.descent() + distancePaint.ascent()) / 2f, distancePaint)

        var y = top + lineH
        if (road.isNotEmpty()) {
            etaLabelPaint.textSize = smallH * 0.78f
            val shown = ellipsize(road, usableW, etaLabelPaint)
            y += smallH
            canvas.drawText(shown, pad + offset(usableW, etaLabelPaint.measureText(shown)), y - etaLabelPaint.descent(), etaLabelPaint)
        }
        eta?.let {
            etaPaint.textSize = smallH * 0.80f
            val shown = ellipsize(it, usableW, etaPaint)
            y += smallH
            canvas.drawText(shown, pad + offset(usableW, etaPaint.measureText(shown)), y - etaPaint.descent(), etaPaint)
        }
    }

    /** Horizontal offset of a [content]-wide line inside [available] for the chosen anchor. */
    private fun offset(available: Float, content: Float): Float = when (anchor) {
        Anchor.LEFT -> 0f
        Anchor.CENTRE -> (available - content) / 2f
        Anchor.RIGHT -> available - content
    }.coerceAtLeast(0f)

    private fun drawArrow(canvas: Canvas, next: BydGuidance, left: Float, top: Float, side: Float) {
        val res = ClusterTurnCardView.glyphRes(next.clusterIcon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(arrowTint) }
            glyphRes = res
        }
        glyph?.setBounds(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
        glyph?.draw(canvas)
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return text.take(end) + "…"
    }
}
