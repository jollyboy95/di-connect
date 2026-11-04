package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.host.R

/**
 * Di-Connect's own look, after the October 2026 concept: dark glass cards, a restrained blue, and
 * coloured icon tiles for the settings categories. D-Play's widgets keep their behaviour; this only
 * supplies what they draw with.
 */
internal object DiConnectStyle {
    /** The concept's tile colours: slate for the everyday groups, blue, brown, green and purple for the rest. */
    fun categoryTint(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> Color.rgb(44, 54, 72)
        SettingsCategory.CONNECTION -> Color.rgb(44, 54, 72)
        SettingsCategory.DISPLAY -> Color.rgb(44, 54, 72)
        SettingsCategory.AUDIO -> Color.rgb(29, 95, 140)
        SettingsCategory.CLUSTER -> Color.rgb(44, 54, 72)
        SettingsCategory.NAVIGATION -> Color.rgb(30, 92, 214)
        SettingsCategory.CLIMATE -> Color.rgb(30, 92, 214)
        SettingsCategory.VEHICLE -> Color.rgb(96, 62, 40)
        SettingsCategory.APPEARANCE -> Color.rgb(46, 125, 79)
        SettingsCategory.LANGUAGE -> Color.rgb(46, 125, 79)
        SettingsCategory.DIAGNOSTICS -> Color.rgb(44, 54, 72)
        SettingsCategory.ADVANCED -> Color.rgb(78, 60, 124)
        SettingsCategory.ABOUT -> Color.rgb(78, 60, 124)
    }

    /** A rounded square in [tint] carrying a white icon, as on the concept's settings menu. */
    fun iconTile(context: Context, icon: Int, tint: Int, sizePx: Int): View = FrameLayout(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(tint, .12f), tint)).apply { cornerRadius = sizePx * .26f }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams((sizePx * .54f).toInt(), (sizePx * .54f).toInt(), Gravity.CENTER))
    }

    /** Glass card: a faint top-lit gradient over [color] with a hairline edge. */
    fun glass(color: Int, stroke: Int, radiusPx: Float, strokePx: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(color, .045f), color)).apply {
            cornerRadius = radiusPx
            setStroke(strokePx, stroke)
        }

    /**
     * The concept's main button at night: a deep blue fill under a bright blue edge with white text
     * (white on this blue is 5.6:1). Daytime keeps the palette's accent fill.
     */
    val PRIMARY_NIGHT: Int = Color.rgb(28, 98, 220)

    fun lighten(color: Int, amount: Float): Int {
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }
}

/**
 * The home screen's car: the picture centre-cropped into a rounded panel that fades into the page
 * at the bottom, under a thin blue arc like the concept's halo. Decoded once at the panel's size.
 */
