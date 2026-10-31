package com.andrerinas.openheadunit.gwm

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.hud.ClusterMapConfig
import com.andrerinas.openheadunit.utils.AppPermissions
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import org.dplay.gwm.GwmDisplayAdapter
import kotlin.concurrent.thread

/**
 * GWM/Haval settings and parked diagnostics, ported from DiPlay's GWM settings card
 * (jollyboy95/d-play-gwm-h7 @ 1460810). Every control saves immediately. Choices that change what
 * is declared to the phone say they apply on the next connection; the rest update a running
 * session's windows at once.
 */
class GwmSettingsActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var gwm: GwmSettings
    private var scanResult: String? = null
    private var steering: ((GwmSteeringKeys.Event) -> Unit)? = null
    private val steeringEvents = ArrayDeque<String>()
    private var steeringLabel: TextView? = null

    private val appSettings get() = App.provide(this).settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.aa_gwm_settings_title)
        gwm = GwmSettings(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad * 2)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        stopSteering()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) GwmOutputs.hideTest()
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        heading(getString(R.string.aa_gwm_settings_title), 22f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            note(getString(R.string.aa_gwm_needs_android_o))
            return
        }
        renderVehicle()
        if (!GwmMode.active(this)) return
        renderCluster()
        renderHud()
        renderStatus()
        renderDiagnostics()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderVehicle() {
        section(R.string.aa_gwm_section_vehicle)
        note(getString(if (GwmMode.detected()) R.string.aa_gwm_detected else R.string.aa_gwm_not_detected) +
            "\n${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (!GwmMode.detected()) {
            toggle(R.string.aa_gwm_force, getString(R.string.aa_gwm_force_description), gwm.forced) {
                gwm.forced = it
                if (!it && GwmOutputs.sessionRunning) GwmOutputs.stop("GWM route turned off")
                render()
            }
        }
        if (!GwmMode.active(this)) return
        val overlay = AppPermissions.isOverlayGranted(this)
        note(getString(if (overlay) R.string.aa_gwm_overlay_granted else R.string.aa_gwm_overlay_missing))
        if (!overlay) button(getString(R.string.aa_gwm_overlay_allow)) {
            runCatching {
                startActivity(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure { toast("${it.javaClass.simpleName}: ${it.message}") }
        }
        toggle(R.string.aa_gwm_traffic_left, getString(R.string.aa_gwm_traffic_left_description), appSettings.bydLeftHandTraffic) {
            appSettings.bydLeftHandTraffic = it
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderCluster() {
        section(R.string.aa_gwm_section_cluster)
        val on = gwm.clusterEnabled && appSettings.clusterMapEnabled
        toggle(R.string.aa_gwm_cluster_enabled, getString(R.string.aa_gwm_cluster_enabled_description), on) {
            gwm.clusterEnabled = it
            // The same CLUSTER sink DiAuto declares for BYD; the GWM route only changes its target.
            appSettings.clusterMapEnabled = it
            if (!it) GwmOutputs.refresh(this)
            render()
        }
        if (!on) return
        displayChoice(R.string.aa_gwm_cluster_display, gwm.clusterDisplayId) {
            gwm.clusterDisplayId = it
            GwmOutputs.refresh(this)
        }
        toggle(R.string.aa_gwm_cluster_layer, getString(R.string.aa_gwm_cluster_layer_description), gwm.styledLayer) {
            gwm.styledLayer = it
            GwmOutputs.refresh(this)
            render()
        }
        if (gwm.styledLayer) {
            toggle(R.string.aa_gwm_nav_card, null, gwm.navCard) { gwm.navCard = it; GwmOutputs.refresh(this) }
            toggle(R.string.aa_gwm_music_card, null, gwm.musicCard) { gwm.musicCard = it; GwmOutputs.refresh(this) }
            toggle(R.string.aa_gwm_vehicle_strip, null, gwm.vehicleStrip) { gwm.vehicleStrip = it; GwmOutputs.refresh(this) }
        }
        val details = arrayOf(
            getString(R.string.aa_cluster_map_detail_small), getString(R.string.aa_cluster_map_detail_normal),
            getString(R.string.aa_cluster_map_detail_large), getString(R.string.aa_cluster_map_detail_extra_large),
        )
        val detail = ClusterMapConfig.Detail.of(appSettings.clusterMapDetail).value.coerceIn(0, details.size - 1)
        choice(getString(R.string.aa_gwm_map_detail), details, detail, getString(R.string.aa_gwm_map_detail_note)) {
            appSettings.clusterMapDetail = it
        }
        note(getString(R.string.aa_gwm_cluster_note))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderHud() {
        section(R.string.aa_gwm_section_hud)
        toggle(R.string.aa_gwm_hud_enabled, getString(R.string.aa_gwm_hud_enabled_description), gwm.hudEnabled) {
            gwm.hudEnabled = it
            GwmOutputs.refresh(this)
            render()
        }
        if (!gwm.hudEnabled) return
        displayChoice(R.string.aa_gwm_hud_display, gwm.hudDisplayId) {
            gwm.hudDisplayId = it
            GwmOutputs.refresh(this)
        }
        // The windscreen shows only the HUD's arrow window. Find it with the grid (each column 10 %,
        // each row 20 % of the display), then set the area so the arrow is drawn inside it.
        val gridOn = GwmOutputs.hudGridShowing
        button(getString(if (gridOn) R.string.aa_gwm_hud_grid_hide else R.string.aa_gwm_hud_grid_show)) {
            if (GwmOutputs.hudGridShowing) GwmOutputs.hideTest()
            else GwmOutputs.showTest(this, gwm.hudDisplayId, gwm.hudArea)?.let { toast(it) }
            render()
        }
        com.andrerinas.openheadunit.DiConnectViews.hudTestLabel?.invoke(this)?.let { label ->
            button(if (GwmOutputs.hudSampleShowing) getString(R.string.aa_gwm_test_hide) else label) {
                if (GwmOutputs.hudSampleShowing) GwmOutputs.hideTest()
                else GwmOutputs.showHudSample(this)?.let { toast(it) }
                render()
            }
        }
        note(getString(R.string.aa_gwm_hud_area_note))
        val area = gwm.hudArea
        val steps = (0..100 step 5).toList()
        fun areaChoice(label: String, current: Int, apply: (Int) -> GwmSettings.HudArea) {
            val labels = steps.map { "$it %" }.toTypedArray()
            choice(label, labels, steps.indexOf(current - current % 5).coerceAtLeast(0), null) { index ->
                gwm.hudArea = apply(steps[index])
                GwmOutputs.refresh(this)
                // Redraw the grid with the new box.
                if (GwmOutputs.hudGridShowing) GwmOutputs.showTest(this, gwm.hudDisplayId, gwm.hudArea)
            }
        }
        areaChoice(getString(R.string.aa_gwm_hud_area_left), area.left) { area.copy(left = it) }
        areaChoice(getString(R.string.aa_gwm_hud_area_top), area.top) { area.copy(top = it) }
        areaChoice(getString(R.string.aa_gwm_hud_area_width), area.width) { area.copy(width = it) }
        areaChoice(getString(R.string.aa_gwm_hud_area_height), area.height) { area.copy(height = it) }
        val anchors = arrayOf("Left", "Centre", "Right")
        choice(getString(R.string.aa_gwm_hud_position), anchors, gwm.hudAnchor.coerceIn(0, 2), null) {
            gwm.hudAnchor = it
            GwmOutputs.refresh(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderStatus() {
        section(R.string.aa_gwm_section_status)
        note("Session: ${if (GwmOutputs.sessionRunning) "Android Auto connected" else "no Android Auto session"}")
        note("Cluster: ${GwmOutputs.clusterStatus}")
        note("HUD: ${GwmOutputs.hudStatus}")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderDiagnostics() {
        section(R.string.aa_gwm_section_diagnostics)
        note(getString(R.string.aa_gwm_map_tests_note))
        val modes = GwmMapExperiments.Mode.entries
        val labels = resources.getStringArray(R.array.aa_gwm_map_test_modes)
        choice(getString(R.string.aa_gwm_map_tests_title), labels,
            modes.indexOf(GwmMapExperiments.mode(this)), getString(R.string.aa_gwm_map_tests_reconnect)) {
            GwmMapExperiments.saveMode(this, modes[it])
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        toggle(R.string.aa_gwm_nav_images_test, getString(R.string.aa_gwm_nav_images_test_note), GwmMapExperiments.images(this)) {
            GwmMapExperiments.saveImages(this, it)
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        toggle(R.string.aa_gwm_nav_evidence, getString(R.string.aa_gwm_nav_evidence_note), GwmMapExperiments.diagnostics(this)) {
            GwmMapExperiments.saveDiagnostics(this, it)
        }
        note(getString(R.string.aa_gwm_map_tests_active, GwmClusterProfile.declaredExperiment.key))
        // Guidance experiments: each is off by default and applies at the next connection.
        val protocols = GwmMapExperiments.Protocol.entries
        choice(getString(R.string.aa_gwm_nav_protocol_test), resources.getStringArray(R.array.aa_gwm_nav_protocol_modes),
            protocols.indexOf(GwmMapExperiments.protocol(this)), getString(R.string.aa_gwm_nav_protocol_note)) {
            GwmMapExperiments.saveProtocol(this, protocols[it])
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        toggle(R.string.aa_gwm_nav_turn_card_test, getString(R.string.aa_gwm_nav_turn_card_note), GwmMapExperiments.turnCard(this)) {
            GwmMapExperiments.saveTurnCard(this, it)
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        toggle(R.string.aa_gwm_climate_bar, getString(R.string.aa_gwm_climate_bar_note), GwmClimateBar.enabled(this)) {
            GwmClimateBar.setEnabled(this, it)
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        toggle(R.string.aa_gwm_cluster_hevc_test, getString(R.string.aa_gwm_cluster_hevc_note), GwmMapExperiments.clusterHevc(this)) {
            GwmMapExperiments.saveClusterHevc(this, it)
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        toggle(R.string.aa_gwm_nav_cluster_ready_test, getString(R.string.aa_gwm_nav_cluster_ready_note), GwmMapExperiments.clusterReady(this)) {
            GwmMapExperiments.saveClusterReady(this, it)
            toast(getString(R.string.aa_gwm_map_tests_reconnect))
        }
        note(getString(R.string.aa_gwm_nav_protocol_active, GwmMapExperiments.sessionProtocol.key, GwmMapExperiments.phoneVersion))
        button(getString(R.string.aa_gwm_scan)) {
            scanResult = getString(R.string.aa_gwm_scanning)
            render()
            // The vehicle read is a binder call; keep it off the main thread.
            thread(name = "diauto-gwm-scan", isDaemon = true) {
                val text = buildString {
                    append(GwmDisplayAdapter(this@GwmSettingsActivity).dump())
                    appendLine()
                    append(GwmVehicleData.describe(this@GwmSettingsActivity))
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    scanResult = text
                    render()
                }
            }
        }
        scanResult?.let { note(it, selectable = true, mono = true) }
        val testing = GwmOutputs.testShowing
        button(getString(if (testing) R.string.aa_gwm_test_hide else R.string.aa_gwm_test_show)) {
            if (GwmOutputs.testShowing) GwmOutputs.hideTest()
            else GwmOutputs.showTest(this, gwm.clusterDisplayId)?.let { toast(it) }
            render()
        }
        button(getString(if (steering != null) R.string.aa_gwm_steering_stop else R.string.aa_gwm_steering_start)) {
            if (steering != null) stopSteering() else startSteering()
            render()
        }
        steeringLabel = note(steeringText(), selectable = true, mono = true)
    }

    // --- Steering keys: live codes from gwm_adapter; mapping is done in Key mapping ----------

    private fun startSteering() {
        steeringEvents.clear()
        val listener: (GwmSteeringKeys.Event) -> Unit = { event ->
            steeringEvents.addFirst(event.toString())
            while (steeringEvents.size > 10) steeringEvents.removeLast()
            steeringLabel?.text = steeringText()
        }
        steering = listener
        GwmSteeringKeys.addListener(this, listener)
        toast(GwmSteeringKeys.status)
    }

    private fun stopSteering() {
        steering?.let { GwmSteeringKeys.removeListener(it) }
        steering = null
    }

    private fun steeringText(): String =
        if (steeringEvents.isEmpty()) getString(R.string.aa_gwm_steering_idle) else steeringEvents.joinToString("\n")

    // --- Small view helpers ---------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    private fun displayChoice(@StringRes title: Int, selected: Int, onPick: (Int) -> Unit) {
        val displays = GwmDisplayAdapter(this).displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val ids = displays.map { it.displayId }.toMutableList()
        val labels = displays.map { d ->
            val s = GwmOutputs.sizeOf(d)
            "${d.displayId} · \"${d.name}\" ${s.x}x${s.y}"
        }.toMutableList()
        if (selected !in ids) {
            ids += selected
            labels += getString(R.string.aa_gwm_display_missing, selected)
        }
        if (displays.isEmpty()) note(getString(R.string.aa_gwm_no_displays))
        choice(getString(title), labels.toTypedArray(), ids.indexOf(selected), null) { onPick(ids[it]) }
    }

    private fun choice(title: String, labels: Array<String>, index: Int, description: String?, onPick: (Int) -> Unit) {
        button("$title · ${labels.getOrElse(index) { "?" }}") {
            MaterialAlertDialogBuilder(this, R.style.aa_DarkAlertDialog)
                .setTitle(title)
                .setSingleChoiceItems(labels, index) { dialog, which ->
                    onPick(which)
                    dialog.dismiss()
                    render()
                }
                .show()
        }
        description?.let { note(it) }
    }

    private fun toggle(@StringRes title: Int, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(SwitchMaterial(this).apply {
            text = getString(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isChecked = checked
            minHeight = dp(56)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        description?.let { note(it) }
    }

    private fun button(text: String, onClick: () -> Unit) {
        content.addView(MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun section(@StringRes title: Int) = heading(getString(title), 18f).also {
        (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
    }

    private fun heading(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTypeface(typeface, Typeface.BOLD)
    }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun note(text: String, selectable: Boolean = false, mono: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 13f else 14f)
        alpha = 0.8f
        if (mono) typeface = Typeface.MONOSPACE
        setTextIsSelectable(selectable)
    }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
