    /**
     * The full-size Home from the October 2026 concept, laid out for the landscape head unit: the
     * car with the connection status over it, three tiles (Android Auto, CarPlay, Vehicle) under
     * it, and the phone, automatic connection and the other actions in a glass panel beside it.
     * Every control keeps the action, text and field it had on the previous Home, so status
     * updates, tests and saved settings work unchanged; compact windows keep the previous Home.
     */
    private fun homeConcept(content: LinearLayout) {
        fun homeButton(title: String, primary: Boolean, click: () -> Unit) = button(title, primary, click)
        fun tile(target: Button, icon: Int, primary: Boolean): Button = target.apply {
            val size = dp(34)
            val glyph = getDrawable(icon)?.mutate()?.apply {
                setTint(if (primary && appNight) Color.WHITE else ACCENT)
                setBounds(0, 0, size, size)
            }
            setCompoundDrawablesRelative(null, glyph, null, null)
            compoundDrawablePadding = dp(10)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(16), dp(10), dp(14))
        }

        val body = row().apply { gravity = Gravity.TOP }

        // Left: the car, the status over it, and the three tiles.
        val stage = column()
        val hero = android.widget.FrameLayout(this)
        hero.addView(VehicleHeroView(this, BG, ACCENT), android.widget.FrameLayout.LayoutParams(-1, -1))
        hero.addView(column().apply {
            setPadding(dp(26), dp(22), dp(26), 0)
            addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply { letterSpacing = .16f })
            addView(label("Your drive. Connected.", 30, TEXT, true).apply {
                setPadding(0, dp(6), 0, 0)
                setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), Color.argb(170, 0, 0, 0))
            })
        }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        hero.addView(column().apply {
            setPadding(dp(26), 0, dp(26), dp(22))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                homeStatusDot = View(this@DiPlayActivity).also {
                    addView(it, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
                }
                homeCardTitle = label(getString(R.string.di_connect_home_title), 12, ACCENT, true).apply { letterSpacing = .12f }
                addView(homeCardTitle)
            })
            status = label(getString(R.string.ready_when_you_are), 28, TEXT, true).apply {
                setPadding(0, dp(6), 0, 0)
                setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), Color.argb(170, 0, 0, 0))
            }
            addView(status)
        }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        stage.addView(hero, LinearLayout.LayoutParams(-1, dp(318)))

        connectButton = homeButton(getString(R.string.connect_phone), false) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else if (diConnectEntryEnabled() && !SetupGuide.seen(this)) { page = "setup"; render() }
            else connect(true)
        }
        androidAutoButton = homeButton(getString(R.string.di_connect_connect_android_auto), true) { connectAndroidAuto() }
        val vehicle = homeButton(getString(R.string.settings_vehicle), false) {
            page = "settings"
            openSettingsCategory(SettingsCategory.VEHICLE)
        }
        stage.addView(row().apply {
            addView(tile(androidAutoButton!!, R.drawable.ic_dp_navigation, true), LinearLayout.LayoutParams(0, dp(132), 1f))
            addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
            addView(tile(connectButton!!, R.drawable.ic_dp_display, false), LinearLayout.LayoutParams(0, dp(132), 1f))
            addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
            addView(tile(vehicle, R.drawable.ic_dp_car, false), LinearLayout.LayoutParams(0, dp(132), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        setupError?.let { stage.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }

        // Right: phone, automatic connection, USB and the rest, in one glass panel.
        val panel = card().apply { setPadding(dp(22), dp(20), dp(22), dp(20)) }
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        homeConnectionHint = label(connectionHint, 14, MUTED)
        panel.addView(homeConnectionHint)
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            val allowOnSystemScreen = startupProblem == CarHotspotTethering.Result.PERMISSION_REQUIRED &&
                !CarHotspotSetup.isBydHeadUnit(this) && CarHotspotTethering.appStartSupported()
            panel.addView(label(if (allowOnSystemScreen) getString(R.string.settings_write_settings_missing)
                else hotspotResultText(startupProblem), 15, WARNING).apply { setPadding(0, dp(12), 0, 0) })
            panel.addView(
                if (allowOnSystemScreen) homeButton(getString(R.string.settings_write_settings_allow), false) { openWriteSettingsAccess() }
                else homeButton(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() },
                matchButton(CONTROL_GAP_DP, 52))
        } else if (carHotspotOff()) {
            val name = AirPlayPersistence.loadManualHotspotSsid(this)
            val off = if (name.isBlank()) getString(R.string.hotspot_details_off) else getString(R.string.msg_car_hotspot_off, name)
            panel.addView(label(off, 15, WARNING).apply { setPadding(0, dp(12), 0, 0) })
            panel.addView(homeButton(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(CONTROL_GAP_DP, 52))
        }
        panel.addView(homeButton(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(14, 52))
        disconnectButton = homeButton(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        panel.addView(disconnectButton, matchButton(CONTROL_GAP_DP, 52))
        panel.addView(space(6))
        toggle(panel, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description),
            DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
        panel.addView(homeButton(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(10, 52))

        val side = column()
        side.addView(panel)
        side.addView(row().apply {
            fun small(title: String, click: () -> Unit) = addView(homeButton(title, false, click), LinearLayout.LayoutParams(0, dp(52), 1f))
            small(getString(R.string.settings)) { page = "settings"; render() }
            addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
            small(getString(R.string.di_connect_themes)) { page = "themes"; render() }
            addView(space(CONTROL_GAP_DP), LinearLayout.LayoutParams(dp(CONTROL_GAP_DP), 1))
            small(getString(R.string.di_connect_restart)) { confirmRestart() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        availableUpdate?.let { release ->
            val message = getString(R.string.update_available_home)
            val tint = { alpha: Int -> (WARNING and 0x00FFFFFF) or (alpha shl 24) }
            side.addView(label(message, 14, WARNING, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = message
                background = RippleDrawable(
                    ColorStateList.valueOf(RIPPLE),
                    GradientDrawable().apply { setColor(tint(0x26)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), tint(0x80)) },
                    GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() },
                )
                foreground = focusRing(radiusDp = 16)
                setOnClickListener { openAvailableUpdate(release) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        side.addView(label(getString(R.string.home_public_preview, version()), 12, MUTED).apply {
            letterSpacing = .08f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })

        body.addView(stage, LinearLayout.LayoutParams(0, -2, 1.25f))
        body.addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
        body.addView(side, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(body)
    }

