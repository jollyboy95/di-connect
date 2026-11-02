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

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/**
 * Shows a single non-interactive surface on a chosen logical display, used for the
 * cluster/HUD driver content.
 *
 * <p>Approach (Android 9 compatible) adapted from the author's ClusterProbe
 * OverlayManager: resolve the target {@link Display}, build a display-specific
 * {@link Context}, and add a {@code TYPE_APPLICATION_OVERLAY} window that cannot
 * take focus or touch. It draws this app's own content only; it does not move or
 * reorder any other composed video signal (see docs).
 *
 * <p>Must be driven from the main thread. Validate parked.
 */
public final class ClusterOverlayRenderer {

    private final Context appContext;
    private View view;
    private WindowManager windowManager;

    public ClusterOverlayRenderer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Add the cluster surface to the given display.
     *
     * @throws IllegalArgumentException if the display id is invalid (e.g. an
     *         unverified guess that is not present — fail loudly, do not fall back
     *         to the primary display).
     * @throws IllegalStateException if the window cannot be added (most often a
     *         missing overlay privilege).
     */
    public void show(int logicalDisplayId) {
        showView(logicalDisplayId, false);
    }

    /** Show a clearly labelled test pattern without simulated vehicle readings. */
    public void showTest(int logicalDisplayId) {
        showView(logicalDisplayId, true);
    }

    private void showView(int logicalDisplayId, boolean testPattern) {
        hide();
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            throw new IllegalStateException("Cluster overlays need Android 8.0 (API 26) or newer");
        }
        DisplayManager dm =
                (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(logicalDisplayId);
        if (display == null) {
            throw new IllegalArgumentException(
                    "Cluster display not found: " + logicalDisplayId
                            + " — run GwmDisplayAdapter.dump() and fix the config.");
        }

        Context displayContext = appContext.createDisplayContext(display);
        WindowManager wm =
                (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);

        View content = testPattern
            ? new GwmDisplayTestView(displayContext)
            : new ClusterDialView(displayContext);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        try {
            wm.addView(content, lp);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not add cluster overlay (overlay privilege / launch policy?)", e);
        }
        this.view = content;
        this.windowManager = wm;
    }

    /** Push new driver values to the surface. No-op if not shown. */
    public void update(int speedKmh, int fuelPercent, int rangeKm, ClusterDialView.Turn turn) {
        if (view instanceof ClusterDialView) {
            ((ClusterDialView) view).setValues(speedKmh, fuelPercent, rangeKm, turn);
        }
    }

    public boolean isShowing() {
        return view != null;
    }

    public void hide() {
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException ignored) {
                // Already detached.
            }
        }
        view = null;
        windowManager = null;
    }
}
