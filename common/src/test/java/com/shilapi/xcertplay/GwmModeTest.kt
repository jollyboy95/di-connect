package com.shilapi.xcertplay

import android.view.Display
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GwmModeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun manualSwitchEnablesTheH7RouteWhenDetectionMisses() {
        // The Robolectric build identity names no GWM, Haval or B11.
        assertFalse(GwmMode.detected())
        assertFalse(GwmMode.active(context))
        assertEquals(Display.INVALID_DISPLAY, GwmMode.selectedClusterDisplayId(context))

        AirPlayPersistence.saveGwmForced(context, true)
        assertTrue(GwmMode.active(context))
        assertEquals(2, GwmMode.selectedClusterDisplayId(context))

        AirPlayPersistence.saveGwmClusterDisplayId(context, 3)
        assertEquals(3, GwmMode.selectedClusterDisplayId(context))

        AirPlayPersistence.saveGwmForced(context, false)
        AirPlayPersistence.saveGwmClusterDisplayId(context, null)
        assertFalse(GwmMode.active(context))
    }
}
