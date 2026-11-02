package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class GwmVehicleDataTest {
    private val keys = GwmVehicleData.KEYS

    @Test fun valuesInKeyOrderAndKeyedRepliesBothParse() {
        val ordered = GwmVehicleData.pair(keys, arrayOf("62.5", "2", "4", "48", "390", "23.0"))
        val keyed = GwmVehicleData.pair(keys, keys.zip(arrayOf("62.5", "2", "4", "48", "390", "23.0"))
            .map { (k, v) -> "$k=$v" }.reversed().toTypedArray())
        assertEquals(ordered, keyed)
        val snapshot = GwmVehicleData.snapshotFrom(ordered, 1000L)
        assertEquals(62.5f, snapshot.speedKph!!, 0.01f)
        assertEquals("D4", snapshot.gear)
        assertEquals(48f, snapshot.fuelPercent!!, 0.01f)
        assertEquals(390f, snapshot.rangeKm!!, 0.01f)
        assertEquals(23f, snapshot.outsideTempC!!, 0.01f)
    }

    @Test fun gearStatusFollowsTheH7Encoding() {
        assertEquals("N", GwmVehicleData.gearLabel(0, 3))
        assertEquals("D", GwmVehicleData.gearLabel(2, 0))
        assertEquals("P", GwmVehicleData.gearLabel(3, 2))
        assertEquals("R", GwmVehicleData.gearLabel(4, 1))
        assertEquals("M3", GwmVehicleData.gearLabel(5, 3))
        assertNull(GwmVehicleData.gearLabel(9, 1))
        assertNull(GwmVehicleData.gearLabel(null, null))
    }

    @Test fun unitsAndJunkAreToleratedButNonsenseIsDropped() {
        val values = mapOf(
            GwmVehicleData.SPEED to "61 km/h",
            GwmVehicleData.FUEL_PERCENT to "180",
            GwmVehicleData.RANGE_KM to "--",
            GwmVehicleData.OUTSIDE_TEMP to "-4.5",
        )
        val snapshot = GwmVehicleData.snapshotFrom(values, 0L)
        assertEquals(61f, snapshot.speedKph!!, 0.01f)
        assertNull(snapshot.fuelPercent)
        assertNull(snapshot.rangeKm)
        assertEquals(-4.5f, snapshot.outsideTempC!!, 0.01f)
        assertNull(snapshot.gear)
    }
}
