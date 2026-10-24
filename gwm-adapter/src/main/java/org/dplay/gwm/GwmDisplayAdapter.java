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
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import java.util.Locale;

/**
 * Enumerates the vehicle's logical displays and helps pick the cluster display.
 *
 * <p>IDs are NOT assumed: {@link #dump()} is the first thing to run on a new car.
 * {@link #pickClusterDisplayId} resolves a configured hint against what the device
 * actually reports, so a wrong guess fails loudly rather than silently drawing on
 * the main screen.
 */
public final class GwmDisplayAdapter {

    private final DisplayManager dm;
    private final Context context;

    public GwmDisplayAdapter(Context context) {
        this.context = context.getApplicationContext();
        this.dm = (DisplayManager) this.context.getSystemService(Context.DISPLAY_SERVICE);
    }

    public static boolean isGwmTargetDevice() {
        String identity = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL + " "
                + Build.DEVICE + " " + Build.PRODUCT + " " + Build.DISPLAY + " " + Build.FINGERPRINT)
                .toLowerCase(Locale.ROOT);
        return identity.contains("gwm") || identity.contains("haval")
                || identity.contains("great wall") || identity.contains("greatwall")
                || identity.contains("b11");
    }

    public static int defaultClusterDisplayId() {
        return isGwmTargetDevice() ? GwmAdapterConfig.H7_CLUSTER_DISPLAY_ID : Display.INVALID_DISPLAY;
    }

    /** Human-readable list of every display the device reports. Log this on the car. */
    public String dump() {
        StringBuilder sb = new StringBuilder("GWM display dump:\n");
        if (dm == null) return sb.append("  DisplayManager unavailable\n").toString();
        sb.append("  overlayPermission=")
            .append(android.provider.Settings.canDrawOverlays(context))
            .append(" (required for live GWM overlay)\n");
        for (Display d : dm.getDisplays()) {
            Point size = new Point();
            d.getRealSize(size);
            sb.append(String.format(Locale.US,
                    "  id=%d  name=\"%s\"  size=%dx%d  state=%d  flags=0x%x  presentation=%s  valid=%s%n",
                    d.getDisplayId(), d.getName(), size.x, size.y, d.getState(),
                    d.getFlags(), (d.getFlags() & Display.FLAG_PRESENTATION) != 0, d.isValid()));
        }
        return sb.toString();
    }

    /**
     * Resolve the cluster display id from a config, matching candidates against the
     * displays actually present.
     *
     * @return a valid logical display id, or -1 if no candidate matched (caller must
     *         then inspect {@link #dump()} and update the config — never fall back to
     *         the primary display silently).
     */
    public int pickClusterDisplayId(GwmAdapterConfig config) {
        Display[] displays = dm.getDisplays();
        // Pass 1: exact preferred logical id with matching size.
        for (GwmAdapterConfig.DisplayHint hint : config.clusterCandidates) {
            if (hint.preferredLogicalId < 0) continue;
            for (Display d : displays) {
                if (d.getDisplayId() == hint.preferredLogicalId && sizeMatches(d, hint)) {
                    return d.getDisplayId();
                }
            }
        }
        // Pass 2: any non-primary display matching the expected size.
        for (GwmAdapterConfig.DisplayHint hint : config.clusterCandidates) {
            for (Display d : displays) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY && sizeMatches(d, hint)) {
                    return d.getDisplayId();
                }
            }
        }
        return -1;
    }

    private static boolean sizeMatches(Display d, GwmAdapterConfig.DisplayHint hint) {
        if (hint.expectWidth <= 0 || hint.expectHeight <= 0) return true;
        Point size = new Point();
        d.getRealSize(size);
        // Allow either orientation.
        return (size.x == hint.expectWidth && size.y == hint.expectHeight)
                || (size.x == hint.expectHeight && size.y == hint.expectWidth);
    }

    public Display getDisplay(int logicalId) {
        return dm == null ? null : dm.getDisplay(logicalId);
    }

    public Display[] getDisplays() {
        return dm == null ? new Display[0] : dm.getDisplays();
    }
}
