package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File

/** Which projection a cluster theme is drawn for. Each keeps its own choice. */
internal enum class ClusterConnection { ANDROID_AUTO, CARPLAY }

/** The Themes screen groups themes by these; names avoid real brands on purpose. */
internal enum class ThemeCategory { EVERYDAY, TECH, LUXURY, SPORT, NIGHT, PETS }

/**
 * A cluster theme is data, not code: a panel style, a speed style, a battery-flow style, a palette
 * and a typeface. [ThemedClusterView] draws any combination, so adding a theme is adding a row.
 * Modern (Android Auto) and Glass (CarPlay) are the original renderers and stay as they were.
 */
internal data class ClusterTheme(
    val id: String,
    val name: String,
    val category: ThemeCategory,
    val panel: Panel = Panel.PILL,
    val speed: Speed = Speed.PLAIN,
    val flow: Flow = Flow.BAR,
    val layout: Layout = Layout.CLASSIC,
    val face: Face = Face.SANS,
    val text: Int = Color.WHITE,
    val secondary: Int = Color.argb(170, 235, 240, 248),
    val accent: Int = Color.rgb(90, 170, 255),
    val charge: Int = Color.rgb(70, 225, 150),
    val surface: Int = Color.argb(225, 16, 18, 22),
    val edge: Int = Color.argb(60, 255, 255, 255),
    /** Darkens the whole map underneath, 0-255; used by the night themes. */
    val dim: Int = 0,
    /** Drawn by an original renderer rather than [ThemedClusterView]. */
    val native: ClusterConnection? = null,
) {
    enum class Panel { PILL, GOLD_LINE, CARBON, BRACKET, AURORA, WIDGET, CARD, CAPSULE, DOT_GRID, BONE, NONE }
    enum class Speed { PLAIN, DIAL, ITALIC, TAPE, GLOW, HEADED, CONDENSED, DOTS, DOG_TAG }
    enum class Flow { BAR, HAIRLINE, LED, LADDER, ORB, DOTS, PAWS }
    enum class Layout { CLASSIC, MONO_LINE }
    enum class Face { SANS, SERIF, MONO }

    fun availableFor(connection: ClusterConnection) = native == null || native == connection
}

internal object ClusterThemes {
    const val MODERN = "modern"
    const val GLASS = "glass"
    const val CO_PILOT = "co-pilot"

    val all: List<ClusterTheme> = listOf(
        ClusterTheme(MODERN, "Modern", ThemeCategory.EVERYDAY, native = ClusterConnection.ANDROID_AUTO),
        ClusterTheme(GLASS, "Glass", ThemeCategory.EVERYDAY, native = ClusterConnection.CARPLAY),
        ClusterTheme("mono-line", "Mono Line", ThemeCategory.EVERYDAY, panel = ClusterTheme.Panel.NONE,
            layout = ClusterTheme.Layout.MONO_LINE, accent = Color.rgb(127, 224, 255)),
        ClusterTheme("widget", "Widget Glass", ThemeCategory.TECH, panel = ClusterTheme.Panel.WIDGET,
            speed = ClusterTheme.Speed.HEADED, accent = Color.rgb(10, 132, 255), charge = Color.rgb(48, 209, 88),
            surface = Color.argb(178, 30, 30, 34), secondary = Color.argb(160, 235, 235, 245)),
        ClusterTheme("soft-card", "Soft Card", ThemeCategory.TECH, panel = ClusterTheme.Panel.CARD,
            speed = ClusterTheme.Speed.HEADED, accent = Color.rgb(62, 145, 255), charge = Color.rgb(61, 220, 151),
            surface = Color.argb(236, 28, 29, 33), secondary = Color.rgb(154, 160, 170)),
        ClusterTheme("ember", "Ember", ThemeCategory.TECH, panel = ClusterTheme.Panel.CAPSULE,
            speed = ClusterTheme.Speed.CONDENSED, accent = Color.rgb(255, 106, 0), charge = Color.rgb(52, 199, 89),
            surface = Color.argb(220, 0, 0, 0)),
        ClusterTheme("dot-matrix", "Dot Matrix", ThemeCategory.TECH, panel = ClusterTheme.Panel.DOT_GRID,
            speed = ClusterTheme.Speed.DOTS, flow = ClusterTheme.Flow.DOTS, face = ClusterTheme.Face.MONO,
            accent = Color.WHITE, charge = Color.rgb(255, 42, 42), surface = Color.argb(225, 0, 0, 0)),
        ClusterTheme("chronograph", "Chronograph", ThemeCategory.LUXURY, panel = ClusterTheme.Panel.GOLD_LINE,
            speed = ClusterTheme.Speed.DIAL, flow = ClusterTheme.Flow.HAIRLINE, face = ClusterTheme.Face.SERIF,
            text = Color.rgb(244, 236, 220), secondary = Color.argb(205, 217, 184, 120), accent = Color.rgb(217, 184, 120),
            charge = Color.rgb(150, 220, 190), surface = Color.argb(210, 9, 10, 13), edge = Color.argb(140, 217, 184, 120)),
        ClusterTheme("rally", "Rally", ThemeCategory.SPORT, panel = ClusterTheme.Panel.CARBON,
            speed = ClusterTheme.Speed.ITALIC, flow = ClusterTheme.Flow.LED, accent = Color.rgb(255, 45, 60),
            charge = Color.rgb(58, 168, 255), surface = Color.argb(232, 21, 22, 26)),
        ClusterTheme("aviator", "Aviator", ThemeCategory.SPORT, panel = ClusterTheme.Panel.BRACKET,
            speed = ClusterTheme.Speed.TAPE, flow = ClusterTheme.Flow.LADDER, face = ClusterTheme.Face.MONO,
            text = Color.rgb(125, 255, 176), secondary = Color.argb(190, 125, 255, 176), accent = Color.rgb(125, 255, 176),
            charge = Color.rgb(125, 255, 176), surface = Color.argb(158, 2, 14, 8), edge = Color.rgb(125, 255, 176)),
        ClusterTheme("aurora", "Aurora", ThemeCategory.NIGHT, panel = ClusterTheme.Panel.AURORA,
            speed = ClusterTheme.Speed.GLOW, flow = ClusterTheme.Flow.ORB, text = Color.rgb(238, 243, 255),
            accent = Color.rgb(255, 95, 200), charge = Color.rgb(62, 242, 200), surface = Color.argb(189, 8, 12, 34)),
        ClusterTheme("night-ops", "Night Ops", ThemeCategory.NIGHT, panel = ClusterTheme.Panel.NONE,
            speed = ClusterTheme.Speed.PLAIN, flow = ClusterTheme.Flow.HAIRLINE, text = Color.rgb(255, 59, 48),
            secondary = Color.argb(150, 255, 59, 48), accent = Color.rgb(255, 59, 48), charge = Color.rgb(255, 150, 140),
            dim = 158),
        ClusterTheme(CO_PILOT, "Co-Pilot", ThemeCategory.PETS, panel = ClusterTheme.Panel.BONE,
            speed = ClusterTheme.Speed.DOG_TAG, flow = ClusterTheme.Flow.PAWS, text = Color.rgb(255, 246, 232),
            secondary = Color.rgb(233, 203, 160), accent = Color.rgb(164, 109, 255), charge = Color.rgb(245, 143, 176),
            surface = Color.rgb(28, 25, 24), edge = Color.rgb(233, 203, 160)),
    )

