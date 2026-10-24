package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.content.SharedPreferences

/**
 * GWM/Haval preferences, kept in their own file so nothing here can change BYD behaviour.
 * Ported from DiPlay's GWM keys (jollyboy95/d-play-gwm-h7 @ 1460810, AirPlayPersistence).
 *
 * The cluster map itself is still declared to the phone through DiAuto's own
 * [com.andrerinas.openheadunit.utils.Settings.clusterMapEnabled]; [clusterEnabled] only routes it
 * to the GWM display instead of a BYD one.
 */
internal class GwmSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Treat this head unit as GWM even when the build identity names no GWM, Haval or B11. */
    var forced: Boolean
        get() = prefs.getBoolean("forced", false)
        set(value) = prefs.edit().putBoolean("forced", value).apply()

    /** Show Android Auto's cluster map on [clusterDisplayId]. Applies on the next connection. */
    var clusterEnabled: Boolean
        get() = prefs.getBoolean("cluster-enabled", false)
        set(value) = prefs.edit().putBoolean("cluster-enabled", value).apply()

    /** Logical display of the instrument cluster: 2 on the 2026 H7 Vanta (B11). */
    var clusterDisplayId: Int
        get() = prefs.getInt("cluster-display-id", DEFAULT_CLUSTER_DISPLAY_ID)
        set(value) = prefs.edit().putInt("cluster-display-id", value).apply()

    /** The map-first layer: navigation card, music card and the bottom instrument strip. */
    var styledLayer: Boolean
        get() = prefs.getBoolean("styled-layer", true)
        set(value) = prefs.edit().putBoolean("styled-layer", value).apply()

    /**
     * DiAuto's own turn card. Off by default: Google Maps draws its own card into the cluster video
     * and that cannot be switched off, so two cards would show.
     */
    var navCard: Boolean
        get() = prefs.getBoolean("nav-card", false)
        set(value) = prefs.edit().putBoolean("nav-card", value).apply()

    var musicCard: Boolean
        get() = prefs.getBoolean("music-card", true)
        set(value) = prefs.edit().putBoolean("music-card", value).apply()

    var vehicleStrip: Boolean
        get() = prefs.getBoolean("vehicle-strip", true)
        set(value) = prefs.edit().putBoolean("vehicle-strip", value).apply()

    /** Turn arrow, distance and arrival time in the head-up display's arrow area. */
    var hudEnabled: Boolean
        get() = prefs.getBoolean("hud-enabled", false)
        set(value) = prefs.edit().putBoolean("hud-enabled", value).apply()

    /** Logical display of the head-up display's arrow area: 3 on the H7 the owner uses. */
    var hudDisplayId: Int
        get() = prefs.getInt("hud-display-id", DEFAULT_HUD_DISPLAY_ID)
        set(value) = prefs.edit().putInt("hud-display-id", value).apply()

    var hudAnchor: Int
        get() = prefs.getInt("hud-anchor", 0)
        set(value) = prefs.edit().putInt("hud-anchor", value).apply()

    /**
     * The part of the HUD display the windscreen actually shows (the arrow window), in percent of
     * that display. Defaults to the window measured on the 2026 H7 (cells A3 to C4 of the HUD grid:
     * left 0 %, top 40 %, 26 % x 40 %; column C is only partly visible); adjust with the HUD grid in diagnostics.
     */
    var hudArea: HudArea
        get() = HudArea(
            prefs.getInt("hud-area-left", 0), prefs.getInt("hud-area-top", 40),
            prefs.getInt("hud-area-width", 26), prefs.getInt("hud-area-height", 40),
        ).sane()
        set(value) {
            val a = value.sane()
            prefs.edit().putInt("hud-area-left", a.left).putInt("hud-area-top", a.top)
                .putInt("hud-area-width", a.width).putInt("hud-area-height", a.height).apply()
        }

    data class HudArea(val left: Int, val top: Int, val width: Int, val height: Int) {
        /** Inside the display, at least 5 % in each direction. */
        fun sane(): HudArea {
            val l = left.coerceIn(0, 95)
            val t = top.coerceIn(0, 95)
            return HudArea(l, t, width.coerceIn(5, 100 - l), height.coerceIn(5, 100 - t))
        }
    }

    companion object {
        const val PREFS = "gwm_h7"
        const val DEFAULT_CLUSTER_DISPLAY_ID = 2
        const val DEFAULT_HUD_DISPLAY_ID = 3
    }
}
