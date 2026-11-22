/*
 * DiPlay GWM Adapter — GWM/Haval integration layer for DiPlay.
 * Copyright (C) 2026  jollyboy95
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See the LICENSE file.
 */
package org.dplay.gwm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/**
 * Minimal, non-interactive driver display: large speed read-out, a fuel/range line,
 * and an optional small turn indicator (kept deliberately small per the H7 layout
 * preference — speed is primary, turn arrows secondary).
 *
 * <p>Reference renderer only. It intentionally draws nothing where speed/gear/warning
 * telltales live and never claims to replace factory cluster content. Swap in richer
 * artwork later (e.g. 600x600 image dials) as the project matures.
 */
@SuppressLint("ViewConstructor")
public final class ClusterDialView extends View {

    public enum Turn { NONE, LEFT, RIGHT }

    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint turnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int speedKmh = 0;
    private int fuelPercent = -1;
    private int rangeKm = -1;
    private Turn turn = Turn.NONE;

    public ClusterDialView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);

        speedPaint.setColor(Color.WHITE);
        speedPaint.setTextAlign(Paint.Align.CENTER);
        speedPaint.setFakeBoldText(true);

        unitPaint.setColor(0xFFB0B0B0);
        unitPaint.setTextAlign(Paint.Align.CENTER);

        infoPaint.setColor(0xFFDADADA);
        infoPaint.setTextAlign(Paint.Align.CENTER);

        turnPaint.setColor(0xFF3DDc84);
        turnPaint.setStyle(Paint.Style.FILL);
    }

    /** Thread-safe-ish setter; invalidates for redraw. Call from main thread. */
    public void setValues(int speedKmh, int fuelPercent, int rangeKm, Turn turn) {
        this.speedKmh = Math.max(0, speedKmh);
        this.fuelPercent = fuelPercent;
        this.rangeKm = rangeKm;
        this.turn = turn == null ? Turn.NONE : turn;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) return;

        final float cx = w / 2f;

        // Speed — the primary element.
        speedPaint.setTextSize(h * 0.42f);
        float speedBaseline = h * 0.52f;
        canvas.drawText(String.valueOf(speedKmh), cx, speedBaseline, speedPaint);

        unitPaint.setTextSize(h * 0.09f);
        canvas.drawText("km/h", cx, speedBaseline + h * 0.11f, unitPaint);

        // Fuel / range line.
        if (fuelPercent >= 0 || rangeKm >= 0) {
            infoPaint.setTextSize(h * 0.08f);
            StringBuilder info = new StringBuilder();
            if (fuelPercent >= 0) info.append(fuelPercent).append("%");
            if (fuelPercent >= 0 && rangeKm >= 0) info.append("   ");
            if (rangeKm >= 0) info.append(String.format(Locale.US, "%d km", rangeKm));
            canvas.drawText(info.toString(), cx, h * 0.80f, infoPaint);
        }

        // Small turn indicator (secondary by design).
        if (turn != Turn.NONE) {
            drawTurnArrow(canvas, w, h, turn == Turn.LEFT);
        }
    }

    private void drawTurnArrow(Canvas canvas, int w, int h, boolean left) {
        float size = h * 0.12f;             // deliberately small
        float cy = h * 0.18f;
        float x = left ? w * 0.18f : w * 0.82f;
        android.graphics.Path p = new android.graphics.Path();
        if (left) {
            p.moveTo(x - size, cy);
            p.lineTo(x, cy - size * 0.6f);
            p.lineTo(x, cy + size * 0.6f);
        } else {
            p.moveTo(x + size, cy);
            p.lineTo(x, cy - size * 0.6f);
            p.lineTo(x, cy + size * 0.6f);
        }
        p.close();
        canvas.drawPath(p, turnPaint);
    }
}
