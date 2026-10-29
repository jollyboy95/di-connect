package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Read-only vehicle values from the GWM head unit's `gwm_adapter` binder service
 * (com.gwm.android.adapter.IGwmAdapterService), the same source the H7 dashboard apps use.
 *
 * Transaction 1 takes the caller package and a list of signal keys and returns their values.
 * Nothing is written to the car. Values that stop arriving are reported as missing, not frozen.
 */
internal object GwmVehicleData {
    private const val TAG = "DiPlayGwmVehicle"
    private const val SERVICE = "gwm_adapter"
    private const val DESCRIPTOR = "com.gwm.android.adapter.IGwmAdapterService"
    private const val TRANSACTION_GET_DATA = 1
    /** Writes values; same layout as GET_DATA with the values in place of the second array. */
    private const val TRANSACTION_SET_DATA = 2
    private const val POLL_MILLIS = 250L
    /** A value older than this is treated as unavailable so the cluster never shows a stale speed. */
    const val STALE_MILLIS = 2_000L

    const val SPEED = "car.basic.vehicle_speed"
    const val GEAR_STATUS = "car.basic.gear_status"
    const val CURRENT_GEAR = "car.basic.current_gear"
    const val FUEL_PERCENT = "car.basic.remain_fuel_percentage"
    const val RANGE_KM = "car.basic.remain_odometer"
    const val OUTSIDE_TEMP = "car.basic.outside_temp"
    const val TOTAL_ODOMETER = "car.basic.total_odometer"
    const val ODOMETER = "car.basic.odometer"
    /** Hybrid battery pack current in amps; positive = discharging, negative = charging. */
    const val BATTERY_CURRENT = "car.ev_info.cur_charge_current"
    /** The adapter's "unavailable" answer; never a real reading. */
    private const val UNAVAILABLE = -1001f
    /** Same read-only RPM keys used by SmartSplit v3.4 DataManager. */
    const val ENGINE_RPM = "car.basic.engine_speed"
    const val OFFROAD_ENGINE_RPM = "car.off_road_info.engine_rpm"
    /**
     * Climate and drive-mode keys, read only by the settings scan for now. Names from the same
     * adapter namespace a GWM voice-control app uses; whether writes work is a separate question.
     */
    val CLIMATE_KEYS = arrayOf("car.hvac.power_mode", "car.hvac.ac_enable", "car.hvac.auto_enable", "car.hvac.driver_temperature",
        "car.hvac.pass_temperature", "car.hvac.fan_speed", "car.hvac.sync_enable", "car.hvac.eco_enable", "car.hvac.front_defrost_enable",
        "car.hvac.rear_defrost_enable", "car.hvac.heating_enable", "car.drive.setting.drive_mode", "car.drive_setting.drive_mode")
    val KEYS = arrayOf(SPEED, GEAR_STATUS, CURRENT_GEAR, FUEL_PERCENT, RANGE_KM, OUTSIDE_TEMP, TOTAL_ODOMETER, ODOMETER, BATTERY_CURRENT, ENGINE_RPM, OFFROAD_ENGINE_RPM)

    data class Snapshot(
        val speedKph: Float?,
        val gear: String?,
        val fuelPercent: Float?,
        val rangeKm: Float?,
        val outsideTempC: Float?,
        val atMillis: Long,
        val odometerKm: Float? = null,
        val batteryCurrentA: Float? = null,
        val engineRpm: Float? = null,
    ) {
        fun fresh(now: Long = SystemClock.elapsedRealtime()) = now - atMillis <= STALE_MILLIS
    }