    fun byId(id: String?): ClusterTheme? = all.firstOrNull { it.id == id }

    fun defaultFor(connection: ClusterConnection): ClusterTheme =
        byId(if (connection == ClusterConnection.ANDROID_AUTO) MODERN else GLASS)!!

    fun availableFor(connection: ClusterConnection) = all.filter { it.availableFor(connection) }
}

/** Saved choices: one theme per connection, or one for both, plus the Co-Pilot photo. */
internal object ClusterThemeStore {
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_ANDROID_AUTO = "cluster_theme_android_auto"
    private const val KEY_CARPLAY = "cluster_theme_carplay"
    private const val KEY_SAME = "cluster_theme_same_for_both"
    private const val KEY_HUD = "cluster_theme_hud_follows"
    private const val CO_PILOT_FILE = "co_pilot_photo.png"
    private const val CO_PILOT_SIZE = 384

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(connection: ClusterConnection) =
        if (connection == ClusterConnection.ANDROID_AUTO) KEY_ANDROID_AUTO else KEY_CARPLAY

    /** With one theme for both, Modern and Glass still mean each connection's original look. */
    fun selected(context: Context, connection: ClusterConnection): ClusterTheme {
        val prefs = prefs(context)
        val id = if (prefs.getBoolean(KEY_SAME, false)) prefs.getString(KEY_ANDROID_AUTO, null)
            else prefs.getString(key(connection), null)
        val theme = ClusterThemes.byId(id) ?: return ClusterThemes.defaultFor(connection)
        return if (theme.availableFor(connection)) theme else ClusterThemes.defaultFor(connection)
    }

    fun select(context: Context, connection: ClusterConnection, id: String) {
        val edit = prefs(context).edit()
        if (sameForBoth(context)) edit.putString(KEY_ANDROID_AUTO, id).putString(KEY_CARPLAY, id)
        else edit.putString(key(connection), id)
        edit.apply()
    }

    fun sameForBoth(context: Context) = prefs(context).getBoolean(KEY_SAME, false)

    fun setSameForBoth(context: Context, same: Boolean) {
        val prefs = prefs(context)
        val edit = prefs.edit().putBoolean(KEY_SAME, same)
        if (same) edit.putString(KEY_CARPLAY, prefs.getString(KEY_ANDROID_AUTO, ClusterThemes.MODERN))
        edit.apply()
    }

    /** The windscreen HUD takes the theme's colour and digits unless the driver turns it off. */
    fun hudFollowsTheme(context: Context) = prefs(context).getBoolean(KEY_HUD, true)

    fun setHudFollowsTheme(context: Context, follows: Boolean) {
        prefs(context).edit().putBoolean(KEY_HUD, follows).apply()
    }

    /** Calls [changed] on the main thread whenever a theme choice changes; returns the unsubscribe. */
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ANDROID_AUTO || key == KEY_CARPLAY || key == KEY_SAME || key == KEY_HUD || key == CO_PILOT_FILE) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Volatile private var coPilotCache: Pair<Long, Bitmap>? = null

    fun coPilot(context: Context): Bitmap? {
        val file = File(context.applicationContext.filesDir, CO_PILOT_FILE)
        if (!file.isFile) return null
        coPilotCache?.takeIf { it.first == file.lastModified() && !it.second.isRecycled }?.let { return it.second }
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.also {
            coPilotCache = file.lastModified() to it
        }
    }

    /** Copies the chosen picture as a small square, so a large photo never reaches the cluster. */
    fun saveCoPilot(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (minOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= CO_PILOT_SIZE) options.inSampleSize *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return false
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, CO_PILOT_SIZE, CO_PILOT_SIZE, true)
        File(context.applicationContext.filesDir, CO_PILOT_FILE).outputStream().use {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        coPilotCache = null
        // A preference write wakes every open cluster so the new photo shows at once.
        prefs(context).edit().putLong(CO_PILOT_FILE, System.currentTimeMillis()).apply()
        true
    }.getOrDefault(false)
}
