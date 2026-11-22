package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Themes that arrive as data rather than in the app: a pack downloaded from the releases repo or a
 * theme file the driver imports. A theme is only a combination of the layouts, panels, speed and
 * flow styles, typefaces and colours the app already draws, so new ones need no new build.
 *
 * A pack is JSON: `{"version": 1, "themes": [{"id": "sunset", "name": "Sunset",
 * "category": "everyday", "layout": "orbit", "accent": "#FF8A3D", ...}]}`. Anything left out
 * takes the default. A theme naming a style this version does not know is skipped, not guessed.
 */
internal object ThemePacks {
    const val CATALOGUE_URL = "https://raw.githubusercontent.com/jollyboy95/di-connect-releases/themes/themes.json"
    /** Every imported id starts with this, so a pack can never replace a built-in theme. */
    const val ID_PREFIX = "pack-"
    private const val FILE = "theme-packs.json"
    private const val MAXIMUM_THEMES = 200

    @Volatile private var loaded: List<ClusterTheme>? = null

    data class Result(val added: Int, val updated: Int, val total: Int)

    /** The saved themes, read from disk the first time. */
    fun themes(context: Context): List<ClusterTheme> =
        loaded ?: read(context).also { loaded = it }

    /** The saved themes if they have been read, for code that has no context to hand. */
    fun cached(): List<ClusterTheme> = loaded.orEmpty()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun read(context: Context): List<ClusterTheme> =
        runCatching { file(context).takeIf { it.isFile }?.readText()?.let(::parse) }.getOrNull().orEmpty()

    /**
     * Adds the themes in [json] to the saved ones; a theme with the same id replaces the old copy.
     * Throws [IllegalArgumentException] when the text holds no theme this version can draw.
     */
    @Synchronized
    fun install(context: Context, json: String): Result {
        val incoming = parse(json)
        require(incoming.isNotEmpty()) { "No usable themes" }
        val merged = LinkedHashMap<String, ClusterTheme>()
        themes(context).forEach { merged[it.id] = it }
        var added = 0
        var updated = 0
        for (theme in incoming) {
            val previous = merged.put(theme.id, theme)
            when {
                previous == null -> added++
                previous != theme -> updated++
            }
        }
        val all = merged.values.take(MAXIMUM_THEMES)
        val target = file(context)
        val temporary = File(target.parentFile, "$FILE.tmp")
        temporary.writeText(serialize(all))
        if (!temporary.renameTo(target)) {
            target.writeText(temporary.readText())
            temporary.delete()
        }
        loaded = all
        ClusterThemeStore.packsChanged(context)
        return Result(added, updated, all.size)
    }

    fun parse(json: String): List<ClusterTheme> {
        val array = JSONObject(json).optJSONArray("themes") ?: return emptyList()
        return (0 until minOf(array.length(), MAXIMUM_THEMES))
            .mapNotNull { array.optJSONObject(it)?.let(::themeFrom) }
            .distinctBy { it.id }
    }

    private fun themeFrom(json: JSONObject): ClusterTheme? = runCatching {
        val raw = json.getString("id").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(40)
        require(raw.isNotEmpty())
        val id = if (raw.startsWith(ID_PREFIX)) raw else ID_PREFIX + raw
        val name = json.getString("name").trim().take(32)
        require(name.isNotEmpty())
        val base = ClusterTheme(id, name, enumOf(json, "category", ThemeCategory.EVERYDAY))
        base.copy(
            panel = enumOf(json, "panel", base.panel),
            speed = enumOf(json, "speed", base.speed),
            flow = enumOf(json, "flow", base.flow),
            layout = enumOf(json, "layout", base.layout),
            face = enumOf(json, "face", base.face),
            text = color(json, "text", base.text),
            secondary = color(json, "secondary", base.secondary),
            accent = color(json, "accent", base.accent),
            charge = color(json, "charge", base.charge),
            surface = color(json, "surface", base.surface),
            edge = color(json, "edge", base.edge),
            dim = json.optInt("dim", 0).coerceIn(0, 200),
        )
    }.getOrNull()

    private inline fun <reified E : Enum<E>> enumOf(json: JSONObject, key: String, fallback: E): E {
        val value = json.optString(key).trim()
        if (value.isEmpty()) return fallback
        val wanted = value.replace('-', '_')
        return enumValues<E>().firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown $key $value")
    }

    /** `#RRGGBB` or `#AARRGGBB`. */
    private fun color(json: JSONObject, key: String, fallback: Int): Int {
        val value = json.optString(key).trim()
        if (value.isEmpty()) return fallback
        require(value.matches(Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) { "Bad colour $key" }
        return Color.parseColor(value)
    }

    fun serialize(themes: List<ClusterTheme>): String {
        val array = JSONArray()
        for (theme in themes) {
            array.put(JSONObject().apply {
                put("id", theme.id)
                put("name", theme.name)
                put("category", theme.category.name.lowercase())
                put("layout", theme.layout.name.lowercase())
                put("panel", theme.panel.name.lowercase())
                put("speed", theme.speed.name.lowercase())
                put("flow", theme.flow.name.lowercase())
                put("face", theme.face.name.lowercase())
                put("text", hex(theme.text))
                put("secondary", hex(theme.secondary))
                put("accent", hex(theme.accent))
                put("charge", hex(theme.charge))
                put("surface", hex(theme.surface))
                put("edge", hex(theme.edge))
                put("dim", theme.dim)
            })
        }
        return JSONObject().put("version", 1).put("themes", array).toString(2)
    }

    private fun hex(color: Int) = String.format(java.util.Locale.US, "#%08X", color)
}
