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

    /**
     * The Android Auto protocol version Di-Connect asks for. The phone picks its guidance by the
     * version the head unit REQUESTS (decompiled AA 16.1/16.2/17.3, open-android-auto evidence):
     * below 1.6 it sends only the legacy next-turn and distance messages (0x8004/0x8005); from 1.6
     * the steps and current-position messages (0x8006/0x8007), which carry the destination ETA
     * and time to arrival. From 4.3 it reads each display's hidden UI elements, including
     * "navigation turn data available" (value 5) for the cluster. Baseline keeps 1.2.
     */
    enum class Protocol(val key: String, val major: Int, val minor: Int) {
        BASELINE("1.2", 1, 2), RICH_GUIDANCE("1.6", 1, 6), DISPLAY_FLAGS("4.3", 4, 3),
        /** What the H7 phone answers when asked for 4.3. May switch on newer audio and video behaviour. */
        NEWEST("6.2", 6, 2);
        val readsHiddenUiElements: Boolean get() = major > 4 || (major == 4 && minor >= 3)
        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BASELINE }
    }
    fun protocol(context: Context) = Protocol.of(prefs(context).getString("nav-protocol-experiment", null))
    fun saveProtocol(context: Context, protocol: Protocol) { prefs(context).edit().putString("nav-protocol-experiment", protocol.key).apply() }
    /** Version request payload: major then minor, each a big-endian 16-bit number. */
    fun versionPayload(protocol: Protocol) = byteArrayOf(
        (protocol.major shr 8).toByte(), protocol.major.toByte(), (protocol.minor shr 8).toByte(), protocol.minor.toByte())

    /** With 4.3+: tell the phone Di-Connect shows the turn card on the cluster (hidden UI element 5). */
    fun turnCard(context: Context) = prefs(context).getBoolean("nav-turn-card-experiment", false)
    fun saveTurnCard(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("nav-turn-card-experiment", enabled).apply() }
    /** Ask for the dashboard map as H.265. The H7 has a hardware HEVC decoder (OMX.qcom.video.decoder.hevc). */
    fun clusterHevc(context: Context) = prefs(context).getBoolean("cluster-hevc-experiment", false)
    fun saveClusterHevc(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("cluster-hevc-experiment", enabled).apply() }
    /** Read once per connection, so the decoder and the negotiation agree for the whole session. */
    @Volatile var sessionClusterHevc = false

    /** Send InstrumentClusterStart (0x8001, head unit to phone) once the navigation channel opens. */
    fun clusterReady(context: Context) = prefs(context).getBoolean("nav-cluster-ready-experiment", false)
    fun saveClusterReady(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("nav-cluster-ready-experiment", enabled).apply() }

    /** What this connection actually requested and what the phone answered (for the settings note and logs). */
    @Volatile var sessionProtocol: Protocol = Protocol.BASELINE
    @Volatile var phoneVersion: String = "—"
    @Volatile var sessionClusterReady = false
    @Volatile var sessionTurnCard = false

    /** AdditionalVideoConfig.hidden_ui_elements (field 5): UI_ELEMENT_NAVIGATION_TURN_DATA_AVAILABLE. */
    const val HIDDEN_UI_ELEMENTS_FIELD = 5
    const val UI_ELEMENT_NAVIGATION_TURN_DATA_AVAILABLE = 5L

    /** 1280x720 stock constructor arrays, normalized to the actual negotiated map content. */
    fun insets(mode: Mode, width: Int, height: Int, current: ClusterMapConfig.Insets): ClusterMapConfig.Insets = when (mode) {
        Mode.CURRENT -> current
        Mode.STOCK_INSETS, Mode.STOCK_DPI, Mode.STOCK_STREAM -> ClusterMapConfig.Insets(
            height * 140 / 720, height * 100 / 720, width * 240 / 1280, width * 240 / 1280)
        Mode.MINIMAL -> ClusterMapConfig.Insets(height * 3 / 100, height * 3 / 100, width * 3 / 100, width * 3 / 100)
        Mode.PHONE_DEFAULT -> ClusterMapConfig.Insets(0, 0, 0, 0)
    }

    fun uiConfig(profile: ClusterMapConfig.Profile, mode: Mode, turnCard: Boolean = false): Media.UiConfig {
        fun wire(i: ClusterMapConfig.Insets) = Media.Insets.newBuilder()
            .setTop(i.top).setBottom(i.bottom).setLeft(i.left).setRight(i.right).build()
        return Media.UiConfig.newBuilder().apply {
            setMargins(wire(profile.margins))
            if (mode != Mode.PHONE_DEFAULT) {
                setContentInsets(wire(profile.contentInsets))
                setStableContentInsets(wire(profile.contentInsets))
            }
            setUiTheme(0)
            // Field 5 is not in our generated UiConfig; it is written as a raw field so the
            // generated code does not need regenerating. Only read by the phone at protocol 4.3+.
            if (turnCard) setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder().addField(HIDDEN_UI_ELEMENTS_FIELD,
                com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(UI_ELEMENT_NAVIGATION_TURN_DATA_AVAILABLE).build()).build())
        }.build()
    }

    fun navigationService(customImages: Boolean): Control.Service.NavigationStatusService =
        Control.Service.NavigationStatusService.newBuilder().apply {
            minimumIntervalMs = 1000
            type = if (customImages) Control.Service.NavigationStatusService.ClusterType.CustomImageSupported
                else Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly
            // Our generated names have these swapped: wire field 1 is HEIGHT and field 2 WIDTH
            // (aa-proxy-rs, open-android-auto). Both are 128 here, so the bytes are the same.
            if (customImages) imageOptions = Control.Service.NavigationStatusService.ImageOptions.newBuilder()
                .setWidth(128).setHeight(128).setColourDethBits(32).build()
        }.build()
}
