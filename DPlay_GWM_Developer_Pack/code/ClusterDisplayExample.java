package example.gwm;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** Adapted from ClusterProbe OverlayManager.java: Android 9 API-compatible approach. */
public final class ClusterDisplayExample {
    private View shown;
    private WindowManager shownWindowManager;

    /** `content` should be a non-interactive view created for target display context. */
    public void show(Context app, int logicalDisplayId) {
        hide();
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(logicalDisplayId);
        if (display == null) throw new IllegalArgumentException("Display not found: " + logicalDisplayId);
        Context dc = app.createDisplayContext(display);
        WindowManager wm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
        View view = new View(dc); // Replace with your renderer (view must be created for dc).
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        wm.addView(view, lp); // Requires authorized SYSTEM_ALERT_WINDOW / overlay privilege.
        shown = view;
        shownWindowManager = wm;
    }

    public void hide() {
        if (shown != null && shownWindowManager != null) {
            shownWindowManager.removeView(shown);
        }
        shown = null;
        shownWindowManager = null;
    }
}
