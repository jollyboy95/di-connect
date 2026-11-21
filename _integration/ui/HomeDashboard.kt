package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Di-Connect 2.0 Home, phase 1 preview (Nov 2026). A glass home screen for the head unit: the map
 * zone, now playing, the next turn, a dock on the right and the climate bar along the bottom, with
 * the driver's controls on the right for a right-hand-drive car (mirrored positions for left-hand
 * drive; text and pictures are never flipped).
 *
 * Opened only from Settings > Appearance. It is not a Home app: nothing here declares the HOME
 * category, so the car's own launcher is untouched. The map zone does not show a map yet: until map
 * streaming is built it says so and opens Android Auto, and it never draws a made-up map on the car.
 */
class HomeDashboardActivity : Activity() {
    private lateinit var view: HomeDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = HomeDashboardView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        view.stop()
        super.onPause()
    }
}

internal class HomeDashboardView(context: Context) : View(context) {
    // ---- What the screen shows --------------------------------------------------------------------
    data class Media(val source: String, val title: String?, val artist: String?, val playing: Boolean,
        val progress: Float?, val art: Bitmap? = null)
    data class Turn(val icon: Int, val distance: String, val road: String, val eta: String?)
    enum class Popover { NONE, PASSENGER_TEMP, DRIVER_TEMP, FAN, APPS, SETTINGS }

    var connected = false; set(v) { field = v; invalidate() }
    var connectionName = "No phone"; set(v) { field = v; invalidate() }
    var night = true; set(v) { field = v; invalidate() }
    var rightHandDrive = true; set(v) { field = v; invalidate() }
    var media = Media("Bluetooth audio", null, null, false, null); set(v) { field = v; invalidate() }
    var turn: Turn? = null; set(v) { field = v; invalidate() }
    var popover = Popover.NONE; set(v) { field = v; invalidate() }
    /** Shown in the map zone; the preview names itself a placeholder, the car says what is coming. */
    var mapNote = "Tap to open Android Auto · the live map on this screen comes in the next build"
    /** A fixed clock for previews; null = now. */
    var fixedTime: Date? = null

    // Climate, as read from the car (gwm_adapter); keys the car does not answer for stay null and hide.
    var driverTemp: Float? = 22f; set(v) { field = v; invalidate() }
    var passengerTemp: Float? = 22f; set(v) { field = v; invalidate() }
    var fan: Int? = 3; set(v) { field = v; invalidate() }
    var ac: Boolean? = true; set(v) { field = v; invalidate() }
    var auto: Boolean? = false; set(v) { field = v; invalidate() }
    var driverSeat: Int? = 0; set(v) { field = v; invalidate() }
    var passengerSeat: Int? = 0; set(v) { field = v; invalidate() }
    var frontDemist: Boolean? = false; set(v) { field = v; invalidate() }
    var rearDemist: Boolean? = false; set(v) { field = v; invalidate() }

    // ---- Car wiring (only on a GWM head unit) -------------------------------------------------------
    private val main = Handler(Looper.getMainLooper())
    private var stopMusic: (() -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() { invalidate(); main.postDelayed(this, 15_000) }
    }
    private val bar get() = com.andrerinas.openheadunit.gwm.GwmClimateBar
    private val keyAuto = "car.hvac.auto_enable"
    private val keyFront = "car.hvac.front_defrost_enable"
    private val keyRear = "car.hvac.rear_defrost_enable"
    private val onCar: Boolean get() = runCatching { GwmMode.active(context) }.getOrDefault(false)

    fun start() {
        main.removeCallbacks(tick); main.post(tick)
        stopMusic?.invoke()
        stopMusic = ClusterMusicState.observe { snap ->
            val info = snap.info
            val hasTrack = !info.title.isNullOrBlank()
            connected = hasTrack || connected
            media = if (hasTrack) Media(info.sourceApp?.takeIf { it.isNotBlank() } ?: "Phone", info.title, info.artist, info.playing,
                info.durationMillis?.takeIf { it > 0 }?.let { d -> ((info.elapsedMillis ?: 0L).toFloat() / d).coerceIn(0f, 1f) }, snap.artwork)
            else Media("Bluetooth audio", null, null, false, null)
        }
        if (onCar) readClimate() else { driverTemp = null; passengerTemp = null; fan = null; ac = null; auto = null
            driverSeat = null; passengerSeat = null; frontDemist = null; rearDemist = null }
    }

    fun stop() {
        main.removeCallbacks(tick)
        stopMusic?.invoke(); stopMusic = null
    }

    private fun readClimate(delay: Long = 0) {
        val app = context.applicationContext
        Thread({
            if (delay > 0) runCatching { Thread.sleep(delay) }
            val keys = arrayOf(bar.DRIVER_TEMP, bar.PASS_TEMP, bar.FAN, bar.AC, keyAuto, bar.DRIVER_VENT, bar.PASS_VENT, keyFront, keyRear)
            val v = runCatching { bar.readKeys(app, keys) }.getOrDefault(emptyMap())
            main.post {
                driverTemp = v[bar.DRIVER_TEMP]; passengerTemp = v[bar.PASS_TEMP]; fan = v[bar.FAN]?.toInt()
                ac = v[bar.AC]?.let { it.toInt() == 1 }; auto = v[keyAuto]?.let { it.toInt() == 1 }
                driverSeat = v[bar.DRIVER_VENT]?.toInt(); passengerSeat = v[bar.PASS_VENT]?.toInt()
                frontDemist = v[keyFront]?.let { it.toInt() == 1 }; rearDemist = v[keyRear]?.let { it.toInt() == 1 }
            }
        }, "home-climate-read").start()
    }

