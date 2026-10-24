package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.hud.ClusterMapConfig

/**
 * What DiAuto declares to the phone for the GWM cluster map, built on DiAuto's own
 * [ClusterMapConfig.Profile] so the declaration, decoder and crop are the existing ones.
 *
 * The phone draws its map (and, for Google Maps, its own small cluster cards) inside the content
 * insets. On the GWM layout those insets are the band between the custom navigation card at the
 * top and the instrument strip at the bottom, so the car marker sits in the clear part of the map
 * and the phone's cards stay out from under the custom layer. The map itself still fills the panel.
 */
internal object GwmClusterProfile {
    /** Panel percentages of the approved layout; see [GwmClusterLayerView]. */
    const val SAFE_TOP = 31
    const val SAFE_BOTTOM = 25
    const val SAFE_LEFT = 6
    const val SAFE_RIGHT = 6

    /** Marker steps reuse DiAuto's dashboard settings; this many percent per step. */
    private const val STEP = 5

    /** The profile declared to the phone this connection, for the selected cluster display. */
    fun forSession(context: Context): ClusterMapConfig.Profile {
        val id = GwmSettings(context).clusterDisplayId
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(id) else null
        return forDisplay(context, display)
    }

    /** The same profile for an already resolved [display]; null assumes the H7's 1920x720 panel. */
    fun forDisplay(context: Context, display: Display?): ClusterMapConfig.Profile {
        val settings = App.provide(context).settings
        var w = 0
        var h = 0
        if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            w = size.x
            h = size.y
        }
        return profile(w, h, ClusterMapConfig.Detail.of(settings.clusterMapDetail), 0, 0)
    }

    /**
     * @param panelWidth size of the selected cluster display, or 0 when it is not present yet
     * (then the H7's 8:3 panel is assumed).
     */
    fun profile(panelWidth: Int, panelHeight: Int, detail: ClusterMapConfig.Detail, markerX: Int, markerY: Int): ClusterMapConfig.Profile {
        val size = ClusterMapConfig.Size.NORMAL
        val pw = if (panelWidth > 0 && panelHeight > 0) panelWidth else ClusterMapConfig.PANEL_WIDTH
        val ph = if (panelWidth > 0 && panelHeight > 0) panelHeight else ClusterMapConfig.PANEL_HEIGHT
        // Keep the panel's shape inside the 16:9 codec frame; anything taller than 16:9 is
        // letterboxed left and right instead.
        var contentWidth = size.codecWidth
        var contentHeight = (contentWidth.toLong() * ph / pw).toInt()
        if (contentHeight > size.codecHeight) {
            contentHeight = size.codecHeight
            contentWidth = (contentHeight.toLong() * pw / ph).toInt().coerceAtMost(size.codecWidth)
        }
        return ClusterMapConfig.Profile(
            codecResolution = size.codecResolution,
            codecWidth = size.codecWidth,
            codecHeight = size.codecHeight,
            widthMargin = size.codecWidth - contentWidth,
            heightMargin = size.codecHeight - contentHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            density = detail.dpi,
            contentInsets = insets(contentWidth, contentHeight, markerX, markerY),
        )
    }

    fun insets(contentWidth: Int, contentHeight: Int, markerX: Int, markerY: Int): ClusterMapConfig.Insets {
        val dx = markerX.coerceIn(ClusterMapConfig.HORIZONTAL_STEPS) * STEP
        val dy = markerY.coerceIn(ClusterMapConfig.VERTICAL_STEPS) * STEP
        val (left, right) = shift(SAFE_LEFT, SAFE_RIGHT, dx)
        val (top, bottom) = shift(SAFE_TOP, SAFE_BOTTOM, dy)
        return ClusterMapConfig.Insets(
            top = contentHeight * top / 100,
            bottom = contentHeight * bottom / 100,
            left = contentWidth * left / 100,
            right = contentWidth * right / 100,
        )
    }

    /** Moves an inset pair by [delta] percent, keeping the span and both edges on the panel. */
    internal fun shift(start: Int, end: Int, delta: Int): Pair<Int, Int> {
        val span = 100 - start - end
        val newStart = (start + delta).coerceIn(0, 100 - span)
        return newStart to (100 - span - newStart)
    }
}
