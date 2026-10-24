package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Parked diagnostic for the head-up display: a lettered grid over the whole display, plus the
 * area the HUD arrow is currently drawn in. The windscreen shows only part of the HUD display (the
 * arrow window), so the cells the driver can read tell where that window is. No vehicle values.
 */
internal class GwmHudGridView(context: Context, private val area: GwmSettings.HudArea) : View(context) {
    private val line = Paint().apply { color = Color.argb(200, 90, 200, 250); strokeWidth = 2f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val box = Paint().apply { color = Color.rgb(255, 159, 10); style = Paint.Style.STROKE; strokeWidth = 6f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cw = w / COLUMNS
        val rh = h / ROWS
        for (c in 1 until COLUMNS) canvas.drawLine(c * cw, 0f, c * cw, h, line)
        for (r in 1 until ROWS) canvas.drawLine(0f, r * rh, w, r * rh, line)
        label.textSize = minOf(cw, rh) * 0.42f
        for (r in 0 until ROWS) for (c in 0 until COLUMNS) {
            val text = "${'A' + c}${r + 1}"
            canvas.drawText(text, c * cw + cw / 2f, r * rh + rh / 2f - (label.descent() + label.ascent()) / 2f, label)
        }
        // Where the arrow is drawn now (orange).
        canvas.drawRect(w * area.left / 100f, h * area.top / 100f,
            w * (area.left + area.width) / 100f, h * (area.top + area.height) / 100f, box)
    }

    companion object {
        /** Each column is 10 % of the display width, each row 20 % of its height. */
        const val COLUMNS = 10
        const val ROWS = 5
    }
}
