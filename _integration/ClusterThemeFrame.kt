package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * The cluster layer for one connection. It shows the chosen theme and swaps it live when the
 * driver picks another on the Themes screen, carrying over the latest vehicle values and guidance.
 */
internal class ClusterThemeFrame(context: Context, private val connection: ClusterConnection) : FrameLayout(context) {
    private var snapshot: GwmVehicleData.Snapshot? = null
    private var guidance: ClusterTurnGuidance? = null
    private var current: View? = null
    private var currentId: String? = null
    private var stopObserving: (() -> Unit)? = null

    init { show(ClusterThemeStore.selected(context, connection)) }

    fun setSnapshot(next: GwmVehicleData.Snapshot?) {
        snapshot = next
        push()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        guidance = next
        push()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopObserving = ClusterThemeStore.observe(context) {
            post {
                val theme = ClusterThemeStore.selected(context, connection)
                if (theme.id != currentId) show(theme) else current?.invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopObserving?.invoke()
        stopObserving = null
        super.onDetachedFromWindow()
    }

    private fun show(theme: ClusterTheme) {
        current?.let(::removeView)
        val view = create(context, theme, connection)
        current = view
        currentId = theme.id
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        push()
    }

    private fun push() {
        when (val view = current) {
            is ThemedClusterView -> { view.setSnapshot(snapshot); view.setGuidance(guidance) }
            is GwmAndroidAutoClusterView -> { view.setSnapshot(snapshot); view.setGuidance(guidance) }
            is GwmClusterHudView -> { view.setSnapshot(snapshot); view.setGuidance(guidance) }
        }
    }

    companion object {
        fun create(context: Context, theme: ClusterTheme, connection: ClusterConnection, preview: Boolean = false): View =
            when (theme.native) {
                ClusterConnection.ANDROID_AUTO -> GwmAndroidAutoClusterView(context)
                ClusterConnection.CARPLAY -> GwmClusterHudView(context)
                null -> ThemedClusterView(context, theme, connection, preview)
            }
    }
}

/** Gallery pictures drawn by the real renderers over a stand-in map, so a preview never lies. */
internal object ClusterThemePreview {
    private val cache = mutableMapOf<Pair<String, ClusterConnection>, Bitmap>()

    fun bitmap(context: Context, theme: ClusterTheme, connection: ClusterConnection, width: Int = 960, height: Int = 360): Bitmap =
        cache.getOrPut(theme.id to connection) { render(context, theme, connection, width, height) }

    fun render(context: Context, theme: ClusterTheme, connection: ClusterConnection, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawMap(canvas, width.toFloat(), height.toFloat(), connection)
        val view = ClusterThemeFrame.create(context, theme, connection, preview = true)
        val sample = GwmVehicleData.Snapshot(54f, "D", 58f, 523f, 19f, SystemClock.elapsedRealtime(), 34868f, 35f)
        val guidance = ClusterTurnGuidance(3, 0, 300, "Pacific Hwy", arrivalEpochSeconds = System.currentTimeMillis() / 1000 + 1500)
        when (view) {
            is ThemedClusterView -> {
                view.setSnapshot(sample); view.setGuidance(guidance)
                view.setPreviewMusic(ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "Joy.", artist = "RAYE",
                    durationMillis = 200_000L, elapsedMillis = 90_000L, playing = false)))
            }
            is GwmAndroidAutoClusterView -> view.setSnapshot(sample)
            is GwmClusterHudView -> { view.setSnapshot(sample); view.setGuidance(guidance) }
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        view.draw(canvas)
        return bitmap
    }

    /** A plain night map: water, a few roads, the route, and Google's card on Android Auto. */
    private fun drawMap(canvas: Canvas, w: Float, h: Float, connection: ClusterConnection) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w, h, Color.rgb(18, 58, 120), Color.rgb(22, 96, 150), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(70, 210, 230, 255)
        paint.strokeWidth = h * .006f
        for (i in 0 until 9) canvas.drawLine(w * (.05f + i * .11f), 0f, w * (.2f + i * .09f), h, paint)
        for (i in 0 until 5) canvas.drawLine(0f, h * (.15f + i * .2f), w, h * (.1f + i * .19f), paint)
        paint.color = Color.rgb(80, 220, 255)
        paint.strokeWidth = h * .018f
        canvas.drawLine(w * .4f, h, w * .42f, h * .1f, paint)
        if (connection == ClusterConnection.ANDROID_AUTO) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(32, 190, 160)
            canvas.drawRoundRect(w * .757f, h * .42f, w * .935f, h * .56f, h * .02f, h * .02f, paint)
            paint.color = Color.rgb(24, 26, 30)
            canvas.drawRoundRect(w * .757f, h * .55f, w * .935f, h * .63f, h * .02f, h * .02f, paint)
        }
    }
}
