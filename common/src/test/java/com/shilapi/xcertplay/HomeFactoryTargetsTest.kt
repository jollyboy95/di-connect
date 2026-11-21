package com.shilapi.xcertplay

import com.andrerinas.openheadunit.aap.SoftApState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's factory targets and write guard, as verified on a Haval H7 (21 Nov 2026). */
class HomeFactoryTargetsTest {
    @Test fun everyTargetHasAVerifiedComponent() {
        HomeFactoryTargets.Target.values().forEach { target ->
            assertTrue("$target needs a component", HomeFactoryTargets.candidates(target).isNotEmpty())
        }
    }

    @Test fun vehicleSettingsIsTheCarAppNotALabelGuess() {
        // The H7 labels this app "Car": a label search for "vehicle" never found it.
        assertEquals("com.gwm.app.vehicle.center" to "com.gwm.app.vehicle_center.VehicleDesignMainActivity",
            HomeFactoryTargets.candidates(HomeFactoryTargets.Target.VEHICLE).first())
    }

    @Test fun systemAndAndroidSettingsAreDifferentApps() {
        // Both are labelled "Settings", and the GWM app also answers ACTION_SETTINGS.
        val system = HomeFactoryTargets.candidates(HomeFactoryTargets.Target.SYSTEM_SETTINGS).first().first
        val android = HomeFactoryTargets.candidates(HomeFactoryTargets.Target.ANDROID_SETTINGS).first().first
        assertEquals("com.gwm.app.settings", system)
        assertEquals("com.android.settings", android)
    }

    @Test fun factoryHomeIsTheCardLauncher() {
        assertEquals("com.gwm.app.launcher",
            HomeFactoryTargets.candidates(HomeFactoryTargets.Target.FACTORY_HOME).first().first)
    }

    @Test fun engineeringAndTestMenusStayOutOfTheAppGrid() {
        listOf("com.gwm.engineermode", "com.gwm.traceur", "com.gwm.naviengineer", "vendor.gwm.gwmpkiengmode",
            "com.gwmv3.radio.testmode", "com.gwm.eol.connectability", "com.gwm.car.eolaudiotest").forEach {
            assertTrue("$it must be hidden", HomeFactoryTargets.hiddenFromApps(it))
        }
        listOf("com.gwm.app.vehicle.center", "com.gwm.app.mediatunerovs", "com.gwm.app.carphone", "ru.yandex.yandexnavi.auto").forEach {
            assertFalse("$it must stay visible", HomeFactoryTargets.hiddenFromApps(it))
        }
    }

    @Test fun homeWritesOnlyCabinClimate() {
        listOf("car.hvac.driver_temperature", "car.hvac.pass_temperature", "car.hvac.sync_enable", "car.hvac.eco_enable",
            "car.hvac.fan_speed").forEach { assertTrue(it, HomeFactoryTargets.climateWritable(it)) }
        listOf("car.drive_setting.drive_mode", "car.basic.vehicle_speed", "GWM_HUT_EPB_SwtPosn", "car.hvac.power_mode", "").forEach {
            assertFalse("$it must be refused", HomeFactoryTargets.climateWritable(it))
        }
    }

    @Test fun automationSignalsReadWhatTheH7Answered() {
        // Values the H7 returned through gwm_adapter on 21 Nov 2026.
        val state = CarAutomationSignals.parse(
            mapOf(CarAutomationSignals.AC_ECO to "1", CarAutomationSignals.DRIVE_MODE to "0",
                CarAutomationSignals.SPEED to "0.0", CarAutomationSignals.GEAR_STATUS to "3"),
            SoftApState.ENABLED, hotspotAutoEnable = true)
        assertEquals(true, state.acEco)
        assertTrue(state.ecoSupported)
        assertEquals(0, state.driveMode)
        assertEquals(3, state.gearStatus)
        assertTrue(state.parked)
        assertEquals(SoftApState.ENABLED, state.hotspot)
    }

    @Test fun anUnansweredSignalIsUnsupportedNotOff() {
        val state = CarAutomationSignals.parse(emptyMap(), SoftApState.UNKNOWN, hotspotAutoEnable = null)
        assertNull(state.acEco)
        assertFalse(state.ecoSupported)
        assertNull(state.driveMode)
        assertFalse("unknown speed is not parked", state.parked)
    }
}
