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
    // Di-Connect: the settings shared with CarPlay live in D-Play's store (DiConnectSettings).
    private val app: Context = context.applicationContext

    /** Treat this head unit as GWM even when the build identity names no GWM, Haval or B11. */
    var forced: Boolean
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_FORCED, false)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_FORCED, value)

    /** Show Android Auto's cluster map on [clusterDisplayId]. Applies on the next connection. */
    var clusterEnabled: Boolean
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.CLUSTER_MAP, false) && com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_CLUSTER_OVERLAY_ENABLED, false)
        set(value) {
            com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_CLUSTER_OVERLAY_ENABLED, value)
            if (value) com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.CLUSTER_MAP, true)
        }

    /** Logical display of the instrument cluster: 2 on the 2026 H7 Vanta (B11). */
    var clusterDisplayId: Int
        get() = com.andrerinas.openheadunit.DiConnectSettings.displayId(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_CLUSTER_DISPLAY_ID, DEFAULT_CLUSTER_DISPLAY_ID)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setInt(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_CLUSTER_DISPLAY_ID, value)

    /** The map-first layer: navigation card, music card and the bottom instrument strip. */
    var styledLayer: Boolean
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_CLUSTER_STYLE, true)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_CLUSTER_STYLE, value)

    /**
     * DiAuto's own turn card. Off by default: Google Maps draws its own card into the cluster video
     * and that cannot be switched off, so two cards would show.
     */
    var navCard: Boolean
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.AA_NAV_CARD, false)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.AA_NAV_CARD, value)

    var musicCard: Boolean
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_MUSIC_CARD, true)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_MUSIC_CARD, value)

    var vehicleStrip: Boolean
        get() = prefs.getBoolean("vehicle-strip", true)
        set(value) = prefs.edit().putBoolean("vehicle-strip", value).apply()

    /** Turn arrow, distance and arrival time in the head-up display's arrow area. */
    var hudEnabled: Boolean
        get() = com.andrerinas.openheadunit.DiConnectSettings.bool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_HUD_ENABLED, false)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setBool(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_HUD_ENABLED, value)

    /** Logical display of the head-up display's arrow area: 3 on the H7 the owner uses. */
    var hudDisplayId: Int
        get() = com.andrerinas.openheadunit.DiConnectSettings.displayId(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_HUD_DISPLAY_ID, DEFAULT_HUD_DISPLAY_ID)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setInt(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_HUD_DISPLAY_ID, value)

    var hudAnchor: Int
        get() = com.andrerinas.openheadunit.DiConnectSettings.int(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_HUD_ANCHOR, 0).coerceIn(0, 2)
        set(value) = com.andrerinas.openheadunit.DiConnectSettings.setInt(app, com.andrerinas.openheadunit.DiConnectSettings.GWM_HUD_ANCHOR, value.coerceIn(0, 2))

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
