package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.hud.ClusterMapConfig
import com.andrerinas.openheadunit.hud.DashboardGuidance
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import org.dplay.gwm.GwmDisplayTestView

/**
 * GWM outputs for one Android Auto session: the cluster map window on the selected cluster
 * display, and the head-up arrow strip.
 *
 * The cluster window uses the route that works on the H7 with DiPlay (jollyboy95/d-play-gwm-h7,
 * GwmLiveProjectionOverlay): a non-interactive TYPE_APPLICATION_OVERLAY window added through a
 * display context of the selected logical display. Its picture is DiAuto's existing Android Auto
 * cluster stream: the same CLUSTER video sink declared by
 * [com.andrerinas.openheadunit.aap.protocol.messages.ClusterDisplayServices], decoded by
 * [com.andrerinas.openheadunit.AppComponent.clusterVideoDecoder] and cropped with
 * [ClusterMapConfig.cropMatrix]. No second receiver or decoder is created here.
 *
 * Main thread only.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object GwmOutputs {
    private const val GUIDANCE_TICK_MS = 500L

    /** Plain-language results for the GWM settings screen. */
    @Volatile var clusterStatus: String = "Not started yet: connect a phone with the cluster output on."
        private set
    @Volatile var hudStatus: String = "Not started yet."
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var running = false

    private var clusterManager: WindowManager? = null
    private var clusterRoot: View? = null
    private var clusterDisplayId = Display.INVALID_DISPLAY
    private var layer: GwmClusterLayerView? = null
    // Di-Connect: D-Play's cluster layer and HUD, used instead of DiAuto's when D-Play provides them.
    private var dplayLayer: com.andrerinas.openheadunit.DiConnectViews.Layer? = null
    private var dplayLayerNavCard = false
    private var dplayHud: com.andrerinas.openheadunit.DiConnectViews.Layer? = null
    private var surface: Surface? = null

    private var hudManager: WindowManager? = null
    private var hudView: GwmHeadUpView? = null
    private var hudDisplayId = Display.INVALID_DISPLAY

    private var testManager: WindowManager? = null
    private var testView: View? = null

    private val vehicleListener: (GwmVehicleData.Snapshot?) -> Unit = { snapshot ->
        handler.post { layer?.setSnapshot(snapshot) }
    }

    private val guidanceTick = object : Runnable {
        override fun run() {
            val next = DashboardGuidance.current()
            layer?.setGuidance(next)
            hudView?.setGuidance(next)
            val mapped = com.andrerinas.openheadunit.DiConnectViews.from(next)
            // Google Maps draws its own card into the cluster video; Di-Connect's is opt-in in Android Auto.
            dplayLayer?.setGuidance(if (dplayLayerNavCard) mapped else null)
            dplayHud?.setGuidance(mapped)
            handler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    val sessionRunning: Boolean get() = running

    /** A session's screen is up: show whatever GWM output is switched on. Safe to call repeatedly. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        running = true
        val gwm = GwmSettings(app)
        if (GwmMode.clusterRoute(app)) showCluster(app, gwm) else {
            hideCluster("cluster output is off")
            clusterStatus = "Off: turn on \"Cluster map on display ${gwm.clusterDisplayId}\" and reconnect."
        }
        if (gwm.hudEnabled) showHud(app, gwm) else hideHud("off")
        handler.removeCallbacks(guidanceTick)
        if (clusterRoot != null || hudView != null || dplayHud != null) handler.post(guidanceTick)
    }

    /** The session ended: remove every window, stop polling and free the decoder's surface. */
    fun stop(reason: String = "session ended") {
        running = false
        handler.removeCallbacks(guidanceTick)
        hideCluster(reason)
        hideHud(reason)
        GwmMusicState.clear()
        DashboardGuidance.latest = null
    }

    /** Settings changed during a session: rebuild the windows with the new choices. */
    fun refresh(context: Context) {
        if (!running) return
        hideCluster("settings changed")
        hideHud("settings changed")
        start(context)
    }

    // --- Cluster -------------------------------------------------------------------------------

    private fun showCluster(app: Context, gwm: GwmSettings) {
        val target = gwm.clusterDisplayId
        if (clusterRoot != null && clusterDisplayId == target) return
        hideCluster("moving to display $target")
        val display = usableDisplay(app, target) { clusterStatus = it } ?: return
        val displayContext = app.createDisplayContext(display)
        val windows = displayContext.getSystemService(WindowManager::class.java) ?: run {
            clusterStatus = "No window manager for display $target."
            return
        }
        val profile = GwmClusterProfile.forDisplay(app, display)
        val root = FrameLayout(displayContext).apply { setBackgroundColor(Color.BLACK) }
        root.addView(mapView(app, displayContext, display, profile), FrameLayout.LayoutParams(-1, -1))
        if (gwm.styledLayer) {
            val dplay = com.andrerinas.openheadunit.DiConnectViews.cluster?.invoke(displayContext)
            if (dplay != null) {
                root.addView(dplay.view, FrameLayout.LayoutParams(-1, -1))
                dplayLayer = dplay
                dplayLayerNavCard = gwm.navCard
            } else {
                val view = GwmClusterLayerView(displayContext, gwm)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                layer = view
            }
        }
        try {
            windows.addView(root, overlayParams(opaque = true))
        } catch (error: RuntimeException) {
            layer = null
            dplayLayer?.release()
            dplayLayer = null
            clusterStatus = "Display $target refused the overlay window: ${error.javaClass.simpleName}: ${error.message}"
            AppLog.w("GWM cluster: $clusterStatus")
            return
        }
        clusterManager = windows
        clusterRoot = root
        clusterDisplayId = target
        // D-Play's layer reads the car's values itself.
        if (layer != null && gwm.vehicleStrip) GwmVehicleData.addListener(app, vehicleListener)
        val size = sizeOf(display)
        clusterStatus = "Showing on display $target \"${display.name}\" ${size.x}x${size.y}; " +
            "map ${profile.contentWidth}x${profile.contentHeight} in a ${profile.codecWidth}x${profile.codecHeight} stream." +
            if (gwm.styledLayer) " Custom layer on." else " Map only."
        AppLog.i("GWM cluster: $clusterStatus")
    }

    private fun mapView(app: Context, displayContext: Context, display: Display, profile: ClusterMapConfig.Profile): TextureView {
        val texture = TextureView(displayContext)
        fun applyCrop(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val m = ClusterMapConfig.cropMatrix(profile, width, height)
            texture.setTransform(Matrix().apply { setScale(m[0], m[1]); postTranslate(m[2], m[3]) })
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(profile.codecWidth, profile.codecHeight)
                applyCrop(width, height)
                val s = Surface(st)
                surface = s
                AppLog.i("GWM cluster: map surface ready ${width}x$height on display ${display.displayId}")
                App.provide(app).clusterVideoDecoder.setSurface(s)
                // A codec on a new surface needs a keyframe before it can draw anything.
                App.provide(app).commManager.requestClusterKeyframe()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCrop(width, height)
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseSurface(app, "GWM cluster window closed")
                return true
            }
        }
        return texture
    }

    private fun releaseSurface(app: Context, reason: String) {
        val s = surface ?: return
        surface = null
        App.provide(app).clusterVideoDecoder.stopIfCurrentSurface(s, reason)
        s.release()
    }

    private fun hideCluster(reason: String) {
        GwmVehicleData.removeListener(vehicleListener)
        val root = clusterRoot
        val windows = clusterManager
        clusterRoot = null
        clusterManager = null
        layer = null
        dplayLayer?.release()
        dplayLayer = null
        clusterDisplayId = Display.INVALID_DISPLAY
        if (root != null && windows != null) {
            runCatching { windows.removeViewImmediate(root) }
            AppLog.i("GWM cluster: window removed ($reason)")
            clusterStatus = "Closed: $reason."
        }
        // The TextureView's destroy callback normally frees this; a vanished display may skip it.
        appContext?.let { releaseSurface(it, reason) }
    }

    // --- Head-up display ----------------------------------------------------------------------

    private fun showHud(app: Context, gwm: GwmSettings) {
        val target = gwm.hudDisplayId
        val anchor = GwmHeadUpView.Anchor.entries.getOrElse(gwm.hudAnchor) { GwmHeadUpView.Anchor.CENTRE }
        if ((hudView != null || dplayHud != null) && hudDisplayId == target) {
            hudView?.setAnchor(anchor)
            hudView?.setArea(gwm.hudArea)
            return
        }
        hideHud("moving to display $target")
        val display = usableDisplay(app, target) { hudStatus = it } ?: return
        val displayContext = app.createDisplayContext(display)
        val windows = displayContext.getSystemService(WindowManager::class.java) ?: run {
            hudStatus = "No window manager for display $target."
            return
        }
        val dplay = com.andrerinas.openheadunit.DiConnectViews.hud?.invoke(displayContext, gwm.hudAnchor)
        val view: android.view.View = dplay?.view
            ?: GwmHeadUpView(displayContext).apply { setAnchor(anchor); setArea(gwm.hudArea) }
        try {
            windows.addView(view, overlayParams(opaque = dplay != null))
        } catch (error: RuntimeException) {
            dplay?.release()
            hudStatus = "Display $target refused the overlay window: ${error.javaClass.simpleName}: ${error.message}"
            AppLog.w("GWM HUD: $hudStatus")
            return
        }
        hudManager = windows
        if (dplay != null) dplayHud = dplay else hudView = view as GwmHeadUpView
        hudDisplayId = target
        val size = sizeOf(display)
        hudStatus = "Showing on display $target \"${display.name}\" ${size.x}x${size.y}; draws only while a route is active."
        AppLog.i("GWM HUD: $hudStatus")
    }

    private fun hideHud(reason: String) {
        val view: android.view.View? = hudView ?: dplayHud?.view
        val windows = hudManager
        hudView = null
        dplayHud?.release()
        dplayHud = null
        hudManager = null
        hudDisplayId = Display.INVALID_DISPLAY
        if (view != null && windows != null) {
            runCatching { windows.removeViewImmediate(view) }
            hudStatus = "Closed: $reason."
        }
    }

    // --- Parked display test ------------------------------------------------------------------

    /**
     * Shows a parked test pattern on [displayId]: DiPlay's labelled pattern, or with [hudGrid] the
     * lettered HUD grid and current arrow area. Null on success, else the reason.
     */
    fun showTest(context: Context, displayId: Int, hudGrid: GwmSettings.HudArea? = null): String? {
        hideTest()
        val app = context.applicationContext
        var problem: String? = null
        val display = usableDisplay(app, displayId) { problem = it } ?: return problem
        val displayContext = app.createDisplayContext(display)
        val windows = displayContext.getSystemService(WindowManager::class.java) ?: return "No window manager for display $displayId."
        val view: View = if (hudGrid != null) GwmHudGridView(displayContext, hudGrid) else GwmDisplayTestView(displayContext)
        return try {
            windows.addView(view, overlayParams(opaque = false))
            testManager = windows
            testView = view
            null
        } catch (error: RuntimeException) {
            "${error.javaClass.simpleName}: ${error.message}"
        }
    }

    fun hideTest() {
        val view = testView
        val windows = testManager
        testView = null
        testManager = null
        if (view != null && windows != null) runCatching { windows.removeViewImmediate(view) }
    }

    val testShowing: Boolean get() = testView != null
    val hudGridShowing: Boolean get() = testView is GwmHudGridView

    // --- Helpers ------------------------------------------------------------------------------

    /** The display, if it exists, is not the centre screen, and DiAuto may draw over other apps. */
    private fun usableDisplay(app: Context, id: Int, report: (String) -> Unit): Display? {
        if (id == Display.DEFAULT_DISPLAY) {
            report("Display 0 is the centre screen; pick the cluster or HUD display instead.")
            return null
        }
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(id)
        if (display == null || !display.isValid) {
            report("Display $id is not present now. Displays: ${describeDisplays(app)}")
            return null
        }
        if (!AppPermissions.isOverlayGranted(app)) {
            report("\"Display over other apps\" is not allowed for DiAuto, so nothing can be drawn on display $id.")
            return null
        }
        return display
    }

    private fun overlayParams(opaque: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        if (opaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun describeDisplays(context: Context): String =
        context.getSystemService(DisplayManager::class.java)?.displays
            ?.joinToString { d -> val s = sizeOf(d); "${d.displayId}:\"${d.name}\" ${s.x}x${s.y}" }.orEmpty()

    fun sizeOf(display: Display): Point = Point().also {
        @Suppress("DEPRECATION")
        display.getRealSize(it)
    }
}
