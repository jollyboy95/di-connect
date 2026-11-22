package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/**
 * How the windscreen HUD picks up the cluster theme: the theme's accent on the arrow and its digit
 * style on the distance. Nothing else carries over. The HUD projects light, so dark panels would
 * vanish, and every colour is lifted to a minimum brightness so it stays readable against the sky.
 */
internal data class HudThemeStyle(
    val arrow: Int,
    val text: Int,
    val muted: Int,
    val typeface: Typeface,
) {
    companion object {
        /** The original HUD: light blue arrow, white bold digits. Modern and Glass keep it too. */
        val PLAIN = HudThemeStyle(Color.rgb(90, 200, 250), Color.WHITE, Color.rgb(160, 172, 188),
            Typeface.create("sans-serif", Typeface.BOLD))

        /** Below this HSV brightness a projected colour washes out in daylight. */
        const val MIN_BRIGHTNESS = 0.85f

        private var cachedTheme: ClusterTheme? = null
        private var cached: HudThemeStyle = PLAIN

        /** The style for the theme active on [connection], or [PLAIN] when the driver turned it off. */
        fun of(context: Context, connection: ClusterConnection): HudThemeStyle {
            if (!ClusterThemeStore.hudFollowsTheme(context)) return PLAIN
            val theme = ClusterThemeStore.selected(context, connection)
            if (theme == cachedTheme) return cached
            return forTheme(theme).also { cachedTheme = theme; cached = it }
        }

        fun forTheme(theme: ClusterTheme): HudThemeStyle {
            if (theme.native != null) return PLAIN
            val text = lift(theme.text)
            return HudThemeStyle(
                arrow = lift(theme.accent),
                text = text,
                muted = Color.rgb(Color.red(text) * 3 / 4, Color.green(text) * 3 / 4, Color.blue(text) * 3 / 4),
                typeface = typefaceFor(theme),
            )
        }

        private fun typefaceFor(theme: ClusterTheme): Typeface = when {
            theme.face == ClusterTheme.Face.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            theme.face == ClusterTheme.Face.MONO || theme.speed == ClusterTheme.Speed.DOTS ->
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            theme.speed == ClusterTheme.Speed.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            theme.speed == ClusterTheme.Speed.ITALIC -> Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
            else -> PLAIN.typeface
        }

        /** Opaque, and at least [MIN_BRIGHTNESS] bright, keeping the hue. */
        fun lift(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color or 0xFF000000.toInt(), hsv)
            hsv[2] = maxOf(hsv[2], MIN_BRIGHTNESS)
            return Color.HSVToColor(255, hsv)
        }
    }
}
