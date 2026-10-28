package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Android Auto-only GWM cluster skin: compact charcoal modules and an unobstructed map.
 * Derived from the CarPlay renderer to retain its live vehicle and media data lifecycle.
 * Google Maps owns the navigation card in the video; this view never draws another one.
 * Factory telltales are not redrawn or moved. Verify their clearances on the OFF-ROAD theme.
 */
internal class GwmAndroidAutoClusterView(context: Context) : View(context) {
    private var snapshot: GwmVehicleData.Snapshot? = null
    private var guidance: ClusterTurnGuidance? = null
    private var music = ClusterMusicState.Snapshot()
    private var musicReceivedAt = 0L
    private var stopMusic: (() -> Unit)? = null

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val secondary = Color.argb(178, 235, 240, 248)
    private val label = Color.argb(140, 235, 240, 248)
    /** The one accent, used sparingly: turn arrow, fuel level and music progress. */
    private val accent = Color.rgb(71, 222, 219)

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val fade = Paint()
    private val speedShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandSheen = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
    private val flow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val low = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 159, 10) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val glassRect = RectF()
    private val clip = Path()
    private var glyph: Drawable? = null
    private var glyphRes = 0
    private val tick = Runnable { invalidate() }
    private val clockTick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    fun setSnapshot(next: GwmVehicleData.Snapshot?) {
        if (next == snapshot) return
        snapshot = next
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (next == guidance) return
        guidance = next
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        speedShade.shader = android.graphics.RadialGradient(0f, 0f, w * SPEED_SHADE_RADIUS,
            intArrayOf(Color.argb(224, 6, 22, 70), Color.argb(158, 8, 30, 92), Color.argb(0, 8, 30, 92)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        // Frosted dark band: mutes the map quickly so the lower row reads clearly.
        fade.shader = LinearGradient(0f, h * FADE_TOP, 0f, h.toFloat(),
            intArrayOf(Color.argb(0, 6, 10, 18), Color.argb(170, 6, 10, 18), Color.argb(212, 6, 10, 18), Color.argb(228, 6, 10, 18)),
            floatArrayOf(0f, 0.28f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bandSheen.shader = LinearGradient(0f, h * (FADE_TOP + 0.03f), 0f, h * (FADE_TOP + 0.09f),
            Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(clockTick)
        stopMusic = ClusterMusicState.observe {
            music = it
            musicReceivedAt = SystemClock.elapsedRealtime()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        stopMusic?.invoke()
        stopMusic = null
        removeCallbacks(tick)
        removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawLowerRow(canvas, w, h)
        drawHeaderPills(canvas, w, h)
        // Android Auto supplies its turn card in the phone video; never draw a duplicate.
        if (AirPlayPersistence.loadGwmMusicCard(context)) drawMusic(canvas, w, h)
    }

    // ---- glass ----------------------------------------------------------------------------

    /** Every module uses the same neutral near-black as the strip under Google's directions card. */
    private fun drawGlass(canvas: Canvas, box: RectF, radius: Float) {
        body.shader = null
        body.color = SURFACE
        canvas.drawRoundRect(box, radius, radius, body)
    }

    /** Small spaced capitals for labels (OUTSIDE, ODO, KM/H). */
    private fun labelPaint(size: Float) {
        text.typeface = medium
        text.textSize = size
        text.letterSpacing = 0.14f
        text.color = label
    }

    private fun valuePaint(size: Float, face: Typeface = regular) {
        text.typeface = face
        text.textSize = size
        text.letterSpacing = 0f
        text.color = Color.WHITE
    }

    /** One header surface behind the fixed factory gear; never redraw the gear itself. */
    private fun drawHeaderPills(canvas: Canvas, w: Float, h: Float) {
        valuePaint(h * .032f, medium)
        val temperature = snapshot?.outsideTempC?.let {
            String.format(Locale.getDefault(), "%d°C", it.roundToInt())
        } ?: "—°C"
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date())
        val gear = w * GEAR_X
        val gap = w * .052f
        // Symmetric about the gear so the temperature and time sit the same distance from it.
        val half = maxOf(text.measureText(temperature), text.measureText(time)) + gap + w * .018f
        val box = RectF(gear - half, h * .012f, gear + half, h * .104f)
        drawGlass(canvas, box, box.height() / 2f)
        val baseline = box.centerY() - (text.ascent() + text.descent()) / 2f
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(temperature, gear - gap, baseline, text)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(time, gear + gap, baseline, text)
    }

    /** Panels clear all factory telltales, including ECO at 79% and READY at the bottom centre. */
    private fun drawLowerRow(canvas: Canvas, w: Float, h: Float) {
        // Stale or stopped telemetry: say "unavailable" on the battery bar rather than hide it.
        val data = snapshot ?: return drawBatteryFlow(canvas, null, w, h)
        val size = h * .035f
        data.speedKph?.let { speed ->
            canvas.save()
            canvas.translate(w * .15f, h * .455f)
            canvas.scale(1f, .78f)
            canvas.drawCircle(0f, 0f, w * SPEED_SHADE_RADIUS, speedShade)
            canvas.restore()
            valuePaint(h * .22f, medium)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(speed.roundToInt().toString(), w * .15f, h * .53f, text)
            valuePaint(h * .032f)
            canvas.drawText("km/h", w * .15f, h * .59f, text)
        }

        drawBatteryFlow(canvas, data.batteryCurrentA, w, h)

        val baseline = h * BOTTOM_BASELINE
        val fuel = RectF(w*.045f, h*BOTTOM_TOP, w*.255f, h*BOTTOM_BOTTOM)
        drawGlass(canvas, fuel, fuel.height()/2f)
        val icon = h*.034f
        val pumpX = fuel.left + h*.047f
        drawPump(canvas, pumpX, baseline-icon*.94f, icon)
        val triangle = Path().apply {
            moveTo(pumpX-h*.017f, baseline-icon*.50f)
            lineTo(pumpX-h*.007f, baseline-icon*.76f)
            lineTo(pumpX-h*.007f, baseline-icon*.24f)
            close()
        }
        canvas.drawPath(triangle, fill)
        val barLeft = pumpX+icon*1.15f
        val barWidth = w*.05f
        val barY = baseline-icon*.42f
        rect.set(barLeft, barY-h*.004f, barLeft+barWidth, barY+h*.004f)
        canvas.drawRoundRect(rect, h*.004f, h*.004f, track)
        data.fuelPercent?.let {
            rect.right=barLeft+barWidth*(it/100f).coerceIn(0f,1f)
            canvas.drawRoundRect(rect,h*.004f,h*.004f,if(it<15f) low else accentFill)
        }
        valuePaint(size)
        text.textAlign=Paint.Align.LEFT
        canvas.drawText(data.fuelPercent?.let { "${it.roundToInt()}%" } ?: "—%",
            barLeft+barWidth+w*.012f,baseline,text)
        text.textAlign=Paint.Align.RIGHT
        canvas.drawText(data.rangeKm?.let { "${it.roundToInt()} km" } ?: "— km",
            fuel.right-h*.025f,baseline,text)
        text.textAlign=Paint.Align.LEFT

        // The car owns the drive mode (ECO, NORMAL, SPORT) between 75 % and 82 %. Start right of it.
        val odoValue = data.odometerKm?.let { "${NumberFormat.getIntegerInstance().format(it.roundToInt())} km" } ?: "— km"
        labelPaint(h*.022f)
        val odoLabel = text.measureText("ODO")
        valuePaint(size)
        val odoWidth = h*.044f + odoLabel + w*.012f + text.measureText(odoValue)
        val odo = RectF(w*.825f, h*BOTTOM_TOP, w*.825f + odoWidth, h*BOTTOM_BOTTOM)
        drawGlass(canvas,odo,odo.height()/2f)
        labelPaint(h*.022f)
        text.textAlign=Paint.Align.LEFT
        canvas.drawText("ODO", odo.left + h*.022f, baseline, text)
        valuePaint(size)
        canvas.drawText(odoValue, odo.left + h*.022f + odoLabel + w*.012f, baseline, text)
        text.letterSpacing=0f
    }

    // ---- navigation card ------------------------------------------------------------------

    private fun drawNavigation(canvas: Canvas, next: ClusterTurnGuidance, w: Float, h: Float) {
        val cw = w * 0.24f
        val ch = h * 0.205f
        val topLeft = AirPlayPersistence.loadGwmCardAnchor(context) == 1
        val left = if (topLeft) w * 0.06f else (w - cw) / 2f
        val top = if (topLeft) h * 0.13f else h * 0.105f
        rect.set(left, top, left + cw, top + ch)
        val box = RectF(rect)
        drawGlass(canvas, box, ch * 0.22f)

        val res = clusterTurnGlyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(accent) }
            glyphRes = res
        }
        val side = ch * 0.40f
        val gx = left + ch * 0.13f
        val gy = top + ch * 0.11f
        glyph?.setBounds(gx.toInt(), gy.toInt(), (gx + side).toInt(), (gy + side).toInt())
        glyph?.draw(canvas)

        val textLeft = left + ch * 0.68f
        val textWidth = left + cw - ch * 0.12f - textLeft
        text.textAlign = Paint.Align.LEFT
        valuePaint(ch * 0.205f, medium)
        canvas.drawText(fit(distanceLabel(next.distanceMeters), textWidth), textLeft, top + ch * 0.31f, text)
        valuePaint(ch * 0.15f, light)
        val road = if (next.roundaboutExit in 1..9) context.getString(R.string.turn_card_exit, next.roundaboutExit) else next.road
        if (road.isNotEmpty()) canvas.drawText(fit(road, textWidth), textLeft, top + ch * 0.53f, text)

        canvas.drawRect(left + ch * 0.13f, top + ch * 0.655f, left + cw - ch * 0.13f, top + ch * 0.655f + 1.2f, hairline)
        val info = infoParts(next).joinToString("   ·   ")
        if (info.isNotEmpty()) {
            valuePaint(ch * 0.128f)
            text.color = secondary
            canvas.drawText(fit(info, left + cw - ch * 0.13f - (left + ch * 0.13f)), left + ch * 0.13f, top + ch * 0.86f, text)
            text.color = Color.WHITE
        }
    }

    // ---- now playing ----------------------------------------------------------------------

    private fun drawMusic(canvas: Canvas, w: Float, h: Float) {
        val info = music.info
        val title = info.title?.takeIf { it.isNotBlank() } ?: return
        val box = RectF(w * MUSIC_LEFT, h * .125f, w * MUSIC_RIGHT, h * .379f)
        drawGlass(canvas, box, h * .039f)
        val side = h * .125f
        val ax = box.centerX() - side / 2f
        val ay = h * .148f
        rect.set(ax, ay, ax + side, ay + side)
        val bitmap = music.artwork?.takeUnless(Bitmap::isRecycled)
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, side * .12f, side * .12f, Path.Direction.CW)
        canvas.clipPath(clip)
        if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, art) else canvas.drawRect(rect, track)
        canvas.restore()
        val inner = box.width() - h * .05f
        val barTop = ay + side + h * .011f
        val barH = h * .006f
        rect.set(ax, barTop, ax + side, barTop + barH)
        canvas.drawRoundRect(rect, barH, barH, track)
        val duration = info.durationMillis?.takeIf { it > 0 }
        var elapsed = info.elapsedMillis
        if (duration != null && elapsed != null) {
            if (info.playing) {
                elapsed += SystemClock.elapsedRealtime() - musicReceivedAt
                removeCallbacks(tick)
                postDelayed(tick, 1_000L)
            }
            rect.right = ax + side * (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            canvas.drawRoundRect(rect, barH, barH, accentFill)
        }
        text.textAlign = Paint.Align.CENTER
        valuePaint(h * .034f, medium)
        canvas.drawText(fit(title, inner), box.centerX(), barTop + h * .047f, text)
        info.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            valuePaint(h * .022f, regular)
            text.letterSpacing = .03f
            text.color = secondary
            canvas.drawText(fit(artist.uppercase(Locale.getDefault()), inner), box.centerX(), barTop + h * .08f, text)
            text.letterSpacing = 0f
            text.color = Color.WHITE
        }
        text.textAlign = Paint.Align.LEFT
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun infoParts(next: ClusterTurnGuidance): List<String> {
        val parts = mutableListOf<String>()
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let { epoch ->
            // Always 12-hour, whatever the phone or car is set to.
            parts += context.getString(R.string.turn_card_info_arrival,
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epoch * 1000L)))
        }
        next.remainingSeconds?.takeIf { it > 0 }?.let { total ->
            val hours = (total / 3600).toInt()
            val minutes = ((total % 3600) / 60).toInt()
            parts += if (hours > 0) context.getString(R.string.turn_card_info_duration_hm, hours, minutes)
            else context.getString(R.string.turn_card_info_duration_m, minutes)
        }
        next.remainingMeters?.takeIf { it > 0 }?.let { meters ->
            parts += context.getString(R.string.turn_card_info_km, meters / 1000.0)
        }
        return parts
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, (meters / 10) * 10)
        else -> context.getString(R.string.turn_card_distance_km, meters / 100 / 10f)
    }

    private fun fit(value: String, maxWidth: Float): String {
        if (text.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 0 && text.measureText(value.take(end) + "…") > maxWidth) end--
        return value.take(end) + "…"
    }

    /** A thin fuel-pump outline: body, window and nozzle hose. */
    /**
     * Battery flow, centred on zero: blue to the right while the hybrid battery drives the car,
     * green to the left while regen or the engine charges it. This is battery flow, not the stock
     * "Energy output" gauge: with the engine driving and charging, it reads charging.
     * kW = live current × assumed 340 V, so it is labelled approximate. When the current stops
     * arriving the bar empties and shows "—" (unavailable), never the last value.
     */
    private fun drawBatteryFlow(canvas: Canvas, currentA: Float?, w: Float, h: Float) {
        val meter = RectF(w * .065f, h * .627f, w * .235f, h * .641f)
        drawGlass(canvas, RectF(meter.left - h*.014f, meter.top - h*.014f,
            meter.right + h*.014f, meter.bottom + h*.014f), h*.02f)
        canvas.drawRoundRect(meter, h*.007f, h*.007f, track)
        val kw = currentA?.let { batteryKw(it) }
        if (kw != null) {
            val fraction = (kw / BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f)
            val end = meter.centerX() + fraction * meter.width() / 2f
            val colour = if (fraction >= 0f) DISCHARGE else CHARGE
            flow.shader = LinearGradient(meter.centerX(), 0f, end, 0f,
                Color.argb(140, Color.red(colour), Color.green(colour), Color.blue(colour)), colour, Shader.TileMode.CLAMP)
            rect.set(minOf(meter.centerX(), end), meter.top, maxOf(meter.centerX(), end), meter.bottom)
            if (rect.width() >= 1f) canvas.drawRoundRect(rect, h*.007f, h*.007f, flow)
        }
        canvas.drawLine(meter.centerX(), meter.top-h*.006f, meter.centerX(),
            meter.bottom+h*.006f, stroke.apply { strokeWidth = h*.003f })
        valuePaint(h*.018f, medium)
        text.color = label
        text.letterSpacing = .12f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("CHARGE", meter.left, h*.684f, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("POWER", meter.right, h*.684f, text)
        text.letterSpacing = 0f
        valuePaint(h*.024f, medium)
        text.textAlign = Paint.Align.CENTER
        text.color = when {
            kw == null -> secondary
            kw >= 0f -> DISCHARGE_TEXT
            else -> CHARGE_TEXT
        }
        canvas.drawText(kw?.let { formatKw(it) } ?: "—", meter.centerX(), h*.687f, text)
        text.color = Color.WHITE
    }

    private fun drawPump(canvas: Canvas, left: Float, top: Float, size: Float) {
        stroke.strokeWidth = size * 0.09f
        rect.set(left, top, left + size * 0.62f, top + size)
        canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, stroke)
        rect.set(left + size * 0.15f, top + size * 0.17f, left + size * 0.47f, top + size * 0.38f)
        canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, fill)
        canvas.drawLine(left + size * 0.62f, top + size * 0.30f, left + size * 0.86f, top + size * 0.46f, stroke)
        canvas.drawLine(left + size * 0.86f, top + size * 0.46f, left + size * 0.86f, top + size * 0.86f, stroke)
    }

    internal companion object {
        /** Where the lower fade starts, as a fraction of the panel height. */
        const val FADE_TOP = 0.74f
        /** Neutral near-black matching the strip under Google's directions card. */
        val SURFACE = Color.rgb(22, 23, 25)
        /** Centre of the factory gear indicator, which the car draws above this layer. */
        const val GEAR_X = .501f
        /** Deep navy fade kept tight behind the speed digits, soft at the edge. */
        const val SPEED_SHADE_RADIUS = .078f
        /**
         * Assumed pack voltage (334-341 V measured). The live HV voltage has no verified adapter key
         * yet, so every kW figure is shown as approximate ("≈") until it does.
         */
        const val PACK_VOLTS = 340f
        /**
         * Provisional display scale only, from one drive (+188 A ≈ 64 kW on a hill climb). It is not
         * the battery's confirmed limit; readings beyond it pin the bar at the end.
         */
        const val BATTERY_FULL_SCALE_KW = 65f
        val DISCHARGE = Color.rgb(90, 170, 255)
        val CHARGE = Color.rgb(70, 225, 150)
        val DISCHARGE_TEXT = Color.rgb(120, 185, 255)
        val CHARGE_TEXT = Color.rgb(90, 230, 160)

        fun batteryKw(currentA: Float): Float = currentA * PACK_VOLTS / 1000f

        /** Approximate whole kW with an explicit sign; under 0.5 kW reads ≈ 0 kW. */
        fun formatKw(kw: Float): String {
            val whole = kotlin.math.round(kw).toInt()
            return when {
                whole > 0 -> "\u2248 +$whole kW"
                whole < 0 -> "\u2248 \u2212${-whole} kW"
                else -> "\u2248 0 kW"
            }
        }
        /** One row with the factory READY and drive mode, below the EV / limit / lane lamps (end 87 %). */
        const val BOTTOM_TOP = .905f
        const val BOTTOM_BOTTOM = .975f
        const val BOTTOM_BASELINE = .952f
        /** Left edge of Google's directions card; right edge clear of the factory warnings from 89 %. */
        const val MUSIC_LEFT = .758f
        const val MUSIC_RIGHT = .884f
    }
}



