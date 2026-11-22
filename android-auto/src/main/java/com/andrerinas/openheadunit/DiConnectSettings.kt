package com.andrerinas.openheadunit

import android.content.Context
import android.content.SharedPreferences

/**
 * D-Play's settings store, shared with Android Auto so both protocols are set up in one place.
 * The keys are D-Play's own (AirPlayPersistence); the integration script fails if they change.
 */
internal object DiConnectSettings {
    private const val PREFS = "xcertplay_airplay"
    const val CLUSTER_MAP = "cluster_map_enabled"
    const val GWM_CLUSTER_OVERLAY_ENABLED = "gwm_cluster_overlay_enabled"
    const val GWM_CLUSTER_DISPLAY_ID = "gwm_cluster_display_id"
    const val GWM_FORCED = "gwm_forced"
    const val GWM_CLUSTER_STYLE = "gwm_cluster_style"
    const val GWM_MUSIC_CARD = "gwm_music_card"
    const val GWM_HUD_ENABLED = "gwm_hud_enabled"
    const val GWM_HUD_DISPLAY_ID = "gwm_hud_display_id"
    const val GWM_HUD_ANCHOR = "gwm_hud_anchor"
    /** Android Auto only: Di-Connect's directions card. Off: Google Maps draws its own card. */
    const val AA_NAV_CARD = "di_connect_aa_nav_card"
    const val DEFAULT_CLUSTER_DISPLAY_ID = 2
    const val DEFAULT_HUD_DISPLAY_ID = 3

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bool(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)
    fun setBool(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()
    fun int(context: Context, key: String, default: Int) = prefs(context).getInt(key, default)
    fun setInt(context: Context, key: String, value: Int) = prefs(context).edit().putInt(key, value).apply()

    /** A display id above the main screen's, or [default]. */
    fun displayId(context: Context, key: String, default: Int) =
        int(context, key, -1).takeIf { it > android.view.Display.DEFAULT_DISPLAY } ?: default
}
