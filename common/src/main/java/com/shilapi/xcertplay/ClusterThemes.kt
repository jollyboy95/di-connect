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
    enum class Panel { PILL, GOLD_LINE, CARBON, BRACKET, AURORA, WIDGET, CARD, CAPSULE, DOT_GRID, BONE, LCD, FROST, NONE }
    enum class Speed { PLAIN, DIAL, ITALIC, TAPE, GLOW, HEADED, CONDENSED, DOTS, SEGMENT, DOG_TAG }
    enum class Flow { BAR, HAIRLINE, LED, LADDER, ORB, DOTS, SEGMENT, PAWS }
    /**
     * Classic uses the cluster's usual slots. The others rearrange the whole screen: a twin-dial
     * cluster on the left, one solid sidebar, or a console along the bottom edge. All keep Google's
     * card on Android Auto (the right, 42-74 % down) clear.
     */
    enum class Layout { CLASSIC, MONO_LINE, TWIN_DIALS, SIDEBAR, DECK, RIBBONS, ORBIT, ARCS, PERFORMANCE, SPLIT, CENTRE, HYBRID_SPORT, VAPORWAVE_STEREO, MUSIC_LOUNGE, BAVARIAN, TURBINE_LUXURY, PERFORMANCE_COCKPIT, MUSIC_PANORAMA, DAYLIGHT_FUTURE }
    enum class Face { SANS, SERIF, MONO }

    /**
     * Where the map goes on the cluster, as fractions of the panel width, or null for the whole
     * panel. Android Auto is asked for a map drawn for that window (a change applies at the next
     * connection). CarPlay cannot resize its map yet, so split layouts are Android Auto only.
     */
    val mapWindow: Pair<Float, Float>? get() = when (layout) {
        Layout.SPLIT, Layout.HYBRID_SPORT, Layout.VAPORWAVE_STEREO, Layout.MUSIC_LOUNGE -> SPLIT_MAP
        Layout.CENTRE -> CENTRE_MAP
        Layout.PERFORMANCE_COCKPIT -> .65f to 1f
        Layout.MUSIC_PANORAMA -> .62f to 1f
        else -> null
    }

    fun availableFor(connection: ClusterConnection) =
        (native == null || native == connection) && (mapWindow == null || connection == ClusterConnection.ANDROID_AUTO)

    companion object {
        /** Split: instruments on the left half, the map on the right half. */
        val SPLIT_MAP = .5f to 1f
        /** Centre stage: dials either side, the map through the middle half, like the car's own cluster. */
        val CENTRE_MAP = .25f to .75f
    }
}

internal object ClusterThemes {
    const val MODERN = "modern"
    const val GLASS = "glass"
    const val CO_PILOT = "co-pilot"

