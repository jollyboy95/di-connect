package com.andrerinas.openheadunit.gwm

import org.junit.Assert.assertEquals
import org.junit.Test

class GwmHudAreaTest {
    @Test fun `an area stays on the display and never collapses`() {
        assertEquals(GwmSettings.HudArea(40, 20, 30, 60), GwmSettings.HudArea(40, 20, 30, 60).sane())
        assertEquals(GwmSettings.HudArea(95, 0, 5, 100), GwmSettings.HudArea(120, -5, 50, 200).sane())
        assertEquals(GwmSettings.HudArea(60, 70, 40, 30), GwmSettings.HudArea(60, 70, 90, 90).sane())
        assertEquals(GwmSettings.HudArea(0, 0, 5, 5), GwmSettings.HudArea(0, 0, 0, 0).sane())
    }
}
