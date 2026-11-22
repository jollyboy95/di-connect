    // ---- Climate Control page (Nov 2026) ---------------------------------------------------------
    // Live controls read from and written to the car's gwm_adapter service, as the climate strip
    // does. The page shows only the controls the car answers for, so a key this car lacks never
    // appears. Keys beyond the strip's come from a GWM voice-control app that drives the H7.
    private val climateAuto = "car.hvac.auto_enable"
    private val climateMaxAc = "car.hvac.acmax_enable"
    private val climateFrontDemist = "car.hvac.front_defrost_enable"
    private val climateRearDemist = "car.hvac.rear_defrost_enable"
    private val climateSync = "car.hvac.sync_enable"
    private val climateEco = "car.hvac.eco_enable"
    private val climateIoniser = "car.hvac.anion_enable"
    private val climateKeys get() = arrayOf(
        com.andrerinas.openheadunit.gwm.GwmClimateBar.DRIVER_TEMP, com.andrerinas.openheadunit.gwm.GwmClimateBar.PASS_TEMP,
        com.andrerinas.openheadunit.gwm.GwmClimateBar.DRIVER_VENT, com.andrerinas.openheadunit.gwm.GwmClimateBar.PASS_VENT,
        com.andrerinas.openheadunit.gwm.GwmClimateBar.FAN, com.andrerinas.openheadunit.gwm.GwmClimateBar.AC,
        climateAuto, climateMaxAc, climateFrontDemist, climateRearDemist, climateSync, climateEco, climateIoniser)
    private var climateValues: Map<String, Float>? = null
    private var climateReading = false
    private var climateReadAt = 0L

    private fun climatePageShown() = page == "settings" && settingsCategory == SettingsCategory.CLIMATE && !isFinishing && !isDestroyed

    private fun readClimate(delayMs: Long = 0) {
        if (climateReading) return
        climateReading = true
        val app = applicationContext
        Thread({
            if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
            val values = com.andrerinas.openheadunit.gwm.GwmClimateBar.readKeys(app, climateKeys)
            runOnUiThread {
                climateReading = false
                climateReadAt = android.os.SystemClock.elapsedRealtime()
                val changed = values != climateValues
                climateValues = values
                if (changed && climatePageShown()) render()
            }
        }, "climate-page-read").start()
    }

    /** Shows the new value at once, sends it, and reads the car back a moment later. */
    private fun writeClimate(vararg changes: Pair<String, Float>) {
        val current = climateValues ?: return
        climateValues = current + changes
        render()
        Thread({
            changes.forEach { (key, value) ->
                val wire = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
                com.andrerinas.openheadunit.gwm.GwmClimateBar.write(key, wire)
            }
            runOnUiThread { readClimate(delayMs = 1200) }
        }, "climate-page-write").start()
    }

    private fun climateLive(card: LinearLayout) {
        if (!GwmMode.active(this)) {
            card.addView(label(getString(R.string.settings_climate_unavailable), 15, MUTED))
            return
        }
        val values = climateValues
        if (values == null) {
            card.addView(label(getString(R.string.settings_climate_reading), 15, MUTED))
            readClimate()
            return
        }
        if (values.isEmpty()) {
            card.addView(label(getString(R.string.settings_climate_unavailable), 15, MUTED))
            card.addView(button(getString(R.string.settings_climate_retry), false) { climateValues = null; render() }, matchButton(CONTROL_GAP_DP, 52))
            return
        }
        // Re-read when the page is shown again, so changes made on the car's own buttons show up.
        if (android.os.SystemClock.elapsedRealtime() - climateReadAt > 4000) readClimate()
        card.addView(label(getString(R.string.settings_climate_live_description), 14, MUTED).apply { setPadding(0, 0, 0, dp(12)) })
        val bar = com.andrerinas.openheadunit.gwm.GwmClimateBar
        val syncOn = values[climateSync]?.toInt() == 1

        // Temperatures: passenger on the left and driver on the right, as the seats sit in a
        // right-hand-drive car and on the climate strip.
        card.addView(row().apply {
            isBaselineAligned = false
            listOf(bar.PASS_TEMP to R.string.settings_climate_passenger, bar.DRIVER_TEMP to R.string.settings_climate_driver)
                .forEachIndexed { index, (key, title) ->
                    val temp = values[key] ?: return@forEachIndexed
                    if (index > 0 && childCount > 0) addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
                    addView(column().apply {
                        background = rounded(BUTTON, BORDER)
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        addView(label(getString(title), 15, MUTED, true).apply { gravity = Gravity.CENTER })
                        addView(label(String.format(Locale.US, "%.1f°C", temp), 40, TEXT, true).apply {
                            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                        })
                        addView(row().apply {
                            fun step(text: String, delta: Float) = addView(button(text, false) {
                                val target = ((climateValues?.get(key) ?: temp) + delta).coerceIn(16f, 32f)
                                // With Sync on, the car copies the driver's temperature to the passenger itself.
                                writeClimate(key to target)
                            }, LinearLayout.LayoutParams(0, dp(60), 1f))
                            step("−", -.5f)
                            addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
                            step("+", .5f)
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
        })

        // On/off controls the car answers for, four to a row.
        val switches = listOf(
            climateAuto to R.string.settings_climate_auto, bar.AC to R.string.settings_climate_ac,
            climateMaxAc to R.string.settings_climate_max_ac, climateSync to R.string.settings_climate_sync,
            climateFrontDemist to R.string.settings_climate_front_demist, climateRearDemist to R.string.settings_climate_rear_demist,
            climateEco to R.string.settings_climate_eco, climateIoniser to R.string.settings_climate_ioniser,
        ).filter { values.containsKey(it.first) }
        switches.chunked(4).forEach { group ->
            card.addView(row().apply {
                isBaselineAligned = false
                group.forEachIndexed { i, (key, title) ->
                    if (i > 0) addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
                    val on = values[key]?.toInt() == 1
                    addView(button(getString(title), on) { writeClimate(key to if (on) 0f else 1f) }.apply {
                        contentDescription = "${getString(title)} · ${getString(if (on) R.string.di_connect_on else R.string.di_connect_off)}"
                    }, LinearLayout.LayoutParams(0, dp(64), 1f))
                }
                for (i in group.size until 4) {
                    addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
                    addView(View(this@DiPlayActivity), LinearLayout.LayoutParams(0, dp(64), 1f))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTROL_GAP_DP) })
        }
        if (syncOn) card.addView(label(getString(R.string.settings_climate_sync_note), 13, MUTED).apply { setPadding(0, dp(8), 0, 0) })

        // Fan speed, 1 to 7 on the H7.
        values[bar.FAN]?.let { fan ->
            val level = fan.toInt()
            card.addView(row().apply {
                isBaselineAligned = false
                gravity = Gravity.CENTER_VERTICAL
                addView(label("${getString(R.string.settings_climate_fan)} · $level", 18, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("−", false) { writeClimate(bar.FAN to (level - 1).coerceIn(1, 7).toFloat()) }, LinearLayout.LayoutParams(dp(96), dp(56)))
                addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
                addView(button("+", false) { writeClimate(bar.FAN to (level + 1).coerceIn(1, 7).toFloat()) }, LinearLayout.LayoutParams(dp(96), dp(56)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(SETTINGS_BLOCK_GAP_DP) })
            card.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 7; progress = level
                progressTintList = ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(BUTTON)
            }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        }

        // Seat ventilation: off, 1, 2, 3 for each front seat.
        val vents = listOf(bar.PASS_VENT to R.string.settings_climate_seat_passenger, bar.DRIVER_VENT to R.string.settings_climate_seat_driver)
            .filter { values.containsKey(it.first) }
        if (vents.isNotEmpty()) card.addView(row().apply {
            isBaselineAligned = false
            vents.forEachIndexed { i, (key, title) ->
                if (i > 0) addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
                val level = values[key]?.toInt() ?: 0
                val shown = if (level <= 0) getString(R.string.settings_climate_off) else level.toString()
                addView(button("${getString(title)} · $shown", level > 0) {
                    writeClimate(key to (if (level >= 3 || level < 0) 0 else level + 1).toFloat())
                }, LinearLayout.LayoutParams(0, dp(60), 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }

    // ---- Appearance page (Nov 2026) ----------------------------------------------------------------
    private fun accentPicker(card: LinearLayout) {
        card.addView(label(getString(R.string.settings_accent), 17, TEXT, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        card.addView(label(getString(R.string.settings_accent_description), 14, MUTED).apply { setPadding(0, 0, 0, dp(10)) })
        val current = DiConnectAccent.index(this)
        card.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            DiConnectAccent.CHOICES.forEachIndexed { i, choice ->
                if (i > 0) addView(space(12), LinearLayout.LayoutParams(dp(12), 1))
                addView(View(this@DiPlayActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(choice.accent)
                        setStroke(dp(if (i == current) 4 else 1), if (i == current) TEXT else BORDER)
                    }
                    isClickable = true
                    isFocusable = true
                    foreground = focusRing(26)
                    contentDescription = "${getString(R.string.settings_accent)} ${i + 1}"
                    setOnClickListener {
                        DiConnectAccent.save(this@DiPlayActivity, i)
                        checkForAppearanceChange()
                        render()
                    }
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
            }
        })
    }

