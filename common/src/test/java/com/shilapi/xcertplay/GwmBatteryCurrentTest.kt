package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GwmBatteryCurrentTest {
    private fun current(raw: String) =
        GwmVehicleData.snapshotFrom(mapOf(GwmVehicleData.BATTERY_CURRENT to raw), 0L).batteryCurrentA

    @Test fun readsSignedAmpsAsLogged() {
        assertEquals(188.0f, current("188.0"))
        assertEquals(-104.8f, current("-104.8"))
        assertEquals(2.2f, current("2.2"))
    }

    @Test fun theUnavailableSentinelIsNotAReading() {
        assertNull(current("-1001"))
        assertNull(current(""))
        assertNull(current("5000"))
    }
}
