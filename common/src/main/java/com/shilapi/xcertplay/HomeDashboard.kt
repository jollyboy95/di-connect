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
    /** [pkg] is set for a car or app media session (not the projection), so Home can open and control it. */
    data class Media(val source: String, val title: String?, val artist: String?, val playing: Boolean,
        val progress: Float?, val art: Bitmap? = null, val pkg: String? = null)
    data class Turn(val icon: Int, val distance: String, val road: String, val eta: String?)
    enum class Popover { NONE, PASSENGER_TEMP, DRIVER_TEMP, FAN, APPS, SETTINGS, SOURCES }

    var connected = false; set(v) { field = v; invalidate() }
    var connectionName = "No phone"; set(v) { field = v; invalidate() }
    var night = true; set(v) { field = v; invalidate() }
    var rightHandDrive = true; set(v) { field = v; invalidate() }
    var media = Media("No source", null, null, false, null); set(v) { field = v; invalidate() }
    /** Which projection is live; drives the map zone title and what the source list offers. */
    var projection = HomeSources.Projection.NONE; set(v) { field = v; invalidate() }
    /** The source playing now, ticked in the source list; null when nothing is. */
    var activeSource: HomeSources.Source? = null; set(v) { field = v; invalidate() }
    /** Sources that can play right now; a missing one is not shown at all. */
    var sources: List<HomeSources.Source> = listOf(HomeSources.Source.RADIO); set(v) { field = v; invalidate() }
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
    /** Both zones follow the driver while on; the passenger zone is independent while off. */
    var sync: Boolean? = false; set(v) { field = v; invalidate() }
    var eco: Boolean? = null; set(v) { field = v; invalidate() }
    /** False while the climate system is switched off; the temperatures then show as Off. */
    var climateOn: Boolean? = null; set(v) { field = v; invalidate() }

    // Vehicle chips along the top; null hides a chip.
    var outsideTemp: Float? = null; set(v) { field = v; invalidate() }
    var rangeKm: Float? = null; set(v) { field = v; invalidate() }
    var hotspotOn: Boolean? = null; set(v) { field = v; invalidate() }
    /** Moving faster than walking pace: the app grid waits until the car is parked. */
    var moving = false; set(v) { field = v; invalidate() }

    // ---- Car wiring (only on a GWM head unit) -------------------------------------------------------
    private val main = Handler(Looper.getMainLooper())
    private var stopMusic: (() -> Unit)? = null
    private var running = false
    private val tick = object : Runnable {
        override fun run() { invalidate(); readHotspot(); main.postDelayed(this, 15_000) }
    }
    private val mediaPoll = object : Runnable {
        override fun run() { readFactoryMedia(); if (running) main.postDelayed(this, 2_000) }
    }
    private val vehicleListener: (GwmVehicleData.Snapshot?) -> Unit = { snap ->
        main.post {
            outsideTemp = snap?.outsideTempC; rangeKm = snap?.rangeKm?.takeIf { it > 0f }
            moving = (snap?.speedKph ?: 0f) > 3f
        }
    }
    private var carPlayMedia: Media? = null
    private var androidAutoMusic: com.andrerinas.openheadunit.DiConnectViews.Music? = null
    private var factoryMedia: Media? = null
    private var stopAndroidAutoMusic: (() -> Unit)? = null
    private var usbMounted = false
    /** Every second while Home shows: the projection state comes from Di-Connect's own session state. */
    private val projectionPoll = object : Runnable {
        override fun run() { refreshProjection(); if (running) main.postDelayed(this, 1_000) }
    }
    private val bar get() = com.andrerinas.openheadunit.gwm.GwmClimateBar
    private val keyAuto = "car.hvac.auto_enable"
    private val keyFront = "car.hvac.front_defrost_enable"
    private val keyRear = "car.hvac.rear_defrost_enable"
    private val keySync = HomeFactoryTargets.CLIMATE_SYNC
    private val keyEco = HomeFactoryTargets.CLIMATE_ECO
    private val keyPower = "car.hvac.power_mode"
    private val onCar: Boolean get() = runCatching { GwmMode.active(context) }.getOrDefault(false)

    fun start() {
        running = true
        main.removeCallbacks(tick); main.post(tick)
        main.removeCallbacks(mediaPoll); main.post(mediaPoll)
        main.removeCallbacks(projectionPoll); main.post(projectionPoll)
        stopMusic?.invoke()
        // CarPlay's now playing (CarPlayMediaKeys publishes it here).
        stopMusic = ClusterMusicState.observe { snap ->
            val info = snap.info
            carPlayMedia = if (!info.title.isNullOrBlank()) Media(info.sourceApp?.takeIf { it.isNotBlank() }?.let { "$it · CarPlay" } ?: "CarPlay",
                info.title, info.artist, info.playing,
                info.durationMillis?.takeIf { it > 0 }?.let { d -> ((info.elapsedMillis ?: 0L).toFloat() / d).coerceIn(0f, 1f) }, snap.artwork)
            else null
            refreshMedia()
        }
        // Android Auto's now playing straight from the phone's media channel, cluster layer or not.
        stopAndroidAutoMusic?.invoke()
        stopAndroidAutoMusic = runCatching {
            com.andrerinas.openheadunit.DiConnectViews.observeMusic { music -> main.post { androidAutoMusic = music; refreshMedia() } }
        }.getOrNull()
        if (onCar) {
            readClimate()
            GwmVehicleData.addListener(context, vehicleListener)
        } else { driverTemp = null; passengerTemp = null; fan = null; ac = null; auto = null
            driverSeat = null; passengerSeat = null; frontDemist = null; rearDemist = null; sync = null; eco = null }
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        main.removeCallbacks(mediaPoll)
        main.removeCallbacks(projectionPoll)
        stopMusic?.invoke(); stopMusic = null
        stopAndroidAutoMusic?.invoke(); stopAndroidAutoMusic = null
        GwmVehicleData.removeListener(vehicleListener)
    }

    /** Reads the live projection from Di-Connect's session state, never from Bluetooth. */
    private fun refreshProjection() {
        val aa = runCatching { com.andrerinas.openheadunit.DiConnectAndroidAuto.isConnected(context) }.getOrDefault(false)
        val next = HomeSources.projection(aa, CarPlayBackgroundSession.active, CarPlayBackgroundSession.hasSession())
        val phone = if (next == HomeSources.Projection.ANDROID_AUTO)
            runCatching { com.andrerinas.openheadunit.DiConnectAndroidAuto.phoneName(context) }.getOrNull() else null
        if (next != projection) projection = next
        connected = HomeSources.connected(next)
        connectionName = HomeSources.connectionLabel(next, phone)
        mapNote = when (next) {
            HomeSources.Projection.CARPLAY -> "Tap to open CarPlay · the live map on this screen comes in a later build"
            else -> "Tap to open Android Auto · the live map on this screen comes in a later build"
        }
        usbMounted = usbDriveMounted()
        refreshMedia()
    }

    /** True while Android Auto's own audio is playing; the phone does not always send a play state. */
    private fun androidAutoAudioPlaying(): Boolean {
        androidAutoMusic?.takeIf { !it.title.isNullOrBlank() }?.let { return it.playing }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching { audio.isMusicActive }.getOrDefault(false)
    }

    /**
     * What the music card shows: the live projection's music, a playing car source, or the
     * projection connected with nothing reported. The phone sometimes sends no song details; then
     * the card says so instead of claiming nothing is playing.
     */
    private fun refreshMedia() {
        val p = projection
        val aaPlaying = p == HomeSources.Projection.ANDROID_AUTO && androidAutoAudioPlaying()
        val cpPlaying = p == HomeSources.Projection.CARPLAY && carPlayMedia?.playing == true
        val factory = factoryMedia
        val active = HomeSources.active(p, aaPlaying || cpPlaying, factory?.pkg, factory?.playing == true)
        activeSource = active
        sources = HomeSources.available(p, radioInstalled(), usbMounted)
        media = when (active) {
            HomeSources.Source.ANDROID_AUTO -> androidAutoMedia(aaPlaying)
            HomeSources.Source.CARPLAY -> carPlayMedia ?: Media("CarPlay", null, "CarPlay", false, null)
            HomeSources.Source.RADIO, HomeSources.Source.USB -> factory ?: Media(active.label, null, null, false, null)
            null -> factory ?: Media("No source", null, null, false, null)
        }
    }

    private fun androidAutoMedia(playing: Boolean): Media {
        val m = androidAutoMusic
        if (m != null && !m.title.isNullOrBlank()) {
            val progress = m.durationMillis?.takeIf { it > 0 }?.let { d -> ((m.positionMillis ?: 0L).toFloat() / d).coerceIn(0f, 1f) }
            return Media("Android Auto", m.title, m.artist ?: m.album, m.playing, progress, m.artwork)
        }
        return if (playing) Media("Android Auto", "Playing on Android Auto", "The phone has not sent the song details", true, null)
        else Media("Android Auto", null, "Android Auto", false, null)
    }

    private val radioFitted: Boolean by lazy {
        HomeFactoryTargets.candidates(HomeFactoryTargets.Target.RADIO).any { (pkg, cls) ->
            cls != null && runCatching { context.packageManager.resolveActivity(Intent().setClassName(pkg, cls), 0) != null }.getOrDefault(false)
        }
    }
    private fun radioInstalled(): Boolean = radioFitted

    /** A removable drive (USB stick) mounted now; without one the USB player has nothing to play. */
    private fun usbDriveMounted(): Boolean = runCatching {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        sm.storageVolumes.any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
    }.getOrDefault(false)

    private fun readFactoryMedia() {
        val app = context.applicationContext
        Thread({
            val now = runCatching { HomeMediaSessions.current(app) }.getOrNull()
            main.post {
                factoryMedia = now?.let { Media(it.source, it.title, it.artist, it.playing, it.progress, it.art, it.packageName) }
                refreshMedia()
            }
        }, "home-media-read").start()
    }

    private fun readHotspot() {
        if (!onCar) return
        val app = context.applicationContext
        Thread({
            val state = runCatching { com.andrerinas.openheadunit.utils.SoftApStateReader.read(app) }.getOrNull()
            main.post {
                hotspotOn = when (state) {
                    com.andrerinas.openheadunit.aap.SoftApState.ENABLED -> true
                    com.andrerinas.openheadunit.aap.SoftApState.NOT_ENABLED -> false
                    else -> null
                }
            }
        }, "home-hotspot-read").start()
    }

    private fun readClimate(delay: Long = 0) {
        val app = context.applicationContext
        Thread({
            if (delay > 0) runCatching { Thread.sleep(delay) }
            val keys = arrayOf(bar.DRIVER_TEMP, bar.PASS_TEMP, bar.FAN, bar.AC, keyAuto, bar.DRIVER_VENT, bar.PASS_VENT, keyFront, keyRear,
                keySync, keyEco, keyPower)
            val v = runCatching { bar.readKeys(app, keys) }.getOrDefault(emptyMap())
            main.post {
                driverTemp = v[bar.DRIVER_TEMP]; passengerTemp = v[bar.PASS_TEMP]; fan = v[bar.FAN]?.toInt()
                ac = v[bar.AC]?.let { it.toInt() == 1 }; auto = v[keyAuto]?.let { it.toInt() == 1 }
                driverSeat = v[bar.DRIVER_VENT]?.toInt(); passengerSeat = v[bar.PASS_VENT]?.toInt()
                frontDemist = v[keyFront]?.let { it.toInt() == 1 }; rearDemist = v[keyRear]?.let { it.toInt() == 1 }
                sync = v[keySync]?.let { it.toInt() == 1 }; eco = v[keyEco]?.let { it.toInt() == 1 }
                climateOn = v[keyPower]?.let { it.toInt() != 0 }
            }
        }, "home-climate-read").start()
    }

    private fun write(key: String, value: Float) = writeAll(key to value)

    /** Sends the changes in order on one thread, then reads the car back. Only allow-listed climate keys go out. */
    private fun writeAll(vararg changes: Pair<String, Float>) {
        if (!onCar) return
        val allowed = changes.filter { HomeFactoryTargets.climateWritable(it.first) }
        if (allowed.isEmpty()) return
        Thread({
            allowed.forEach { (key, value) ->
                val wire = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
                runCatching { bar.write(key, wire) }
            }
            main.post { readClimate(1200) }
        }, "home-climate-write").start()
    }

    /**
     * Sets one zone. The driver's zone carries the passenger's along while Sync is on, as the car
     * does. A passenger change first turns Sync off, so the two zones become independent.
     */
    private fun setZoneTemp(driver: Boolean, next: Float) {
        val value = next.coerceIn(16f, 32f)
        if (driver) {
            driverTemp = value
            if (sync == true) passengerTemp = value
            write(bar.DRIVER_TEMP, value)
        } else {
            passengerTemp = value
            if (sync == true) { sync = false; writeAll(keySync to 0f, bar.PASS_TEMP to value) } else write(bar.PASS_TEMP, value)
        }
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
            Popover.SOURCES -> drawSources(canvas)
            Popover.NONE -> Unit
        }
        canvas.restore()
    }

    private fun drawBackdrop(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = if (night) LinearGradient(0f, 0f, w, h, Color.rgb(24, 27, 40), Color.rgb(36, 38, 60), Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, w, h, Color.rgb(104, 120, 170), Color.rgb(146, 130, 196), Shader.TileMode.CLAMP)
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
        // Note: the shader is multiplied by the shadow colour's alpha above; the values are tuned with that.
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

    /** A floating panel over other content: nearly opaque, so what is behind never shows through the words. */
    private fun panel(c: Canvas, rect: RectF, radius: Float) {
        p.style = Paint.Style.FILL; p.shader = null
        p.color = Color.argb(if (night) 110 else 70, 0, 0, 20)
        c.drawRoundRect(rect.left, rect.top + 8f, rect.right, rect.bottom + 14f, radius, radius, p)
        // A shader is multiplied by the paint's own alpha, so the paint must be opaque here.
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            if (night) Color.argb(246, 52, 55, 84) else Color.argb(246, 128, 132, 186),
            if (night) Color.argb(246, 38, 40, 64) else Color.argb(246, 112, 110, 170), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = Color.argb(if (night) 80 else 140, 255, 255, 255)
        c.drawRoundRect(rect, radius, radius, p)
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
        val dayX = left + t.measureText(clock) + 16f
        text(c, day, dayX, 40f, 17f, quiet)
        // Connection status, beside the clock.
        t.textSize = 17f; t.typeface = Typeface.DEFAULT
        var x = dayX + t.measureText(day) + 24f
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
        // Car readings, each shown only while the car answers for it.
        outsideTemp?.let { chip(String.format(Locale.US, "%.0f° outside", it), true) }
        rangeKm?.let { chip(String.format(Locale.US, "%.0f km", it), true) }
        hotspotOn?.let { chip(if (it) "Hotspot on" else "Hotspot off", it) }
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
        val carPlay = projection == HomeSources.Projection.CARPLAY
        text(c, if (carPlay) "CarPlay" else "Android Auto", z.centerX(), z.centerY() + 24f, 30f, ink, light, Paint.Align.CENTER)
        text(c, mapNote, z.centerX(), z.centerY() + 56f, 16f, quiet, null, Paint.Align.CENTER, z.width() - 80f)
        hit(z) { openProjection() }
        // Search goes to the real search inside Android Auto.
        r.set(z.left + 20f, z.top + 20f, z.left + 360f, z.top + 72f); glass(c, r, 26f, 2.4f)
        icon(c, R.drawable.ic_dp_search, r.left + 30f, r.centerY(), 24f, ink)
        text(c, if (carPlay) "Search in Maps" else "Search in Google Maps", r.left + 54f, r.centerY() + 6.5f, 18f, ink)
        hit(r) { openProjection() }
        // Full screen projection.
        r.set(z.right - 72f, z.top + 20f, z.right - 20f, z.top + 72f); glass(c, r, 26f, 2.4f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f; p.color = ink; p.strokeCap = Paint.Cap.ROUND
        val ex = r.centerX(); val ey = r.centerY(); val e = 10f
        c.drawLine(ex - e, ey - e + 6, ex - e, ey - e); c.drawLine(ex - e, ey - e, ex - e + 6, ey - e)
        c.drawLine(ex + e, ey + e - 6, ex + e, ey + e); c.drawLine(ex + e, ey + e, ex + e - 6, ey + e)
        c.drawLine(ex + e - 6, ey - e, ex + e, ey - e); c.drawLine(ex + e, ey - e, ex + e, ey - e + 6)
        c.drawLine(ex - e, ey + e - 6, ex - e, ey + e); c.drawLine(ex - e, ey + e, ex - e + 6, ey + e)
        p.style = Paint.Style.FILL
        hit(r) { openProjection() }
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
        text(c, "Radio, USB, your apps and climate all work without a phone.", z.left + 48f, z.top + 294f, 16f, quiet, null,
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
        // Only sources that can play now; the car's Bluetooth audio is never offered.
        val local = sources.filter { it == HomeSources.Source.RADIO || it == HomeSources.Source.USB }
        val tiles = local.map { it.label } + "All apps"
        val tw = (z.width() - 48f * 2 - 14f * 3) / 4f
        tiles.forEachIndexed { i, label ->
            r.set(z.left + 48f + i * (tw + 14f), z.bottom - 112f, z.left + 48f + i * (tw + 14f) + tw, z.bottom - 40f)
            glass(c, r, 20f, 1.4f)
            text(c, label, r.centerX(), r.centerY() + 6.5f, 18f, ink, medium, Paint.Align.CENTER)
            val source = local.getOrNull(i)
            hit(r) { if (source == null) showApps() else selectSource(source) }
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
        hit(r) { popover = if (popover == Popover.SOURCES) Popover.NONE else Popover.SOURCES }
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
        hit(art) { openMediaSource() }
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
        circleButton(c, card.centerX() - 86f, cy, 26f, "") { transport(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
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
        hit(r) { transport(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        circleButton(c, card.centerX() + 86f, cy, 26f, "") { transport(KeyEvent.KEYCODE_MEDIA_NEXT) }
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
                hit(r) { if (i == 0) openVehicle() else openSystemSettings() }
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
        seat(c, mx(298f), cy, false)
        seat(c, mx(996f), cy, true)
        // Fan: − and + change it at once; the middle opens the slider.
        fan?.let { level ->
            circleButton(c, 370f, cy, 22f, "−") { setFan(level - 1) }
            r.set(400f, cy - 34f, 578f, cy + 34f)
            if (popover == Popover.FAN) glass(c, r, 20f, 2f)
            icon(c, R.drawable.ic_dc_fan, 428f, cy, 28f, ink)
            for (i in 1..7) {
                p.color = if (i <= level) accent else Color.argb(55, 255, 255, 255)
                val bx = 438f + i * 17f; val bh = 8f + i * 3.4f
                c.drawRoundRect(bx, cy + 14f - bh, bx + 9f, cy + 14f, 3f, 3f, p)
            }
            hit(RectF(r)) { popover = if (popover == Popover.FAN) Popover.NONE else Popover.FAN }
            circleButton(c, 608f, cy, 22f, "+") { setFan(level + 1) }
        }
        var x = 646f
        fun toggle(label: String, on: Boolean?, action: (Boolean) -> Unit) {
            val state = on ?: return
            t.textSize = 15f; t.typeface = medium
            val w = max(52f, t.measureText(label) + 24f)
            r.set(x, cy - 24f, x + w, cy + 24f)
            if (state) glass(c, r, 24f, 2.6f)
            text(c, label, r.centerX(), cy + 5.5f, 15f, if (state) ink else quiet, medium, Paint.Align.CENTER)
            hit(RectF(r)) { action(!state) }
            x += w + 6f
        }
        toggle("A/C", ac) { ac = it; write(bar.AC, if (it) 1f else 0f) }
        toggle("AUTO", auto) { auto = it; write(keyAuto, if (it) 1f else 0f) }
        toggle("Demist", frontDemist) { frontDemist = it; write(keyFront, if (it) 1f else 0f) }
        toggle("Rear", rearDemist) { rearDemist = it; write(keyRear, if (it) 1f else 0f) }
    }

    private fun tempGroup(c: Canvas, cx: Float, cy: Float, driver: Boolean) {
        val v = (if (driver) driverTemp else passengerTemp) ?: return
        circleButton(c, cx - 86f, cy, 24f, "−") { setZoneTemp(driver, v - .5f) }
        circleButton(c, cx + 86f, cy, 24f, "+") { setZoneTemp(driver, v + .5f) }
        r.set(cx - 56f, cy - 40f, cx + 56f, cy + 40f)
        val open = popover == if (driver) Popover.DRIVER_TEMP else Popover.PASSENGER_TEMP
        if (open) glass(c, r, 20f, 2f)
        val zone = if (driver) "DRIVER" else "PASSENGER"
        val label = if (!driver && sync == true) "$zone · SYNC" else zone
        text(c, label, cx, cy - 16f, 11.5f, quiet, medium, Paint.Align.CENTER)
        if (climateOn == false) text(c, "Off", cx, cy + 22f, 30f, quiet, light, Paint.Align.CENTER)
        else text(c, String.format(Locale.US, "%.1f°", v), cx, cy + 22f, 34f, tempColor(v), light, Paint.Align.CENTER)
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
        p.style = Paint.Style.FILL; p.color = ink
        c.save(); c.rotate(-12f, cx - 6f, cy - 2f)
        c.drawRoundRect(cx - 11f, cy - 18f, cx - 2f, cy + 6f, 4.5f, 4.5f, p)
        c.restore()
        c.drawRoundRect(cx - 7f, cy + 1f, cx + 13f, cy + 9f, 4f, 4f, p)
        c.drawRoundRect(cx - 14f, cy - 22f, cx - 6f, cy - 16f, 3f, 3f, p)
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
        panel(c, frame, 26f)
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
        slider = Slider(x0, x1, ty, 16f, 32f, .5f) { value -> setZoneTemp(driver, value) }
        hit(RectF(x0 - 20f, ty - 30f, x1 + 20f, ty + 30f)) { }
        // Sync: both zones follow the driver. Turning it off makes the passenger zone independent.
        sync?.let { on -> popoverChip(c, f.right - 72f, f.top + 77f, "Sync", on) { sync = !on; write(keySync, if (on) 0f else 1f) } }
    }

    /** A chip right-aligned at [right] in a popover's title row; returns the next chip's right edge. */
    private fun popoverChip(c: Canvas, right: Float, cy: Float, label: String, on: Boolean?, action: () -> Unit): Float {
        t.textSize = 15f; t.typeface = medium
        val w = t.measureText(label) + 32f
        r.set(right - w, cy - 21f, right, cy + 21f)
        glass(c, r, 21f, if (on == true) 3f else 1.4f)
        text(c, label, r.centerX(), r.centerY() + 5.5f, 15f, if (on == false) quiet else ink, medium, Paint.Align.CENTER)
        hit(RectF(r), action)
        return r.left - 10f
    }

    private fun drawFanPopover(c: Canvas) {
        val level = fan ?: return
        val f = popoverFrame(c, 489f, 520f, 170f)
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
        // ECO shows only on a car that answers for it.
        eco?.let { on -> popoverChip(c, f.right - 72f, f.top + 77f, "ECO", on) { eco = !on; write(keyEco, if (on) 0f else 1f) } }
    }

    private fun overlayFrame(c: Canvas, title: String): RectF {
        val frame = RectF(zone.left, zone.top, column.right, zone.bottom).let {
            if (rightHandDrive) RectF(20f, 64f, 1168f, 584f) else RectF(112f, 64f, 1260f, 584f)
        }
        hits.add(Pair(RectF(0f, 0f, W, H), closePopover))
        panel(c, frame, 28f)
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
        if (moving) {
            text(c, "Apps open when the car is parked", f.left + 32f, f.top + 110f, 18f, quiet); return
        }
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
            Triple("System settings", "The head unit's display, sound and connection menu") { openSystemSettings() },
            Triple("Android settings", "The full Android settings app") { openAndroidSettings() },
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
    private fun start(intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun openDiConnect() = start(Intent(context, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    private fun launch(pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) } ?: false

    /** Opens the car's own screen by component; false when this car does not have it. */
    private fun openFactory(target: HomeFactoryTargets.Target): Boolean {
        val pm = context.packageManager
        val intent = HomeFactoryTargets.candidates(target).firstNotNullOfOrNull { (pkg, cls) ->
            val i = if (cls == null) pm.getLaunchIntentForPackage(pkg) else Intent(Intent.ACTION_MAIN).setClassName(pkg, cls)
            i?.takeIf { runCatching { pm.resolveActivity(it, 0) }.getOrNull() != null }
        } ?: return false
        return start(intent)
    }

    /** Fallback for cars whose screens are not in [HomeFactoryTargets]: find them by launcher label. */
    private fun openByLabel(vararg words: String): Boolean {
        val list = apps ?: loadApps().also { apps = it }
        for (word in words) {
            val match = list.firstOrNull { it.first.lowercase(Locale.ROOT).contains(word) } ?: continue
            if (launch(match.second)) return true
        }
        return false
    }

    /** A whole-label match, for short labels such as the H7's "Car" that a substring would over-match. */
    private fun openByExactLabel(label: String): Boolean {
        val list = apps ?: loadApps().also { apps = it }
        val match = list.firstOrNull { it.first.equals(label, ignoreCase = true) } ?: return false
        return launch(match.second)
    }

    private fun openVehicle() {
        if (!openFactory(HomeFactoryTargets.Target.VEHICLE) && !openByExactLabel("car") &&
            !openByLabel("vehicle", "car setting")) openDiConnect()
    }

    private fun openSystemSettings() {
        if (!openFactory(HomeFactoryTargets.Target.SYSTEM_SETTINGS)) start(Intent(android.provider.Settings.ACTION_SETTINGS))
    }

    /** AOSP Settings by component: on the H7 the GWM settings app also answers ACTION_SETTINGS. */
    private fun openAndroidSettings() {
        if (!openFactory(HomeFactoryTargets.Target.ANDROID_SETTINGS)) start(Intent(android.provider.Settings.ACTION_SETTINGS))
    }

    /** Plays the chosen source: the projection opens, the car's radio or USB player opens. */
    private fun selectSource(source: HomeSources.Source) {
        popover = Popover.NONE
        when (source) {
            HomeSources.Source.ANDROID_AUTO, HomeSources.Source.CARPLAY -> openProjection()
            HomeSources.Source.RADIO -> if (!openFactory(HomeFactoryTargets.Target.RADIO) && !openByLabel("radio", "fm")) showApps()
            HomeSources.Source.USB -> if (!openFactory(HomeFactoryTargets.Target.USB_MUSIC) && !openByLabel("usb", "music")) showApps()
        }
    }

    /** Brings the live projection forward; with none, Di-Connect's own screen to connect one. */
    private fun openProjection() {
        when (projection) {
            HomeSources.Projection.ANDROID_AUTO -> {
                val activity = context as? Activity
                if (activity == null || runCatching { com.andrerinas.openheadunit.DiConnectAndroidAuto.connect(activity) }.isFailure) openDiConnect()
            }
            HomeSources.Projection.CARPLAY, HomeSources.Projection.CARPLAY_STARTING ->
                if (!start(Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))) openDiConnect()
            HomeSources.Projection.NONE -> openDiConnect()
        }
    }

    /** The source list: what plays now is ticked, and only sources that can play are listed. */
    private fun drawSources(c: Canvas) {
        val list = sources
        val col = column
        val h = 76f + list.size * 64f
        val frame = RectF(col.left, col.top + 60f, col.right, col.top + 60f + h)
        hits.add(Pair(RectF(0f, 0f, W, H), closePopover))
        panel(c, frame, 24f)
        text(c, "Audio source", frame.left + 24f, frame.top + 40f, 16f, quiet, medium)
        list.forEachIndexed { i, source ->
            r.set(frame.left + 16f, frame.top + 60f + i * 64f, frame.right - 16f, frame.top + 116f + i * 64f)
            val on = source == activeSource
            glass(c, r, 18f, if (on) 3f else 1.3f)
            text(c, source.label, r.left + 20f, r.centerY() + 6.5f, 18f, ink, medium)
            if (on) text(c, "Playing", r.right - 20f, r.centerY() + 6f, 15f, accent, medium, Paint.Align.RIGHT)
            hit(RectF(r)) { selectSource(source) }
        }
        hit(RectF(frame)) { }
    }

    /** Opens the app behind Now Playing: the projection, the car's radio or USB player, or an app. */
    private fun openMediaSource() {
        when (activeSource) {
            HomeSources.Source.ANDROID_AUTO, HomeSources.Source.CARPLAY -> return openProjection()
            HomeSources.Source.RADIO, HomeSources.Source.USB -> return selectSource(activeSource!!)
            null -> Unit
        }
        val pkg = media.pkg ?: return run { openDiConnect() }
        if (!launch(pkg)) openDiConnect()
    }

    private fun openFactoryHome() {
        if (openFactory(HomeFactoryTargets.Target.FACTORY_HOME)) return
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        val factory = homes.firstOrNull { it.activityInfo.packageName != context.packageName }?.activityInfo ?: return
        start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(factory.packageName, factory.name))
    }

    /** Controls the session Home shows; the projection and unknown sources get a media key. */
    private fun transport(code: Int) {
        val pkg = media.pkg
        val sent = pkg != null && HomeMediaSessions.control(context, pkg) { t ->
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                else -> if (media.playing) t.pause() else t.play()
            }
        }
        if (!sent) mediaKey(code)
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
                if (pkg == context.packageName || HomeFactoryTargets.hiddenFromApps(pkg)) null
                else Triple(info.loadLabel(pm)?.toString() ?: pkg, pkg, runCatching { info.loadIcon(pm) }.getOrNull())
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }
}

/**
 * Which phone projection is live and which audio sources Home may offer, decided from the same
 * state the rest of Di-Connect trusts: [com.andrerinas.openheadunit.DiConnectAndroidAuto.isConnected]
 * and [CarPlayBackgroundSession]. Nothing here guesses from Bluetooth. The car's own Bluetooth audio
 * is deliberately never offered: Di-Connect keeps it off because it fought with the projections.
 */
internal object HomeSources {
    enum class Projection { NONE, ANDROID_AUTO, CARPLAY, CARPLAY_STARTING }
    enum class Source(val label: String) { ANDROID_AUTO("Android Auto"), CARPLAY("CarPlay"), RADIO("FM / DAB radio"), USB("USB") }

    /** CarPlay wins while it is actually streaming; a CarPlay session still starting does not hide Android Auto. */
    fun projection(androidAutoConnected: Boolean, carPlayActive: Boolean, carPlaySession: Boolean): Projection = when {
        carPlayActive -> Projection.CARPLAY
        androidAutoConnected -> Projection.ANDROID_AUTO
        carPlaySession -> Projection.CARPLAY_STARTING
        else -> Projection.NONE
    }

    fun connected(p: Projection): Boolean = p == Projection.ANDROID_AUTO || p == Projection.CARPLAY

    fun connectionLabel(p: Projection, androidAutoPhone: String?): String = when (p) {
        Projection.ANDROID_AUTO -> androidAutoPhone?.takeIf { it.isNotBlank() }?.let { "Android Auto · $it" } ?: "Android Auto"
        Projection.CARPLAY -> "CarPlay"
        Projection.CARPLAY_STARTING -> "CarPlay connecting"
        Projection.NONE -> "No phone"
    }

    /** Only sources that can play right now: the live projection, the radio if fitted, USB while a drive is mounted. */
    fun available(p: Projection, radioInstalled: Boolean, usbMounted: Boolean): List<Source> = buildList {
        if (p == Projection.ANDROID_AUTO) add(Source.ANDROID_AUTO)
        if (p == Projection.CARPLAY) add(Source.CARPLAY)
        if (radioInstalled) add(Source.RADIO)
        if (usbMounted) add(Source.USB)
    }

    /** The car's own media session packages and the source each one is. */
    fun factorySource(pkg: String?): Source? = when (pkg) {
        "com.gwm.app.media.server.radio" -> Source.RADIO
        "com.gwm.app.media.server.usbmusic" -> Source.USB
        else -> null
    }

    /**
     * What is playing: the projection while its audio plays, else a playing car source, else the
     * projection that is connected. Null when nothing is connected or playing.
     */
    fun active(p: Projection, projectionPlaying: Boolean, factoryPkg: String?, factoryPlaying: Boolean): Source? {
        val projection = when (p) { Projection.ANDROID_AUTO -> Source.ANDROID_AUTO; Projection.CARPLAY -> Source.CARPLAY; else -> null }
        val factory = factorySource(factoryPkg)
        return when {
            projection != null && projectionPlaying -> projection
            factory != null && factoryPlaying -> factory
            projection != null -> projection
            else -> factory
        }
    }
}

/**
 * The car's own screens that Home opens, by component, as found on a Haval H7 (build
 * GW35OVBPU1G091203B11; see docs/haval-h7/HEAD_UNIT_SURVEY.md). Components come first because the
 * labels mislead: the H7 calls Vehicle Settings "Car", and both settings apps "Settings". A car
 * without a component falls back to label matching in [HomeDashboardView].
 */
internal object HomeFactoryTargets {
    enum class Target { VEHICLE, SYSTEM_SETTINGS, ANDROID_SETTINGS, FACTORY_HOME, RADIO, USB_MUSIC }

    /** Package and activity in order of preference; a null activity means the package's launcher entry. */
    fun candidates(target: Target): List<Pair<String, String?>> = when (target) {
        Target.VEHICLE -> listOf("com.gwm.app.vehicle.center" to "com.gwm.app.vehicle_center.VehicleDesignMainActivity")
        Target.SYSTEM_SETTINGS -> listOf("com.gwm.app.settings" to "com.gwm.settings.view.ui.MainActivity")
        Target.ANDROID_SETTINGS -> listOf("com.android.settings" to "com.android.settings.Settings")
        Target.FACTORY_HOME -> listOf("com.gwm.app.launcher" to "com.gwm.app.launcher.view.HomeActivity")
        Target.RADIO -> listOf("com.gwm.app.mediatunerovs" to "com.gwm.app.mediatunerovs.ui.HomeActivity")
        Target.USB_MUSIC -> listOf("com.gwm.overseas.localmusic" to null)
        // No Media Center target: on the H7 it hands over to the car's Bluetooth audio, which
        // Di-Connect keeps off on purpose (it made the projections' audio stutter).
    }

    /**
     * Engineering, end-of-line and test menus that can change the car's coding or calibration, plus
     * keyboard and voice-test entries that are not apps a driver opens. They stay out of Home's grid.
     */
    private val HIDDEN_PACKAGES = setOf(
        "com.gwm.engineermode",
        "com.gwm.traceur",
        "com.gwm.naviengineer",
        "vendor.gwm.gwmpkiengmode",
        "com.gwmv3.radio.testmode",
        "com.gwm.app.personalization",
        "com.qualcomm.qti.sva",
        "com.emoji.keyboard.touchpal",
        "com.emoji.keyboard.touchpal.go",
    )
    private val HIDDEN_PREFIXES = listOf("com.gwm.eol.", "com.gwm.car.eol")

    // Not used on purpose: com.gwm.app.hvac's TOGGLE_HVAC_CONTROLS service action also switched the
    // H7's climate on when it opened the panel (21 Nov 2026), so Home has no "open factory panel" button.

    const val CLIMATE_SYNC = "car.hvac.sync_enable"
    const val CLIMATE_ECO = "car.hvac.eco_enable"

    /**
     * The only gwm_adapter keys Home writes: cabin climate and seat ventilation. Anything else
     * (drive mode, parking brake, driver assistance, charging) is refused here, whatever calls it.
     */
    private val CLIMATE_WRITABLE = setOf(
        "car.hvac.driver_temperature", "car.hvac.pass_temperature", "car.hvac.fan_speed", "car.hvac.ac_enable",
        "car.hvac.auto_enable", "car.hvac.front_defrost_enable", "car.hvac.rear_defrost_enable", CLIMATE_SYNC, CLIMATE_ECO,
        "car.comfort_setting.driver_seat_ventilation_level_nine", "car.comfort_setting.passenger_seat_ventilation_level_nine",
    )

    fun climateWritable(key: String): Boolean = key in CLIMATE_WRITABLE

    fun hiddenFromApps(pkg: String): Boolean = pkg in HIDDEN_PACKAGES || HIDDEN_PREFIXES.any { pkg.startsWith(it) }
}
