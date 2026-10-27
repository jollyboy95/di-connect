package com.andrerinas.openheadunit.gwm

import android.content.Context
import com.andrerinas.openheadunit.hud.ClusterMapConfig
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** Opt-in phone negotiations. These do not write any vehicle configuration. */
internal object GwmMapExperiments {
    enum class Mode(val key: String) {
        CURRENT("current"), STOCK_INSETS("stock-insets"), STOCK_DPI("stock-dpi"), STOCK_STREAM("stock-stream"),
        MINIMAL("minimal"), PHONE_DEFAULT("phone-default");
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CURRENT }
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(GwmSettings.PREFS, Context.MODE_PRIVATE)
    fun mode(context: Context) = Mode.of(prefs(context).getString("map-experiment", null))
    fun saveMode(context: Context, mode: Mode) { prefs(context).edit().putString("map-experiment", mode.key).apply() }
    fun images(context: Context) = prefs(context).getBoolean("nav-image-experiment", false)
    fun saveImages(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("nav-image-experiment", enabled).apply() }
    fun diagnostics(context: Context) = prefs(context).getBoolean("nav-experiment-diagnostics", false)
    fun saveDiagnostics(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("nav-experiment-diagnostics", enabled).apply() }

    /** 1280x720 stock constructor arrays, normalized to the actual negotiated map content. */
    fun insets(mode: Mode, width: Int, height: Int, current: ClusterMapConfig.Insets): ClusterMapConfig.Insets = when (mode) {
        Mode.CURRENT -> current
        Mode.STOCK_INSETS, Mode.STOCK_DPI, Mode.STOCK_STREAM -> ClusterMapConfig.Insets(
            height * 140 / 720, height * 100 / 720, width * 240 / 1280, width * 240 / 1280)
        Mode.MINIMAL -> ClusterMapConfig.Insets(height * 3 / 100, height * 3 / 100, width * 3 / 100, width * 3 / 100)
        Mode.PHONE_DEFAULT -> ClusterMapConfig.Insets(0, 0, 0, 0)
    }

    fun uiConfig(profile: ClusterMapConfig.Profile, mode: Mode): Media.UiConfig {
        fun wire(i: ClusterMapConfig.Insets) = Media.Insets.newBuilder()
            .setTop(i.top).setBottom(i.bottom).setLeft(i.left).setRight(i.right).build()
        return Media.UiConfig.newBuilder().apply {
            setMargins(wire(profile.margins))
            if (mode != Mode.PHONE_DEFAULT) {
                setContentInsets(wire(profile.contentInsets))
                setStableContentInsets(wire(profile.contentInsets))
            }
            setUiTheme(0)
        }.build()
    }

    fun navigationService(customImages: Boolean): Control.Service.NavigationStatusService =
        Control.Service.NavigationStatusService.newBuilder().apply {
            minimumIntervalMs = 1000
            type = if (customImages) Control.Service.NavigationStatusService.ClusterType.CustomImageSupported
                else Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly
            if (customImages) imageOptions = Control.Service.NavigationStatusService.ImageOptions.newBuilder()
                .setWidth(128).setHeight(128).setColourDethBits(32).build()
        }.build()
}
