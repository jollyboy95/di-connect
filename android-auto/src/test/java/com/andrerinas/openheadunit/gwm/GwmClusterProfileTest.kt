package com.andrerinas.openheadunit.gwm

import com.andrerinas.openheadunit.hud.ClusterMapConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GwmClusterProfileTest {
    private fun profile(w: Int, h: Int, x: Int = 0, y: Int = 0) =
        GwmClusterProfile.profile(w, h, ClusterMapConfig.Detail.NORMAL, x, y)

    @Test fun `an 8 to 3 cluster is drawn full width inside the 1080p frame`() {
        val p = profile(1920, 720)
        assertEquals(1920, p.codecWidth)
        assertEquals(1080, p.codecHeight)
        assertEquals(1920, p.contentWidth)
        assertEquals(720, p.contentHeight)
        assertEquals(0, p.widthMargin)
        assertEquals(360, p.heightMargin)
    }

    @Test fun `an unknown display falls back to the H7 panel shape`() {
        val p = profile(0, 0)
        assertEquals(720, p.contentHeight)
    }

    @Test fun `a panel taller than 16 to 9 is letterboxed at the sides`() {
        val p = profile(1200, 1200)
        assertEquals(1080, p.contentHeight)
        assertEquals(1080, p.contentWidth)
        assertEquals(840, p.widthMargin)
        assertEquals(0, p.heightMargin)
    }

    @Test fun `the safe area keeps the marker between the nav card and the instrument strip`() {
        val i = profile(1920, 720).contentInsets
        assertEquals(720 * GwmClusterProfile.SAFE_TOP / 100, i.top)
        assertEquals(720 * GwmClusterProfile.SAFE_BOTTOM / 100, i.bottom)
        // Below the navigation card (10.5 % + 20 %) and above the strip (76 %).
        assertTrue(i.top >= (720 * 0.305).toInt())
        assertTrue(720 - i.bottom <= (720 * 0.76).toInt())
    }

    @Test fun `marker steps move the safe area without shrinking it`() {
        val (left, right) = GwmClusterProfile.shift(31, 25, 10)
        assertEquals(41, left)
        assertEquals(44, 100 - left - right)
        assertEquals(12 to 0, GwmClusterProfile.shift(6, 6, 15))
        assertEquals(0 to 12, GwmClusterProfile.shift(6, 6, -50))
    }

    @Test fun `the crop shows exactly the drawn content`() {
        val p = profile(1920, 720)
        val m = ClusterMapConfig.cropMatrix(p, 1920, 720)
        assertEquals(1f, m[0], 0.001f)
        assertEquals(1.5f, m[1], 0.001f)
        assertEquals(0f, m[2], 0.001f)
        assertEquals(-180f, m[3], 0.001f)
    }
}