internal class VehicleHeroView(context: Context, private val pageColor: Int, private val accent: Int) : View(context) {
    private companion object {
        // Home is rebuilt on every page change and theme switch; the picture is decoded once.
        @Volatile var cached: Bitmap? = null
        fun load(context: Context): Bitmap? = cached ?: runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.dc_vehicle_hero,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull()?.also { cached = it }
    }
    private val picture: Bitmap? = load(context)
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pictureLeft = 0f
    private var pictureRight = 0f
    private var edge = 0f
    private var leftEdge: Shader? = null
    private var rightEdge: Shader? = null
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val clip = Path()
    private val bounds = RectF()
    private val radius = 20f * resources.displayMetrics.density
    /** The render's studio backdrop, so the sides of the panel continue it. */
    private val SCENE = picture?.getPixel(4, 4) ?: pageColor

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        picture?.let { bitmap ->
            // The whole car fits; the panel's own colour fills either side and fades into the picture.
            val scale = minOf(w / bitmap.width.toFloat(), h / bitmap.height.toFloat())
            pictureLeft = (w - bitmap.width * scale) / 2f
            pictureRight = pictureLeft + bitmap.width * scale
            edge = .06f * w
            leftEdge = LinearGradient(pictureLeft, 0f, pictureLeft + edge, 0f, SCENE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            rightEdge = LinearGradient(pictureRight - edge, 0f, pictureRight, 0f, Color.TRANSPARENT, SCENE, Shader.TileMode.CLAMP)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - bitmap.width * scale) / 2f, (h - bitmap.height * scale) / 2f)
            }
            imagePaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        fadePaint.shader = LinearGradient(0f, h * .62f, 0f, h.toFloat(),
            Color.argb(0, Color.red(pageColor), Color.green(pageColor), Color.blue(pageColor)), pageColor, Shader.TileMode.CLAMP)
        glowPaint.shader = RadialGradient(w / 2f, h * .92f, w * .55f,
            Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        arcPaint.strokeWidth = 2.5f * resources.displayMetrics.density
        arcPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, accent, Color.WHITE, accent, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(SCENE)
        if (picture != null) {
            canvas.drawRect(pictureLeft, 0f, pictureRight, h, imagePaint)
            // Soft edges where the picture meets the panel, so no hard letterbox line shows.
            sidePaint.shader = leftEdge
            canvas.drawRect(pictureLeft, 0f, pictureLeft + edge, h, sidePaint)
            sidePaint.shader = rightEdge
            canvas.drawRect(pictureRight - edge, 0f, pictureRight, h, sidePaint)
        }
        canvas.drawRect(0f, h * .5f, w, h, glowPaint)
        canvas.drawRect(0f, h * .6f, w, h, fadePaint)
        canvas.restore()
        // The concept's blue light line, under the car where the picture fades out.
        val y = h - arcPaint.strokeWidth
        canvas.drawLine(w * .08f, y, w * .92f, y, arcPaint)
    }
}

/**
 * The driver's accent colour for the night look (Settings > Appearance). Each choice keeps text on
 * the dark page above 4.5:1 and white text on its button fill above 4.5:1. Daytime keeps the light
 * palette's own blue. Palettes are cached so the same choice is always the same instance.
 */
internal object DiConnectAccent {
    class Choice(val accent: Int, val track: Int, val fill: Int)

    val CHOICES = listOf(
        Choice(Color.rgb(92, 164, 255), Color.rgb(47, 128, 255), Color.rgb(28, 98, 220)),    // blue
        Choice(Color.rgb(167, 139, 250), Color.rgb(124, 92, 240), Color.rgb(98, 64, 200)),    // violet
        Choice(Color.rgb(244, 114, 182), Color.rgb(219, 72, 150), Color.rgb(176, 46, 120)),   // pink
        Choice(Color.rgb(248, 113, 113), Color.rgb(220, 60, 60), Color.rgb(170, 40, 40)),     // red
        Choice(Color.rgb(251, 146, 60), Color.rgb(234, 110, 20), Color.rgb(170, 76, 8)),      // orange
        Choice(Color.rgb(251, 191, 36), Color.rgb(217, 150, 6), Color.rgb(140, 92, 0)),       // amber
        Choice(Color.rgb(74, 222, 128), Color.rgb(34, 170, 90), Color.rgb(20, 120, 60)),      // green
        Choice(Color.rgb(45, 212, 191), Color.rgb(20, 160, 145), Color.rgb(10, 110, 100)),    // teal
    )
    private const val PREFS = "di_connect_appearance"
    private const val KEY = "accent"
    private val cache = HashMap<Int, DiPlayPalette>()

    fun index(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, CHOICES.lastIndex)

    fun save(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, index.coerceIn(0, CHOICES.lastIndex)).apply()
    }

    fun palette(context: Context, night: Boolean): DiPlayPalette {
        if (!night) return DiPlayPalette.LIGHT
        val i = index(context)
        if (i == 0) return DiPlayPalette.DARK
        return synchronized(cache) {
            cache.getOrPut(i) {
                val c = CHOICES[i]
                DiPlayPalette.DARK.copy(accent = c.accent, accentTrack = c.track, focusRing = c.accent,
                    railSelectedOutline = c.track, ripple = (c.accent and 0x00FFFFFF) or 0x33000000)
            }
        }
    }

    /** The main button's fill at night. */
    fun fill(context: Context): Int = CHOICES[index(context)].fill
}
