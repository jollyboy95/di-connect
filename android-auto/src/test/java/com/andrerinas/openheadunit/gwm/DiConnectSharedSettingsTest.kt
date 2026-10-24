package com.andrerinas.openheadunit.gwm

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Android Auto's GWM settings are D-Play's: what the D-Play settings screen saves is what AA uses. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class DiConnectSharedSettingsTest {
    private lateinit var context: Context
    private val dplay get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clear() {
        context = RuntimeEnvironment.getApplication()
        dplay.edit().clear().commit()
        context.getSharedPreferences(GwmSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsMatchDPlayAndKeepAndroidAutosDirectionsCardOff() {
        val gwm = GwmSettings(context)
        assertFalse(gwm.clusterEnabled)
        assertFalse(gwm.navCard)
        assertTrue(gwm.musicCard)
        assertTrue(gwm.styledLayer)
        assertEquals(2, gwm.clusterDisplayId)
        assertEquals(3, gwm.hudDisplayId)
    }

    @Test fun androidAutoReadsWhatDPlaySaved() {
        dplay.edit().putBoolean("cluster_map_enabled", true).putBoolean("gwm_cluster_overlay_enabled", true)
            .putBoolean("gwm_music_card", false).putBoolean("gwm_hud_enabled", true)
            .putInt("gwm_hud_display_id", 4).putInt("gwm_hud_anchor", 2).commit()
        val gwm = GwmSettings(context)
        assertTrue(gwm.clusterEnabled)
        assertFalse(gwm.musicCard)
        assertTrue(gwm.hudEnabled)
        assertEquals(4, gwm.hudDisplayId)
        assertEquals(2, gwm.hudAnchor)
        assertTrue(com.andrerinas.openheadunit.utils.Settings(context).clusterMapEnabled)
    }

    @Test fun theClusterNeedsBothDPlaySwitchesAndAndroidAutoSetsBoth() {
        dplay.edit().putBoolean("gwm_cluster_overlay_enabled", true).commit()
        val gwm = GwmSettings(context)
        assertFalse("The cluster map switch is off", gwm.clusterEnabled)
        gwm.clusterEnabled = true
        assertTrue(dplay.getBoolean("cluster_map_enabled", false))
        assertTrue(gwm.clusterEnabled)
    }

    @Test fun theDirectionsCardIsAndroidAutoOnly() {
        GwmSettings(context).navCard = true
        assertTrue(dplay.getBoolean("di_connect_aa_nav_card", false))
    }
}