    /** The themes built into the app. */
    val builtIn: List<ClusterTheme> = listOf(
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
        ClusterTheme("retro-lcd", "Retro LCD", ThemeCategory.TECH, panel = ClusterTheme.Panel.LCD,
            speed = ClusterTheme.Speed.SEGMENT, flow = ClusterTheme.Flow.SEGMENT, face = ClusterTheme.Face.MONO,
            text = Color.rgb(24, 34, 22), secondary = Color.argb(190, 24, 34, 22), accent = Color.rgb(24, 34, 22),
            charge = Color.rgb(24, 34, 22), surface = Color.argb(242, 156, 176, 132), edge = Color.rgb(92, 108, 78)),
        ClusterTheme("vapour", "Vapour", ThemeCategory.TECH, panel = ClusterTheme.Panel.PILL,
            speed = ClusterTheme.Speed.SEGMENT, flow = ClusterTheme.Flow.SEGMENT, face = ClusterTheme.Face.MONO,
            text = Color.rgb(96, 255, 224), secondary = Color.argb(170, 96, 255, 224), accent = Color.rgb(96, 255, 224),
            charge = Color.rgb(96, 255, 224), surface = Color.argb(238, 4, 12, 12), edge = Color.argb(90, 96, 255, 224)),
        ClusterTheme("tower", "Tower", ThemeCategory.TECH, layout = ClusterTheme.Layout.SIDEBAR,
            accent = Color.rgb(0, 200, 255), charge = Color.rgb(80, 230, 140), surface = Color.argb(244, 18, 21, 28),
            edge = Color.argb(40, 255, 255, 255)),
        ClusterTheme("horizon", "Horizon", ThemeCategory.EVERYDAY, layout = ClusterTheme.Layout.DECK,
            accent = Color.rgb(64, 156, 255), charge = Color.rgb(70, 225, 150), surface = Color.argb(238, 10, 14, 24)),
        ClusterTheme("coastline", "Coastline", ThemeCategory.EVERYDAY, layout = ClusterTheme.Layout.DECK,
            text = Color.rgb(232, 252, 248), secondary = Color.argb(180, 190, 235, 228), accent = Color.rgb(0, 214, 190),
            charge = Color.rgb(255, 196, 90), surface = Color.argb(238, 4, 32, 38)),
        ClusterTheme("vector", "Vector", ThemeCategory.TECH, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.ARCS,
            accent = Color.rgb(70, 160, 255), charge = Color.rgb(60, 230, 170), surface = Color.argb(170, 10, 16, 30)),
        ClusterTheme("solar", "Solar", ThemeCategory.TECH, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.ARCS,
            text = Color.rgb(255, 244, 228), accent = Color.rgb(255, 170, 40), charge = Color.rgb(90, 200, 255),
            surface = Color.argb(170, 24, 14, 6)),
        ClusterTheme("orbit", "Orbit", ThemeCategory.LUXURY, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.ORBIT,
            face = ClusterTheme.Face.SERIF, text = Color.rgb(246, 244, 240), accent = Color.rgb(255, 190, 120),
            charge = Color.rgb(120, 220, 255), surface = Color.argb(150, 12, 16, 24)),
        ClusterTheme("split-test", "Split (test)", ThemeCategory.EVERYDAY, panel = ClusterTheme.Panel.FROST,
            layout = ClusterTheme.Layout.SPLIT, accent = Color.rgb(64, 156, 255), charge = Color.rgb(70, 225, 150),
            surface = Color.argb(250, 12, 16, 26), edge = Color.argb(60, 255, 255, 255)),
        ClusterTheme("centre-stage", "Centre Stage", ThemeCategory.EVERYDAY, panel = ClusterTheme.Panel.FROST,
            layout = ClusterTheme.Layout.CENTRE, accent = Color.rgb(80, 220, 255), charge = Color.rgb(255, 210, 60),
            surface = Color.rgb(8, 10, 16), edge = Color.argb(120, 255, 255, 255)),
        ClusterTheme("chronograph", "Chronograph", ThemeCategory.LUXURY, panel = ClusterTheme.Panel.GOLD_LINE,
            speed = ClusterTheme.Speed.DIAL, flow = ClusterTheme.Flow.HAIRLINE, face = ClusterTheme.Face.SERIF,
            text = Color.rgb(244, 236, 220), secondary = Color.argb(205, 217, 184, 120), accent = Color.rgb(217, 184, 120),
            charge = Color.rgb(150, 220, 190), surface = Color.argb(210, 9, 10, 13), edge = Color.argb(140, 217, 184, 120)),
        ClusterTheme("grand-tourer", "Grand Tourer", ThemeCategory.LUXURY, panel = ClusterTheme.Panel.GOLD_LINE,
            layout = ClusterTheme.Layout.TWIN_DIALS, face = ClusterTheme.Face.SERIF, text = Color.rgb(246, 238, 218),
            secondary = Color.argb(200, 214, 176, 112), accent = Color.rgb(214, 176, 112), charge = Color.rgb(150, 210, 185),
            surface = Color.argb(242, 16, 13, 10), edge = Color.argb(170, 214, 176, 112)),
        ClusterTheme("rally", "Rally", ThemeCategory.SPORT, panel = ClusterTheme.Panel.CARBON,
            speed = ClusterTheme.Speed.ITALIC, flow = ClusterTheme.Flow.LED, accent = Color.rgb(255, 45, 60),
            charge = Color.rgb(58, 168, 255), surface = Color.argb(232, 21, 22, 26)),
        ClusterTheme("twin-turbo", "Twin Turbo", ThemeCategory.SPORT, panel = ClusterTheme.Panel.CARBON,
            layout = ClusterTheme.Layout.TWIN_DIALS, accent = Color.rgb(255, 40, 40), charge = Color.rgb(60, 170, 255),
            surface = Color.argb(240, 16, 16, 19), edge = Color.argb(110, 255, 255, 255)),
        ClusterTheme("ribbon", "Ribbon", ThemeCategory.SPORT, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.RIBBONS,
            accent = Color.rgb(255, 184, 64), charge = Color.rgb(64, 170, 255), surface = Color.argb(170, 10, 16, 30)),
        ClusterTheme("redline", "Redline", ThemeCategory.SPORT, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.PERFORMANCE,
            accent = Color.rgb(255, 60, 40), charge = Color.rgb(60, 170, 255), surface = Color.argb(200, 12, 12, 14)),
        ClusterTheme("circuit", "Circuit", ThemeCategory.SPORT, panel = ClusterTheme.Panel.CARBON, layout = ClusterTheme.Layout.PERFORMANCE,
            accent = Color.rgb(255, 214, 0), charge = Color.rgb(0, 200, 255), surface = Color.argb(232, 18, 18, 20)),
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
        ClusterTheme("neon-ribbon", "Neon Ribbon", ThemeCategory.NIGHT, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.RIBBONS,
            accent = Color.rgb(255, 70, 200), charge = Color.rgb(60, 240, 255), surface = Color.argb(170, 14, 8, 30)),
        ClusterTheme("halo", "Halo", ThemeCategory.NIGHT, panel = ClusterTheme.Panel.FROST, layout = ClusterTheme.Layout.ORBIT,
            accent = Color.rgb(170, 140, 255), charge = Color.rgb(110, 240, 210), surface = Color.argb(160, 10, 10, 22)),
        ClusterTheme("command", "Command", ThemeCategory.NIGHT, layout = ClusterTheme.Layout.SIDEBAR,
            text = Color.rgb(255, 176, 64), secondary = Color.argb(170, 255, 176, 64), accent = Color.rgb(255, 176, 64),
            charge = Color.rgb(120, 200, 255), surface = Color.argb(248, 6, 6, 8), edge = Color.argb(60, 255, 176, 64), dim = 110),
        ClusterTheme("co-pilot-stage", "Co-Pilot Stage", ThemeCategory.PETS, panel = ClusterTheme.Panel.FROST,
            layout = ClusterTheme.Layout.CENTRE, speed = ClusterTheme.Speed.DOG_TAG, text = Color.rgb(255, 246, 232),
            secondary = Color.rgb(233, 203, 160), accent = Color.rgb(255, 150, 60), charge = Color.rgb(120, 210, 255),
            surface = Color.rgb(14, 12, 12), edge = Color.rgb(233, 203, 160)),
        ClusterTheme(CO_PILOT, "Co-Pilot", ThemeCategory.PETS, panel = ClusterTheme.Panel.BONE,
            speed = ClusterTheme.Speed.DOG_TAG, flow = ClusterTheme.Flow.PAWS, text = Color.rgb(255, 246, 232),
            secondary = Color.rgb(233, 203, 160), accent = Color.rgb(164, 109, 255), charge = Color.rgb(245, 143, 176),
            surface = Color.rgb(28, 25, 24), edge = Color.rgb(233, 203, 160)),
    )

