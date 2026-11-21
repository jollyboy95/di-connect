package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Di-Connect's start-up automations must not put a factory screen in front of the driver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundAutomationTest {

    // --- App opened at start ---

    @Test
    fun audioTuningIsReplayedWithoutItsScreen() {
        // MainActivity.onCreate -> setInitParameters() in the H7's AudioTuning.apk sends exactly these.
        assertEquals(
            listOf("aux_input=false", "effect=reset:0", "clip=1:0:5:500:100:5:50:10"),
            DiConnectStartApp.RECIPES[DiConnectStartApp.AUDIO_TUNING],
        )
    }

    @Test
    fun otherAppsStartOnTheHiddenDisplay() {
        assertEquals(
            "am start-activity --display 7 -f 0x10010000 -n com.example.app/.MainActivity",
            DiConnectStartApp.offScreenCommand("com.example.app/.MainActivity", 7),
        )
        assertThrows(IllegalArgumentException::class.java) { DiConnectStartApp.offScreenCommand("com.example.app/.Main", 0) }
        assertThrows(IllegalArgumentException::class.java) { DiConnectStartApp.offScreenCommand("x/.Main; reboot", 7) }
    }

    @Test
    fun findsWhichDisplayAnAppLandedOn() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              Stack #26: type=standard mode=fullscreen
                * TaskRecord{1d8f59 #10565 A=com.jollyboy95.diconnect.debug U=0 StackId=26 sz=2}
                  * Hist #0: ActivityRecord{9cf09b8 u0 com.jollyboy95.diconnect.debug/com.shilapi.xcertplay.DiPlayActivity t10565}
              Stack #8: type=standard mode=fullscreen
                * TaskRecord{effc72a #10547 A=com.gwm.car.audiotuning U=0 StackId=8 sz=1}
            Display #2 (activities from top to bottom):
            Display #9 (activities from top to bottom):
              Stack #40: type=standard mode=fullscreen
                * TaskRecord{5280ab8 #10570 A=com.example.app U=0 StackId=40 sz=1}
        """.trimIndent()
        assertEquals(9, DiConnectStartApp.displayOf(dump, "com.example.app"))
        assertEquals(0, DiConnectStartApp.displayOf(dump, "com.gwm.car.audiotuning"))
        assertNull(DiConnectStartApp.displayOf(dump, "com.example.missing"))
    }

    // --- Hotspot band ---

    private val singleFiveGhz = """
        mApConfig.apBand: 1
        mApInterfaceName: wlan1
        mReportedFrequency: 5745
    """.trimIndent()

    @Test
    fun fiveGhzOnOneRadioOpensNothing() {
        val state = HotspotBandGuard.parseAp(singleFiveGhz).copy(bridgedRadios = HotspotBandGuard.bridgedRadios(""))
        assertEquals(1, state.band)
        assertEquals(5745, state.frequency)
        assertEquals("wlan1", state.iface)
        assertEquals(149, state.channel)
        assertTrue(state.fiveGhzOnly)
    }

    @Test
    fun bothBandsIsABridgeEvenThoughApBandSaysFive() {
        val dump = singleFiveGhz.replace("wlan1", "br-softap")
        val state = HotspotBandGuard.parseAp(dump).copy(bridgedRadios = HotspotBandGuard.bridgedRadios("wlan1\nwlan2\n"))
        assertFalse(state.fiveGhzOnly)
        assertTrue(state.describe().contains("Both"))
    }

    @Test
    fun twoPointFourOrUnreadableStillNeedsTheFix() {
        val two = HotspotBandGuard.parseAp("mApConfig.apBand: 0\nmApInterfaceName: wlan1\nmReportedFrequency: 2437").copy(bridgedRadios = 0)
        assertFalse(two.fiveGhzOnly)
        assertEquals(6, two.channel)
        // Bridge not read: not confirmed, so not "already 5 GHz".
        assertFalse(HotspotBandGuard.parseAp(singleFiveGhz).fiveGhzOnly)
        assertFalse(HotspotBandGuard.parseAp("").fiveGhzOnly)
    }

    @Test
    fun interfaceNamesAreCheckedBeforeTheyReachTheShell() {
        assertEquals("wlan1", HotspotBandGuard.safeIface("wlan1"))
        assertNull(HotspotBandGuard.safeIface("wlan1;reboot"))
        assertNull(HotspotBandGuard.safeIface(null))
    }

    @Test
    fun channelsFollowTheirFrequencies() {
        assertEquals(1, HotspotBandGuard.channelOf(2412))
        assertEquals(36, HotspotBandGuard.channelOf(5180))
        assertEquals(165, HotspotBandGuard.channelOf(5825))
        assertNull(HotspotBandGuard.channelOf(60000))
    }

    @Test
    fun hotspotDiagnosticDistinguishesFiveGhzFromBothAndUnknown() {
        val single = HotspotBandGuard.parseAp(singleFiveGhz).copy(bridgedRadios = 0)
        assertTrue(single.diagnostic().startsWith("5 GHz only ("))
        assertTrue(single.copy(iface = "bridge0", bridgedRadios = 2).diagnostic().contains("(Both)"))
        assertTrue(single.copy(bridgedRadios = null).diagnostic().startsWith("5 GHz only could not be confirmed"))
    }
}
