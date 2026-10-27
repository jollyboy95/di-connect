package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GwmEngineRpmTest {
    @Test fun engineOffIsARealZeroAndBasicRpmWins() {
        val data = GwmVehicleData.snapshotFrom(mapOf(GwmVehicleData.ENGINE_RPM to "0",
            GwmVehicleData.OFFROAD_ENGINE_RPM to "1800"), 0L)
        assertEquals(0f, data.engineRpm!!, .01f)
        assertEquals(1835f, GwmVehicleData.snapshotFrom(mapOf(GwmVehicleData.ENGINE_RPM to "1835.0"), 0L).engineRpm!!, .01f)
    }

    @Test fun unavailableBasicRpmFallsBackWithoutInventingAValue() {
        for (raw in listOf("-1001", "", "NaN", "99999")) {
            assertEquals(2100f, GwmVehicleData.snapshotFrom(mapOf(GwmVehicleData.ENGINE_RPM to raw,
                GwmVehicleData.OFFROAD_ENGINE_RPM to "2100"), 0L).engineRpm!!, .01f)
        }
        assertNull(GwmVehicleData.snapshotFrom(emptyMap(), 0L).engineRpm)
        assertNull(GwmVehicleData.snapshotFrom(mapOf(GwmVehicleData.ENGINE_RPM to "-1001"), 0L).engineRpm)
    }
}