    /** Built-in themes followed by any downloaded or imported ones ([ThemePacks]). */
    val all: List<ClusterTheme> get() = builtIn + ThemePacks.cached()

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
    private const val KEY_PACKS = "cluster_theme_packs_changed"
    private const val KEY_DIRECTIONS = "cluster_theme_directions_card"
    private const val CO_PILOT_FILE = "co_pilot_photo.png"
    private const val CO_PILOT_SIZE = 384

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(connection: ClusterConnection) =
        if (connection == ClusterConnection.ANDROID_AUTO) KEY_ANDROID_AUTO else KEY_CARPLAY

    /** With one theme for both, Modern and Glass still mean each connection's original look. */
    fun selected(context: Context, connection: ClusterConnection): ClusterTheme {
        ThemePacks.themes(context)
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

    /**
     * Di-Connect's own directions card on Android Auto (a test): Google's arrow when the phone sends
     * it, distance and road, drawn in the theme's style. Google's own card is still drawn in the map.
     */
    fun directionsCard(context: Context) = prefs(context).getBoolean(KEY_DIRECTIONS, false)

    fun setDirectionsCard(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIRECTIONS, on).apply()
    }

    /** Wakes every open cluster and gallery after themes were downloaded or imported. */
    fun packsChanged(context: Context) {
        prefs(context).edit().putLong(KEY_PACKS, System.currentTimeMillis()).apply()
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
            if (key == KEY_ANDROID_AUTO || key == KEY_CARPLAY || key == KEY_SAME || key == KEY_HUD || key == KEY_PACKS || key == KEY_DIRECTIONS || key == CO_PILOT_FILE) changed()
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