    @Volatile var latest: Snapshot? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot?) -> Unit>()
    private var executor: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null
    @Volatile private var binder: IBinder? = null

    /** Starts polling while at least one listener is registered. Listeners run on the poll thread. */
    @Synchronized fun addListener(context: Context, listener: (Snapshot?) -> Unit) {
        listeners += listener
        if (task != null) return
        val app = context.applicationContext
        val service = executor ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "diplay-gwm-vehicle").apply { isDaemon = true }
        }.also { executor = it }
        task = service.scheduleWithFixedDelay({ poll(app) }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun removeListener(listener: (Snapshot?) -> Unit) {
        listeners -= listener
        if (listeners.isNotEmpty()) return
        task?.cancel(false)
        task = null
    }

    private fun poll(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val snapshot = runCatching { snapshotFrom(read(context), now) }
            .onSuccess { lastError = null }
            .onFailure { error ->
                binder = null
                val message = "${error.javaClass.simpleName}: ${error.message}"
                if (message != lastError) Log.w(TAG, "vehicle read failed: $message")
                lastError = message
            }.getOrNull()
        if (snapshot != null) latest = snapshot
        val current = latest?.takeIf { it.fresh(now) }
        listeners.forEach { runCatching { it(current) } }
    }

    /** One read of every key; used by the settings scan as well as the poller. */
    fun read(context: Context, keys: Array<String> = KEYS): Map<String, String> {
        val service = binder ?: lookup().also { binder = it }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            data.writeString(context.packageName)
            data.writeStringArray(keys)
            data.writeStringArray(null)
            data.writeInt(1)
            check(service.transact(TRANSACTION_GET_DATA, data, reply, 0)) { "transact returned false" }
            reply.readException()
            val values: Array<String> = reply.createStringArray()?.map { it ?: "" }?.toTypedArray() ?: emptyArray()
            return pair(keys, values).filterValues { it.isNotEmpty() }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Writes [values] to [keys] in one call and returns the adapter's status code. [caller] is the
     * package name the adapter is told; the car may only take changes from apps it knows. The
     * adapter accepting a change does not prove the car made it, so callers read the value back.
     */
    fun write(context: Context, keys: Array<String>, values: Array<String>, caller: String = context.packageName): Int {
        val service = binder ?: lookup().also { binder = it }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            data.writeString(caller)
            data.writeStringArray(keys)
            data.writeStringArray(values)
            data.writeInt(1)
            check(service.transact(TRANSACTION_SET_DATA, data, reply, 0)) { "transact returned false" }
            reply.readException()
            return if (reply.dataAvail() >= 4) reply.readInt() else 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun lookup(): IBinder {
        val manager = Class.forName("android.os.ServiceManager")
        val found = manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
        return checkNotNull(found) { "$SERVICE service is not running" }
    }

    /** The reply lists values in key order; some firmware answers "key=value" instead. */
    internal fun pair(keys: Array<String>, values: Array<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        values.forEachIndexed { index, raw ->
            val keyed = keys.firstOrNull { raw.startsWith("$it=") }
            when {
                keyed != null -> result[keyed] = raw.substringAfter('=')
                index < keys.size -> result[keys[index]] = raw
            }
        }
        return result
    }

    private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")

    internal fun number(raw: String?): Float? =
        raw?.let { NUMBER.find(it)?.value?.toFloatOrNull() }?.takeIf { it.isFinite() }

    private fun rpm(raw: String?): Float? = number(raw)?.takeIf { it in 0f..12_000f }

    internal fun snapshotFrom(values: Map<String, String>, now: Long): Snapshot = Snapshot(
        speedKph = number(values[SPEED])?.takeIf { it in 0f..400f },
        gear = gearLabel(number(values[GEAR_STATUS])?.toInt(), number(values[CURRENT_GEAR])?.toInt()),
        fuelPercent = number(values[FUEL_PERCENT])?.takeIf { it in 0f..100f },
        rangeKm = number(values[RANGE_KM])?.takeIf { it in 0f..3000f },
        outsideTempC = number(values[OUTSIDE_TEMP])?.takeIf { it in -60f..80f },
        atMillis = now,
        odometerKm = (number(values[TOTAL_ODOMETER])?.takeIf { it > 0f } ?: number(values[ODOMETER]))
            ?.takeIf { it in 0f..2_000_000f },
        batteryCurrentA = number(values[BATTERY_CURRENT])?.takeIf { it != UNAVAILABLE && it in -1000f..1000f },
        engineRpm = rpm(values[ENGINE_RPM]) ?: rpm(values[OFFROAD_ENGINE_RPM]),
    )

    /** gear_status 0 N, 2 D, 3 P, 4 R, 5 M; in D or M, current_gear 1..8 adds the step (D3, M3). */
    internal fun gearLabel(status: Int?, step: Int?): String? {
        val base = when (status) {
            0 -> "N"
            2 -> "D"
            3 -> "P"
            4 -> "R"
            5 -> "M"
            else -> return null
        }
        return if ((status == 2 || status == 5) && step != null && step in 1..8) "$base$step" else base
    }

    /** Human-readable dump for the GWM settings scan. */
    fun describe(context: Context): String = runCatching {
        val values = read(context)
        val snapshot = snapshotFrom(values, SystemClock.elapsedRealtime())
        buildString {
            appendLine("GWM vehicle data ($SERVICE): reachable")
            KEYS.forEach { appendLine("  $it = ${values[it] ?: "(no value)"}") }
            append("  → speed=${snapshot.speedKph} gear=${snapshot.gear} fuel=${snapshot.fuelPercent}% ")
            append("range=${snapshot.rangeKm} km outside=${snapshot.outsideTempC} °C odo=${snapshot.odometerKm} km ")
            append("battery=${snapshot.batteryCurrentA} A engine=${snapshot.engineRpm} rpm")
            val climate = runCatching { read(context, CLIMATE_KEYS) }.getOrNull()
            appendLine()
            appendLine("Climate and drive mode (read only):")
            if (climate == null) appendLine("  (read failed)")
            else CLIMATE_KEYS.forEach { appendLine("  $it = ${climate[it] ?: "(no value)"}") }
        }
    }.getOrElse { "GWM vehicle data ($SERVICE): unavailable — ${it.javaClass.simpleName}: ${it.message}" }
}

