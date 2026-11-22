/*
 * DiPlay GWM Adapter - live secondary CarPlay surface host.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.dplay.gwm;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Hosts the actual decoder output surface in a non-interactive overlay on a selected display. */
public final class GwmLiveProjectionOverlay {
    public interface SurfaceListener {
        void onSurfaceChanged(Surface surface);
    }

    /** Builds an optional layer drawn above the live map, in the cluster display's context. */
    public interface DecorFactory {
        View create(Context displayContext);
    }

    private final Context appContext;
    private SurfaceView surfaceView;
    private View windowRoot;
    private WindowManager windowManager;
    private int displayId = Display.INVALID_DISPLAY;

    public GwmLiveProjectionOverlay(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void show(int logicalDisplayId, SurfaceListener listener) {
        show(logicalDisplayId, listener, null);
    }

    public void show(int logicalDisplayId, SurfaceListener listener, DecorFactory decor) {
        hide();
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            throw new IllegalStateException("Cluster overlays need Android 8.0 (API 26) or newer");
        }
        if (!Settings.canDrawOverlays(appContext)) {
            throw new IllegalStateException("Display-over-other-apps permission is not granted");
        }
        DisplayManager displays = appContext.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(logicalDisplayId);
        if (display == null || !display.isValid() || logicalDisplayId == Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Selected cluster display is not currently available: " + logicalDisplayId);
        }

        Context displayContext = appContext.createDisplayContext(display);
        WindowManager manager = displayContext.getSystemService(WindowManager.class);
        if (manager == null) throw new IllegalStateException("WindowManager unavailable for display " + logicalDisplayId);
        SurfaceView view = new SurfaceView(displayContext);
        view.setZOrderOnTop(false);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                listener.onSurfaceChanged(holder.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                listener.onSurfaceChanged(holder.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                listener.onSurfaceChanged(null);
            }
        });

        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        layout.gravity = Gravity.TOP | Gravity.START;
        // The map surface sits under the window; views in the same window draw over it.
        View root = view;
        if (decor != null) {
            FrameLayout frame = new FrameLayout(displayContext);
            frame.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            View layer = decor.create(displayContext);
            if (layer != null) {
                frame.addView(layer, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
            root = frame;
        }
        try {
            manager.addView(root, layout);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not add live CarPlay overlay on display " + logicalDisplayId, error);
        }
        surfaceView = view;
        windowRoot = root;
        windowManager = manager;
        displayId = logicalDisplayId;
    }

    public boolean isShowing() {
        return surfaceView != null;
    }

    public int getDisplayId() {
        return displayId;
    }

    public void hide() {
        View view = windowRoot != null ? windowRoot : surfaceView;
        WindowManager manager = windowManager;
        surfaceView = null;
        windowRoot = null;
        windowManager = null;
        displayId = Display.INVALID_DISPLAY;
        if (view != null && manager != null) {
            try {
                manager.removeView(view);
            } catch (RuntimeException ignored) {
                // The display or window may already have been removed.
            }
        }
    }
}