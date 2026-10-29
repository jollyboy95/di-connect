package com.shilapi.xcertplay

import android.content.Context
import android.util.Log

/**
 * A small, reversible climate check before any climate control is built: change the driver's
 * temperature by one degree or toggle A/C, then read the value back. Uses the gwm_adapter keys a
 * GWM voice-control app uses. Di-Connect's own package is tried first; if the car does not act on
 * it, the GWM voice assistant's package name is tried, as that app does. Temperature stays within
 * 16-32 °C.
 */
internal object ClimateTest {
    private const val TAG = "DiConnect-Climate"
    private const val DRIVER_TEMP = "car.hvac.driver_temperature"
    private const val AC = "car.hvac.ac_enable"
    private const val VOICE_ASSISTANT = "com.gwm.voiceassistantoversea"

    fun temperatureStep(context: Context, delta: Int): String = runCatching {
        val before = GwmVehicleData.read(context, arrayOf(DRIVER_TEMP))[DRIVER_TEMP]
        val current = before?.toFloatOrNull()?.toInt() ?: return@runCatching "Could not read the driver temperature (got '${before}')"
        val target = (current + delta).coerceIn(16, 32)
        if (target == current) return@runCatching "Already at the limit ($current °C)"
        attempt(context, DRIVER_TEMP, target.toString()) { it?.toFloatOrNull()?.toInt() == target }
            .let { "Driver temperature $current → $target °C: $it" }
    }.getOrElse { "Climate test failed: ${it.javaClass.simpleName}: ${it.message}" }.also { Log.i(TAG, it) }

    fun toggleAc(context: Context): String = runCatching {
        val before = GwmVehicleData.read(context, arrayOf(AC))[AC]
        val on = before?.toFloatOrNull()?.toInt() ?: return@runCatching "Could not read A/C (got '${before}')"
        val target = if (on == 1) 0 else 1
        attempt(context, AC, target.toString()) { it?.toFloatOrNull()?.toInt() == target }
            .let { "A/C ${if (on == 1) "on → off" else "off → on"}: $it" }
    }.getOrElse { "Climate test failed: ${it.javaClass.simpleName}: ${it.message}" }.also { Log.i(TAG, it) }

    /** Names a GWM voice-control app uses for the driver's seat ventilation; the H7 answers to one of them. */
    private val DRIVER_VENT_KEYS = arrayOf("car.comfort_setting.driver_seat_ventilation_level",
        "car.comfort_setting.driver_seat_ventilation_level_nine", "car.hvac.seat_ventilation_fr_mode",
        "car.hvac.seat_ventilation_fl_mode", "car.comfort.setting.driver_seat_ventilation_level")

    /** Steps the driver's seat ventilation through off, 1, 2, 3 and back to off. */
    fun stepDriverVentilation(context: Context): String = runCatching {
        val values = GwmVehicleData.read(context, DRIVER_VENT_KEYS)
        val key = DRIVER_VENT_KEYS.firstOrNull { values[it]?.toFloatOrNull() != null }
            ?: return@runCatching "No seat ventilation value from the car (tried ${DRIVER_VENT_KEYS.joinToString { it.substringAfterLast('.') }}; got $values)"
        val level = values[key]!!.toFloat().toInt()
        val target = if (level >= 3 || level < 0) 0 else level + 1
        attempt(context, key, target.toString()) { it?.toFloatOrNull()?.toInt() == target }
            .let { "Seat ventilation ($key) $level → $target: $it" }
    }.getOrElse { "Climate test failed: ${it.javaClass.simpleName}: ${it.message}" }.also { Log.i(TAG, it) }

    /** Writes as Di-Connect, then as the voice assistant if the value did not change. */
    private fun attempt(context: Context, key: String, value: String, done: (String?) -> Boolean): String {
        val notes = mutableListOf<String>()
        for (caller in listOf(context.packageName, VOICE_ASSISTANT)) {
            val status = runCatching { GwmVehicleData.write(context, arrayOf(key), arrayOf(value), caller) }
            Thread.sleep(800)
            val after = runCatching { GwmVehicleData.read(context, arrayOf(key))[key] }.getOrNull()
            val who = if (caller == VOICE_ASSISTANT) "as voice assistant" else "as Di-Connect"
            val result = status.fold({ "status $it" }, { "${it.javaClass.simpleName}" })
            if (done(after)) return "worked $who ($result, read back $after)"
            notes += "$who: $result, read back ${after ?: "nothing"}"
        }
        return "no change (" + notes.joinToString("; ") + ")"
    }
}
