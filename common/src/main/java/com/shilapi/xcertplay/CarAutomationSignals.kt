package com.shilapi.xcertplay

import android.content.Context
import com.andrerinas.openheadunit.aap.SoftApState
import com.andrerinas.openheadunit.utils.SoftApStateReader

/**
 * Car signals an automation rule can test, read in one pass: the hotspot, the driver's hotspot
 * preference, A/C ECO, the drive mode, speed and gear. Nothing is written to the car.
 *
 * Verified on a Haval H7 (build GW35OVBPU1G091203B11, 21 Nov 2026) through gwm_adapter: the car
 * answers `car.hvac.eco_enable` and `car.drive_setting.drive_mode`, and does not answer the older
 * `car.drive.setting.drive_mode` spelling. What each drive-mode number means has not been mapped
 * yet, so it is kept as a number. A value the car does not answer stays null, which a rule must
 * read as "not supported on this car", never as "off".
 */
internal object CarAutomationSignals {
    const val AC_ECO = "car.hvac.eco_enable"
    const val DRIVE_MODE = "car.drive_setting.drive_mode"
    const val SPEED = GwmVehicleData.SPEED
    const val GEAR_STATUS = GwmVehicleData.GEAR_STATUS
    val KEYS = arrayOf(AC_ECO, DRIVE_MODE, SPEED, GEAR_STATUS)

    data class State(
        /** Whether the car's access point is running; UNKNOWN when the car would not say. */
        val hotspot: SoftApState,
        /** The driver's Android Auto preference to start the car hotspot; null off a GWM car. */
        val hotspotAutoEnable: Boolean?,
        /** A/C ECO; null when this car has no ECO switch. */
        val acEco: Boolean?,
        /** The car's raw drive-mode number; meanings are not mapped yet. */
        val driveMode: Int?,
        val speedKph: Float?,
        val gearStatus: Int?,
    ) {
        val ecoSupported: Boolean get() = acEco != null
        /** Parked or barely moving: a rule that changes something the driver sees should wait for this. */
        val parked: Boolean get() = speedKph != null && speedKph < 3f
    }

    /** One read; call it off the main thread (it is a binder call to the car). */
    fun read(context: Context): State {
        val values = runCatching { GwmVehicleData.read(context, KEYS) }.getOrDefault(emptyMap())
        val hotspot = runCatching { SoftApStateReader.read(context) }.getOrDefault(SoftApState.UNKNOWN)
        val autoEnable = runCatching { com.andrerinas.openheadunit.App.provide(context).settings.autoEnableHotspot }.getOrNull()
        return parse(values, hotspot, autoEnable)
    }

    internal fun parse(values: Map<String, String>, hotspot: SoftApState, hotspotAutoEnable: Boolean?): State = State(
        hotspot = hotspot,
        hotspotAutoEnable = hotspotAutoEnable,
        acEco = GwmVehicleData.number(values[AC_ECO])?.let { it.toInt() == 1 },
        driveMode = GwmVehicleData.number(values[DRIVE_MODE])?.toInt(),
        speedKph = GwmVehicleData.number(values[SPEED])?.takeIf { it in 0f..300f },
        gearStatus = GwmVehicleData.number(values[GEAR_STATUS])?.toInt(),
    )
}
