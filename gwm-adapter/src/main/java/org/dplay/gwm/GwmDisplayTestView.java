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

/** Parked-only visual check; deliberately shows no simulated vehicle readings. */
@SuppressLint("ViewConstructor")
final class GwmDisplayTestView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GwmDisplayTestView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        paint.setTextSize(Math.max(24f, getHeight() * 0.08f));
        canvas.drawText("DISPLAY TEST", centerX, centerY, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(Math.max(14f, getHeight() * 0.035f));
        canvas.drawText("PARKED ONLY - NO VEHICLE DATA", centerX,
                centerY + paint.getTextSize() * 1.8f, paint);
    }
}