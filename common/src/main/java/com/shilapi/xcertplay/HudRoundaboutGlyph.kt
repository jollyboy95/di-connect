package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A roundabout with its exit number and no direction: the ring, the approach and the number.
 * Used where the source gives a roundabout code but no reliable picture, so the HUD never shows
 * a turn in the wrong direction, as one fixed roundabout picture for every exit can.
 */
internal class HudRoundaboutGlyph(tint: Int) : Drawable() {
    var exitNumber = 0
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    var tint: Int
        get() = ink.color
        set(value) { ink.color = value }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    override fun draw(canvas: Canvas) {
        val box = RectF(bounds)
        val size = minOf(box.width(), box.height())
        val cx = box.centerX()
        val cy = box.top + size * .40f
        val radius = size * .25f
        ink.strokeWidth = size * .075f
        canvas.drawCircle(cx, cy, radius, ink)
        canvas.drawLine(cx, cy + radius, cx, box.top + size * .88f, ink)
        if (exitNumber in 1..10) {
            number.textSize = size * .30f
            canvas.drawText(exitNumber.toString(), cx, cy - (number.ascent() + number.descent()) / 2f, number)
        }
    }

    override fun setAlpha(alpha: Int) { ink.alpha = alpha; number.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { ink.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** AMap roundabout codes as mapped by clusterTurnGlyphRes. */
        val ICONS = setOf(11, 12, 17, 18)
    }
}