    private fun write(key: String, value: Float) {
        if (!onCar) return
        val wire = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
        Thread({ runCatching { bar.write(key, wire) }; main.post { readClimate(1200) } }, "home-climate-write").start()
    }

    // ---- Look --------------------------------------------------------------------------------------
    private val W = 1280f
    private val H = 720f
    private var s = 1f
    private var ox = 0f
    private var oy = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val r = RectF()
    private val path = Path()
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val icons = HashMap<Int, Drawable?>()

    private val ink get() = Color.WHITE
    private val quiet get() = if (night) Color.argb(150, 226, 230, 244) else Color.argb(205, 255, 255, 255)
    private val accent get() = if (night) Color.rgb(176, 168, 255) else Color.rgb(255, 255, 255)

    // Mirror a horizontal position for left-hand drive: positions move, drawing never flips.
    private fun mx(x: Float): Float = if (rightHandDrive) x else W - x

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        s = min(width / W, height / H)
        ox = (width - W * s) / 2f
        oy = (height - H * s) / 2f
        drawBackdrop(canvas)
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        drawTopBar(canvas)
        drawDock(canvas)
        if (connected) drawMapZone(canvas) else drawLocalZone(canvas)
        drawMediaCard(canvas)
        drawLowerCard(canvas)
        drawClimateBar(canvas)
        when (popover) {
            Popover.DRIVER_TEMP -> drawTempPopover(canvas, true)
            Popover.PASSENGER_TEMP -> drawTempPopover(canvas, false)
            Popover.FAN -> drawFanPopover(canvas)
            Popover.APPS -> drawApps(canvas)
            Popover.SETTINGS -> drawSettings(canvas)
            Popover.NONE -> Unit
        }
        canvas.restore()
    }

    private fun drawBackdrop(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = if (night) LinearGradient(0f, 0f, w, h, Color.rgb(24, 27, 40), Color.rgb(36, 38, 60), Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, w, h, Color.rgb(122, 138, 182), Color.rgb(160, 148, 206), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // Soft light: a misty blue glow upper left and a lavender one lower right.
        fun glow(x: Float, y: Float, rad: Float, color: Int) {
            p.shader = RadialGradient(x, y, rad, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p)
        }
        if (night) {
            glow(w * .18f, h * .12f, w * .45f, Color.argb(70, 98, 132, 214))
            glow(w * .82f, h * .92f, w * .5f, Color.argb(80, 150, 112, 220))
            glow(w * .55f, h * .45f, w * .35f, Color.argb(30, 200, 200, 255))
        } else {
            glow(w * .15f, h * .1f, w * .5f, Color.argb(120, 196, 220, 255))
            glow(w * .85f, h * .95f, w * .5f, Color.argb(120, 214, 178, 255))
            glow(w * .5f, h * .5f, w * .4f, Color.argb(60, 255, 255, 255))
        }
        p.shader = null
    }

    /** A glass panel: a soft lit fill, a fine bright edge and a faint shadow below. */
    private fun glass(c: Canvas, rect: RectF, radius: Float, strength: Float = 1f) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.argb(if (night) 70 else 40, 0, 0, 20)
        c.drawRoundRect(rect.left, rect.top + 6f, rect.right, rect.bottom + 8f, radius, radius, p)
        val top = ((if (night) 34 else 70) * strength).toInt()
        val bottom = ((if (night) 14 else 38) * strength).toInt()
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.argb(top, 255, 255, 255), Color.argb(bottom, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.argb(if (night) 70 else 130, 255, 255, 255), Color.argb(if (night) 18 else 40, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.FILL
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = ink, face: Typeface? = null,
                     align: Paint.Align = Paint.Align.LEFT, maxWidth: Float = 0f) {
        t.textSize = size; t.color = color; t.typeface = face ?: Typeface.DEFAULT; t.textAlign = align
        var shown = s
        if (maxWidth > 0f && t.measureText(shown) > maxWidth) {
            while (shown.isNotEmpty() && t.measureText("$shown…") > maxWidth) shown = shown.dropLast(1)
            shown = "$shown…"
        }
        c.drawText(shown, x, y, t)
    }

    private fun icon(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int = ink) {
        val d = icons.getOrPut(res) { runCatching { ContextCompat.getDrawable(context, res)?.mutate() }.getOrNull() } ?: return
        d.setTint(color)
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        d.draw(c)
    }

    private val closePopover: () -> Unit = { popover = Popover.NONE }

    private fun hit(rect: RectF, action: () -> Unit) { hits.add(Pair(RectF(rect), action)) }

    private fun circleButton(c: Canvas, cx: Float, cy: Float, radius: Float, label: String, action: () -> Unit) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (night) 30 else 55, 255, 255, 255)
        c.drawCircle(cx, cy, radius, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = Color.argb(if (night) 60 else 110, 255, 255, 255)
        c.drawCircle(cx, cy, radius, p); p.style = Paint.Style.FILL
        text(c, label, cx, cy + radius * .36f, radius * 1.05f, ink, light, Paint.Align.CENTER)
        hit(RectF(cx - radius - 6, cy - radius - 6, cx + radius + 6, cy + radius + 6), action)
    }

    // ---- Top bar and dock ------------------------------------------------------------------------
    private fun drawTopBar(c: Canvas) {
        val now = fixedTime ?: Date()
        val clock = SimpleDateFormat("h:mm a", Locale.US).format(now)
        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)
        val left = if (rightHandDrive) 28f else 120f
        text(c, clock, left, 40f, 24f, ink, medium)
        t.textSize = 24f; t.typeface = medium
        text(c, day, left + t.measureText(clock) + 16f, 40f, 17f, quiet)
        // Connection status, beside the clock.
        t.textSize = 17f
        var x = left + t.measureText(clock) + 16f + Paint().apply { textSize = 17f }.measureText(day) + 32f
        fun chip(label: String, on: Boolean) {
            t.textSize = 15f; t.typeface = medium
            val w = t.measureText(label) + 40f
            r.set(x, 20f, x + w, 48f); glass(c, r, 14f, .8f)
            p.color = if (on) Color.rgb(120, 220, 160) else Color.argb(160, 255, 255, 255)
            c.drawCircle(x + 16f, 34f, 4.5f, p)
            text(c, label, x + 28f, 39.5f, 15f, ink, medium)
            x += w + 10f
        }
        chip(connectionName, connected)
        // Small brand mark above the dock.
        val dockX = mx(1222f)
        icon(c, R.drawable.ic_di_connect_monochrome, dockX - 36f, 34f, 40f, ink)
        text(c, "Di-Connect", dockX - 20f, 39.5f, 14f, quiet, medium)
    }

    private fun drawDock(c: Canvas) {
        val cx = mx(1222f)
        r.set(cx - 38f, 64f, cx + 38f, 584f)
        glass(c, r, 30f)
        val items = listOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_dp_home, "Home") { popover = Popover.NONE },
            Triple(R.drawable.ic_dp_navigation, "Map") { openDiConnect() },
            Triple(R.drawable.ic_dp_audio, "Music") { openDiConnect() },
            Triple(0, "Apps") { popover = if (popover == Popover.APPS) Popover.NONE else Popover.APPS },
            Triple(R.drawable.ic_dp_car, "Vehicle") { openVehicle() },
            Triple(R.drawable.ic_dp_advanced, "Settings") { popover = if (popover == Popover.SETTINGS) Popover.NONE else Popover.SETTINGS },
        )
        val step = 520f / items.size
        items.forEachIndexed { i, (res, label, action) ->
            val cy = 64f + step * (i + .5f)
            val active = (i == 0 && popover == Popover.NONE) || (label == "Apps" && popover == Popover.APPS) ||
                (label == "Settings" && popover == Popover.SETTINGS)
            if (active) { r.set(cx - 30f, cy - 36f, cx + 30f, cy + 36f); glass(c, r, 22f, 2.2f) }
            if (res == 0) drawGridIcon(c, cx, cy - 9f, 26f, ink) else icon(c, res, cx, cy - 9f, 28f, ink)
            text(c, label, cx, cy + 22f, 12.5f, if (active) ink else quiet, medium, Paint.Align.CENTER)
            hit(RectF(cx - 38f, cy - step / 2, cx + 38f, cy + step / 2), action)
        }
    }

    private fun drawGridIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = size * .09f; p.color = color
        val g = size * .12f; val q = (size - g) / 2f
        for (row in 0..1) for (col in 0..1) {
            val x0 = cx - size / 2 + col * (q + g); val y0 = cy - size / 2 + row * (q + g)
            c.drawRoundRect(x0, y0, x0 + q, y0 + q, q * .3f, q * .3f, p)
        }
        p.style = Paint.Style.FILL
    }

    // ---- Map zone (phone connected) -------------------------------------------------------------
    private val zone get() = if (rightHandDrive) RectF(20f, 64f, 856f, 584f) else RectF(424f, 64f, 1260f, 584f)
    private val column get() = if (rightHandDrive) RectF(872f, 64f, 1168f, 584f) else RectF(112f, 64f, 408f, 584f)

    private fun drawMapZone(c: Canvas) {
        val z = zone
        glass(c, z, 28f, .7f)
        // No map is drawn: until the phone's map is streamed here, the zone says so plainly.
        c.save()
        path.reset(); path.addRoundRect(z, 28f, 28f, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(z.left, z.top, z.right, z.bottom, Color.argb(if (night) 60 else 50, 70, 90, 150),
            Color.argb(if (night) 40 else 30, 120, 90, 190), Shader.TileMode.CLAMP)
        c.drawRect(z, p); p.shader = null
        c.restore()
        icon(c, R.drawable.ic_dp_navigation, z.centerX(), z.centerY() - 40f, 64f, Color.argb(200, 255, 255, 255))
        text(c, "Android Auto", z.centerX(), z.centerY() + 24f, 30f, ink, light, Paint.Align.CENTER)
        text(c, mapNote, z.centerX(), z.centerY() + 56f, 16f, quiet, null, Paint.Align.CENTER, z.width() - 80f)
        hit(z) { openDiConnect() }
        // Search goes to the real search inside Android Auto.
        r.set(z.left + 20f, z.top + 20f, z.left + 360f, z.top + 72f); glass(c, r, 26f, 2.4f)
        icon(c, R.drawable.ic_dp_search, r.left + 30f, r.centerY(), 24f, ink)
        text(c, "Search in Google Maps", r.left + 54f, r.centerY() + 6.5f, 18f, ink)
        hit(r) { openDiConnect() }
        // Full screen projection.
        r.set(z.right - 72f, z.top + 20f, z.right - 20f, z.top + 72f); glass(c, r, 26f, 2.4f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f; p.color = ink; p.strokeCap = Paint.Cap.ROUND
        val ex = r.centerX(); val ey = r.centerY(); val e = 10f
        c.drawLine(ex - e, ey - e + 6, ex - e, ey - e); c.drawLine(ex - e, ey - e, ex - e + 6, ey - e)
        c.drawLine(ex + e, ey + e - 6, ex + e, ey + e); c.drawLine(ex + e, ey + e, ex + e - 6, ey + e)
        c.drawLine(ex + e - 6, ey - e, ex + e, ey - e); c.drawLine(ex + e, ey - e, ex + e, ey - e + 6)
        c.drawLine(ex - e, ey + e - 6, ex - e, ey + e); c.drawLine(ex - e, ey + e, ex - e + 6, ey + e)
        p.style = Paint.Style.FILL
        hit(r) { openDiConnect() }
    }

    private fun Canvas.drawLine(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(x0, y0, x1, y1, p)

    // ---- Local zone (no phone) -------------------------------------------------------------------
    private fun drawLocalZone(c: Canvas) {
        val z = zone
        glass(c, z, 28f, .7f)
        val now = fixedTime ?: Date()
        val hm = SimpleDateFormat("h:mm", Locale.US).format(now)
        val ampm = SimpleDateFormat("a", Locale.US).format(now)
        text(c, hm, z.left + 44f, z.top + 150f, 112f, ink, light)
        t.textSize = 112f; t.typeface = light
        text(c, ampm, z.left + 52f + t.measureText(hm), z.top + 150f, 30f, quiet, light)
        text(c, SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), z.left + 48f, z.top + 194f, 22f, quiet)
        text(c, "No phone connected", z.left + 48f, z.top + 262f, 26f, ink, medium)
        text(c, "Radio, Bluetooth, USB, your apps and climate all work without a phone.", z.left + 48f, z.top + 294f, 16f, quiet, null,
            Paint.Align.LEFT, z.width() - 96f)
        // Connect buttons.
        fun pill(x: Float, label: String, primary: Boolean, action: () -> Unit): Float {
            t.textSize = 18f; t.typeface = medium
            val w = t.measureText(label) + 56f
            r.set(x, z.top + 322f, x + w, z.top + 378f); glass(c, r, 28f, if (primary) 3f else 1.6f)
            text(c, label, r.centerX(), r.centerY() + 6.5f, 18f, ink, medium, Paint.Align.CENTER)
            hit(r, action); return x + w + 14f
        }
        var x = z.left + 48f
        x = pill(x, "Connect Android Auto", true) { openDiConnect() }
        pill(x, "CarPlay", false) { openDiConnect() }
        // Local sources along the foot of the zone.
        val sources = listOf("FM / DAB radio", "Bluetooth", "USB", "All apps")
        val tw = (z.width() - 48f * 2 - 14f * 3) / 4f
        sources.forEachIndexed { i, label ->
            r.set(z.left + 48f + i * (tw + 14f), z.bottom - 112f, z.left + 48f + i * (tw + 14f) + tw, z.bottom - 40f)
            glass(c, r, 20f, 1.4f)
            text(c, label, r.centerX(), r.centerY() + 6.5f, 18f, ink, medium, Paint.Align.CENTER)
            hit(r) { if (label == "All apps") showApps() else openSource(label) }
        }
    }

    // ---- Right column: now playing and the next turn or vehicle shortcuts -------------------------
    private fun drawMediaCard(c: Canvas) {
        val col = column
        r.set(col.left, col.top, col.right, col.top + 330f); glass(c, r, 26f)
        val card = RectF(r)
        // Source selector.
        r.set(card.left + 18f, card.top + 18f, card.right - 18f, card.top + 56f); glass(c, r, 19f, 1.5f)
        text(c, media.source, r.left + 18f, r.centerY() + 6f, 16f, ink, medium, Paint.Align.LEFT, r.width() - 60f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = quiet; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(r.right - 28f, r.centerY() - 3f, r.right - 22f, r.centerY() + 3f); c.drawLine(r.right - 22f, r.centerY() + 3f, r.right - 16f, r.centerY() - 3f)
        p.style = Paint.Style.FILL
        hit(r) { openDiConnect() }
        // Artwork, or a soft tile when there is none.
        val art = RectF(card.left + 18f, card.top + 72f, card.left + 110f, card.top + 164f)
        val bitmap = media.art?.takeUnless { it.isRecycled }
        if (bitmap != null) {
            c.save(); path.reset(); path.addRoundRect(art, 16f, 16f, Path.Direction.CW); c.clipPath(path)
            c.drawBitmap(bitmap, null, art, p); c.restore()
        } else {
            p.shader = LinearGradient(art.left, art.top, art.right, art.bottom, Color.rgb(112, 128, 200), Color.rgb(170, 130, 210), Shader.TileMode.CLAMP)
            c.drawRoundRect(art, 16f, 16f, p); p.shader = null
            icon(c, R.drawable.ic_dp_audio, art.centerX(), art.centerY(), 40f, Color.argb(220, 255, 255, 255))
        }
        hit(art) { openDiConnect() }
        val tx = art.right + 16f; val tw = card.right - 18f - tx
        text(c, media.title ?: "Nothing playing", tx, card.top + 106f, 21f, ink, medium, Paint.Align.LEFT, tw)
        text(c, media.artist ?: "Choose a source", tx, card.top + 134f, 16f, quiet, null, Paint.Align.LEFT, tw)
        // Progress.
        val py = card.top + 194f
        p.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(card.left + 22f, py - 2.5f, card.right - 22f, py + 2.5f, 3f, 3f, p)
        media.progress?.let { f ->
            p.color = accent; c.drawRoundRect(card.left + 22f, py - 2.5f, card.left + 22f + (card.width() - 44f) * f, py + 2.5f, 3f, 3f, p)
        }
        // Controls: previous, play or pause, next.
        val cy = card.top + 268f
        circleButton(c, card.centerX() - 86f, cy, 26f, "") { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        skip(c, card.centerX() - 86f, cy, false)
        r.set(card.centerX() - 38f, cy - 38f, card.centerX() + 38f, cy + 38f); glass(c, r, 38f, 3f)
        p.color = ink
        if (media.playing) {
            c.drawRoundRect(card.centerX() - 11f, cy - 13f, card.centerX() - 4f, cy + 13f, 2f, 2f, p)
            c.drawRoundRect(card.centerX() + 4f, cy - 13f, card.centerX() + 11f, cy + 13f, 2f, 2f, p)
        } else {
            path.reset(); path.moveTo(card.centerX() - 8f, cy - 14f); path.lineTo(card.centerX() + 14f, cy); path.lineTo(card.centerX() - 8f, cy + 14f); path.close()
            c.drawPath(path, p)
        }
        hit(r) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        circleButton(c, card.centerX() + 86f, cy, 26f, "") { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        skip(c, card.centerX() + 86f, cy, true)
    }

    /** A previous or next symbol: a triangle and a bar. */
    private fun skip(c: Canvas, cx: Float, cy: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        p.color = ink; p.style = Paint.Style.FILL
        path.reset(); path.moveTo(cx - 7f * d, cy - 9f); path.lineTo(cx + 5f * d, cy); path.lineTo(cx - 7f * d, cy + 9f); path.close()
        c.drawPath(path, p)
        if (forward) c.drawRect(cx + 6f, cy - 9f, cx + 9f, cy + 9f, p) else c.drawRect(cx - 9f, cy - 9f, cx - 6f, cy + 9f, p)
    }

    private fun drawLowerCard(c: Canvas) {
        val col = column
        r.set(col.left, col.top + 346f, col.right, col.bottom); glass(c, r, 26f)
        val card = RectF(r)
        val next = turn
        if (connected && next != null) {
            text(c, "NEXT TURN", card.left + 22f, card.top + 34f, 13f, quiet, medium)
            icon(c, next.icon, card.left + 52f, card.top + 92f, 60f, ink)
            text(c, next.distance, card.left + 100f, card.top + 96f, 34f, ink, light)
            text(c, next.road, card.left + 100f, card.top + 124f, 17f, quiet, null, Paint.Align.LEFT, card.width() - 120f)
            next.eta?.let { text(c, it, card.left + 22f, card.bottom - 20f, 16f, quiet) }
            hit(card) { openDiConnect() }
        } else {
            text(c, "VEHICLE", card.left + 22f, card.top + 34f, 13f, quiet, medium)
            val rows = listOf("Vehicle settings", "System settings")
            rows.forEachIndexed { i, label ->
                r.set(card.left + 18f, card.top + 50f + i * 58f, card.right - 18f, card.top + 98f + i * 58f); glass(c, r, 16f, 1.4f)
                text(c, label, r.left + 18f, r.centerY() + 6f, 17f, ink, medium)
                text(c, "›", r.right - 20f, r.centerY() + 7f, 22f, quiet, null, Paint.Align.CENTER)
                hit(r) { if (i == 0) openVehicle() else showSettings() }
            }
        }
    }

    // ---- Climate bar ------------------------------------------------------------------------------
    /** Cool blue at 16°, near white at 22°, warm amber at 26°, a muted red at 32°. */
    private fun tempColor(v: Float): Int {
        fun mix(a: Int, b: Int, f: Float): Int {
            val k = f.coerceIn(0f, 1f)
            return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
        }
        val blue = Color.rgb(130, 186, 255); val white = Color.rgb(240, 242, 250)
        val amber = Color.rgb(255, 190, 120); val red = Color.rgb(250, 128, 110)
        return when {
            v <= 22f -> mix(blue, white, (v - 16f) / 6f)
            v <= 26f -> mix(white, amber, (v - 22f) / 4f)
            else -> mix(amber, red, (v - 26f) / 6f)
        }
    }

    private val barRect = RectF(20f, 600f, 1260f, 704f)

    private fun drawClimateBar(c: Canvas) {
        glass(c, barRect, 34f, 1.1f)
        val cy = barRect.centerY()
        // Passenger on the left and driver on the right in a right-hand-drive car; mirrored otherwise.
        tempGroup(c, mx(146f), cy, false)
        tempGroup(c, mx(1140f), cy, true)
        seat(c, mx(312f), cy, false)
        seat(c, mx(976f), cy, true)
        // Fan: − and + change it at once; the middle opens the slider.
        fan?.let { level ->
            circleButton(c, 402f, cy, 22f, "−") { setFan(level - 1) }
            r.set(436f, cy - 34f, 616f, cy + 34f)
            if (popover == Popover.FAN) glass(c, r, 20f, 2f)
            icon(c, R.drawable.ic_dc_fan, 462f, cy, 28f, ink)
            for (i in 1..7) {
                p.color = if (i <= level) accent else Color.argb(55, 255, 255, 255)
                val bx = 486f + i * 16f; val bh = 8f + i * 3.4f
                c.drawRoundRect(bx, cy + 14f - bh, bx + 9f, cy + 14f, 3f, 3f, p)
            }
            hit(RectF(r)) { popover = if (popover == Popover.FAN) Popover.NONE else Popover.FAN }
            circleButton(c, 650f, cy, 22f, "+") { setFan(level + 1) }
        }
        var x = 712f
        fun toggle(label: String, on: Boolean?, action: (Boolean) -> Unit) {
            val state = on ?: return
            t.textSize = 16f; t.typeface = medium
            val w = max(56f, t.measureText(label) + 28f)
            r.set(x, cy - 24f, x + w, cy + 24f)
            if (state) glass(c, r, 24f, 2.6f)
            text(c, label, r.centerX(), cy + 6f, 16f, if (state) ink else quiet, medium, Paint.Align.CENTER)
            hit(RectF(r)) { action(!state) }
            x += w + 8f
        }
        toggle("A/C", ac) { ac = it; write(bar.AC, if (it) 1f else 0f) }
        toggle("AUTO", auto) { auto = it; write(keyAuto, if (it) 1f else 0f) }
        toggle("Front", frontDemist) { frontDemist = it; write(keyFront, if (it) 1f else 0f) }
        toggle("Rear", rearDemist) { rearDemist = it; write(keyRear, if (it) 1f else 0f) }
    }

    private fun tempGroup(c: Canvas, cx: Float, cy: Float, driver: Boolean) {
        val v = (if (driver) driverTemp else passengerTemp) ?: return
        val key = if (driver) bar.DRIVER_TEMP else bar.PASS_TEMP
        fun set(next: Float) {
            val value = next.coerceIn(16f, 32f)
            if (driver) driverTemp = value else passengerTemp = value
            write(key, value)
        }
        circleButton(c, cx - 86f, cy, 24f, "−") { set(v - .5f) }
        circleButton(c, cx + 86f, cy, 24f, "+") { set(v + .5f) }
        r.set(cx - 56f, cy - 40f, cx + 56f, cy + 40f)
        val open = popover == if (driver) Popover.DRIVER_TEMP else Popover.PASSENGER_TEMP
        if (open) glass(c, r, 20f, 2f)
        text(c, if (driver) "DRIVER" else "PASSENGER", cx, cy - 16f, 11.5f, quiet, medium, Paint.Align.CENTER)
        text(c, String.format(Locale.US, "%.1f°", v), cx, cy + 22f, 34f, tempColor(v), light, Paint.Align.CENTER)
        hit(RectF(r)) {
            val mine = if (driver) Popover.DRIVER_TEMP else Popover.PASSENGER_TEMP
            popover = if (popover == mine) Popover.NONE else mine
        }
    }

    private fun seat(c: Canvas, cx: Float, cy: Float, driver: Boolean) {
        val level = (if (driver) driverSeat else passengerSeat) ?: return
        val key = if (driver) bar.DRIVER_VENT else bar.PASS_VENT
        r.set(cx - 30f, cy - 30f, cx + 30f, cy + 30f)
        if (level > 0) glass(c, r, 18f, 2f)
        // A seat, drawn as its back and cushion.
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f; p.strokeCap = Paint.Cap.ROUND; p.color = ink
        path.reset()
        path.moveTo(cx - 9f, cy - 14f); path.lineTo(cx - 5f, cy + 6f); path.lineTo(cx + 11f, cy + 6f)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (i in 1..3) {
            p.color = if (i <= level) Color.rgb(140, 200, 255) else Color.argb(60, 255, 255, 255)
            c.drawRoundRect(cx - 12f + (i - 1) * 9f, cy + 13f, cx - 5f + (i - 1) * 9f, cy + 17f, 2f, 2f, p)
        }
        hit(RectF(r)) {
            val next = if (level >= 3 || level < 0) 0 else level + 1
            if (driver) driverSeat = next else passengerSeat = next
            write(key, next.toFloat())
        }
    }

    private fun showApps() { popover = Popover.APPS }
    private fun showSettings() { popover = Popover.SETTINGS }

    private fun setFan(level: Int) {
        val v = level.coerceIn(1, 7)
        fan = v; write(bar.FAN, v.toFloat())
    }

    // ---- Popovers ---------------------------------------------------------------------------------
    private fun popoverFrame(c: Canvas, cx: Float, w: Float, h: Float): RectF {
        val left = (cx - w / 2).coerceIn(20f, 1260f - w)
        val frame = RectF(left, 596f - h, left + w, 584f)
        // Taps outside the popover close it (registered first, so the popover's own hits win).
        hits.add(Pair(RectF(0f, 0f, W, H), closePopover))
        glass(c, frame, 26f, 2.6f)
        r.set(frame.right - 52f, frame.top + 12f, frame.right - 12f, frame.top + 52f)
        text(c, "×", r.centerX(), r.centerY() + 9f, 28f, quiet, light, Paint.Align.CENTER)
        hit(RectF(r)) { popover = Popover.NONE }
        return frame
    }

    private fun drawTempPopover(c: Canvas, driver: Boolean) {
        val v = (if (driver) driverTemp else passengerTemp) ?: return
        val f = popoverFrame(c, mx(if (driver) 1140f else 146f), 520f, 170f)
        text(c, if (driver) "Driver temperature" else "Passenger temperature", f.left + 28f, f.top + 40f, 16f, quiet, medium)
        text(c, String.format(Locale.US, "%.1f°", v), f.left + 28f, f.top + 92f, 44f, tempColor(v), light)
        val x0 = f.left + 40f; val x1 = f.right - 40f; val ty = f.top + 128f
        p.shader = LinearGradient(x0, 0f, x1, 0f, intArrayOf(tempColor(16f), tempColor(22f), tempColor(26f), tempColor(32f)),
            floatArrayOf(0f, .375f, .625f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(x0, ty - 5f, x1, ty + 5f, 5f, 5f, p); p.shader = null
        val kx = x0 + (x1 - x0) * (v - 16f) / 16f
        p.color = Color.WHITE; c.drawCircle(kx, ty, 15f, p)
        p.color = tempColor(v); c.drawCircle(kx, ty, 9f, p)
        text(c, "16°", x0, f.bottom - 12f, 13f, quiet, null, Paint.Align.CENTER)
        text(c, "24°", (x0 + x1) / 2, f.bottom - 12f, 13f, quiet, null, Paint.Align.CENTER)
        text(c, "32°", x1, f.bottom - 12f, 13f, quiet, null, Paint.Align.CENTER)
        slider = Slider(x0, x1, ty, 16f, 32f, .5f) { value ->
            if (driver) driverTemp = value else passengerTemp = value
            write(if (driver) bar.DRIVER_TEMP else bar.PASS_TEMP, value)
        }
        hit(RectF(x0 - 20f, ty - 30f, x1 + 20f, ty + 30f)) { }
    }

    private fun drawFanPopover(c: Canvas) {
        val level = fan ?: return
        val f = popoverFrame(c, 526f, 520f, 170f)
        text(c, "Fan speed", f.left + 28f, f.top + 40f, 16f, quiet, medium)
        text(c, level.toString(), f.left + 28f, f.top + 92f, 44f, ink, light)
        val x0 = f.left + 40f; val x1 = f.right - 40f; val ty = f.top + 128f
        p.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(x0, ty - 5f, x1, ty + 5f, 5f, 5f, p)
        val kx = x0 + (x1 - x0) * (level - 1) / 6f
        p.color = accent; c.drawRoundRect(x0, ty - 5f, kx, ty + 5f, 5f, 5f, p)
        for (i in 1..7) { val sx = x0 + (x1 - x0) * (i - 1) / 6f; text(c, "$i", sx, f.bottom - 12f, 13f, quiet, null, Paint.Align.CENTER) }
        p.color = Color.WHITE; c.drawCircle(kx, ty, 15f, p)
        slider = Slider(x0, x1, ty, 1f, 7f, 1f) { value -> setFan(value.roundToInt()) }
        hit(RectF(x0 - 20f, ty - 30f, x1 + 20f, ty + 30f)) { }
        auto?.let { on ->
            r.set(f.left + 140f, f.top + 56f, f.left + 236f, f.top + 98f)
            glass(c, r, 21f, if (on) 3f else 1.4f)
            text(c, "AUTO", r.centerX(), r.centerY() + 6f, 16f, ink, medium, Paint.Align.CENTER)
            hit(RectF(r)) { auto = !on; write(keyAuto, if (on) 0f else 1f) }
        }
    }

    private fun overlayFrame(c: Canvas, title: String): RectF {
        val frame = RectF(zone.left, zone.top, column.right, zone.bottom).let {
            if (rightHandDrive) RectF(20f, 64f, 1168f, 584f) else RectF(112f, 64f, 1260f, 584f)
        }
        hits.add(Pair(RectF(0f, 0f, W, H), closePopover))
        p.color = Color.argb(if (night) 120 else 70, 10, 12, 24); c.drawRoundRect(frame, 28f, 28f, p)
        glass(c, frame, 28f, 2f)
        text(c, title, frame.left + 32f, frame.top + 52f, 28f, ink, light)
        r.set(frame.right - 64f, frame.top + 16f, frame.right - 16f, frame.top + 64f)
        text(c, "×", r.centerX(), r.centerY() + 10f, 32f, quiet, light, Paint.Align.CENTER)
        hit(RectF(r)) { popover = Popover.NONE }
        hit(RectF(frame)) { }
        return frame
    }

    private var apps: List<Triple<String, String, Drawable?>>? = null

    private fun drawApps(c: Canvas) {
        val f = overlayFrame(c, "Apps")
        val list = apps ?: loadApps().also { apps = it }
        // The factory launcher stays one tap away.
        r.set(f.right - 260f, f.top + 20f, f.right - 80f, f.top + 64f); glass(c, r, 22f, 1.8f)
        text(c, "Factory Home", r.centerX(), r.centerY() + 6f, 16f, ink, medium, Paint.Align.CENTER)
        hit(RectF(r)) { openFactoryHome() }
        if (list.isEmpty()) {
            text(c, "No other apps found", f.left + 32f, f.top + 110f, 18f, quiet); return
        }
        val cols = 8; val cell = (f.width() - 64f) / cols
        list.take(cols * 3).forEachIndexed { i, (label, pkg, d) ->
            val cx = f.left + 32f + cell * (i % cols + .5f); val cy = f.top + 130f + (i / cols) * 128f
            d?.let { it.setBounds((cx - 32f).toInt(), (cy - 32f).toInt(), (cx + 32f).toInt(), (cy + 32f).toInt()); it.draw(c) }
            text(c, label, cx, cy + 56f, 14f, ink, null, Paint.Align.CENTER, cell - 12f)
            hit(RectF(cx - cell / 2, cy - 44f, cx + cell / 2, cy + 64f)) { launch(pkg) }
        }
    }

    private fun drawSettings(c: Canvas) {
        val f = overlayFrame(c, "Settings")
        val rows = listOf<Triple<String, String, () -> Unit>>(
            Triple("Vehicle settings", "The car's own vehicle menu") { openVehicle() },
            Triple("System settings", "The head unit's display, sound and connection menu") { openByLabel("system setting", "setting"); Unit },
            Triple("Android settings", "The full Android settings app") { start(Intent(android.provider.Settings.ACTION_SETTINGS)) },
            Triple("Di-Connect settings", "Projection, cluster, HUD, themes and diagnostics") { openDiConnect() },
        )
        rows.forEachIndexed { i, (title, sub, action) ->
            r.set(f.left + 32f, f.top + 88f + i * 96f, f.right - 32f, f.top + 168f + i * 96f); glass(c, r, 20f, 1.4f)
            text(c, title, r.left + 24f, r.top + 36f, 20f, ink, medium)
            text(c, sub, r.left + 24f, r.top + 62f, 15f, quiet)
            text(c, "›", r.right - 28f, r.centerY() + 9f, 28f, quiet, null, Paint.Align.CENTER)
            hit(RectF(r), action)
        }
    }

    // ---- Touch ------------------------------------------------------------------------------------
    private class Slider(val x0: Float, val x1: Float, val y: Float, val min: Float, val max: Float, val step: Float, val set: (Float) -> Unit)
    private var slider: Slider? = null
    private var dragging = false
    private var lastSlide = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - ox) / s
        val y = (event.y - oy) / s
        val sl = slider?.takeIf { popover == Popover.DRIVER_TEMP || popover == Popover.PASSENGER_TEMP || popover == Popover.FAN }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = sl != null && abs(y - sl.y) < 32f && x in (sl.x0 - 24f)..(sl.x1 + 24f)
                if (dragging) slide(sl!!, x)
                return true
            }
            MotionEvent.ACTION_MOVE -> { if (dragging) slide(sl ?: return true, x); return true }
            MotionEvent.ACTION_UP -> {
                if (dragging) { dragging = false; slide(sl ?: return true, x, force = true); return true }
                hits.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun slide(sl: Slider, x: Float, force: Boolean = false) {
        val f = ((x - sl.x0) / (sl.x1 - sl.x0)).coerceIn(0f, 1f)
        val raw = sl.min + (sl.max - sl.min) * f
        val v = (raw / sl.step).roundToInt() * sl.step
        // The car gets at most a few writes a second while a finger moves; the last one always goes.
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastSlide > 250) { lastSlide = now; sl.set(v) }
        invalidate()
    }

    // ---- Opening things ---------------------------------------------------------------------------
    private fun start(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openDiConnect() = start(Intent(context, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    private fun launch(pkg: String) {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) }
    }

    /** The car's settings app names are not confirmed yet, so they are found by their launcher label. */
    private fun openByLabel(vararg words: String): Boolean {
        val list = apps ?: loadApps().also { apps = it }
        for (word in words) {
            val match = list.firstOrNull { it.first.lowercase(Locale.ROOT).contains(word) } ?: continue
            launch(match.second); return true
        }
        return false
    }

    private fun openVehicle() {
        if (!openByLabel("vehicle", "car setting")) openDiConnect()
    }

    private fun openSource(label: String) {
        val found = when (label) {
            "FM / DAB radio" -> openByLabel("radio", "fm")
            "Bluetooth" -> openByLabel("bluetooth music", "bt music", "bluetooth")
            "USB" -> openByLabel("usb", "music")
            else -> false
        }
        if (!found) showApps()
    }

    private fun openFactoryHome() {
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        val factory = homes.firstOrNull { it.activityInfo.packageName != context.packageName }?.activityInfo ?: return
        start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(factory.packageName, factory.name))
    }

    private fun mediaKey(code: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun loadApps(): List<Triple<String, String, Drawable?>> {
        val pm: PackageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) null
                else Triple(info.loadLabel(pm)?.toString() ?: pkg, pkg, runCatching { info.loadIcon(pm) }.getOrNull())
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }
}
