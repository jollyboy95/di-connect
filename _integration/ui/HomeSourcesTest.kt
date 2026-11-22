package com.shilapi.xcertplay

import com.shilapi.xcertplay.HomeSources.Projection
import com.shilapi.xcertplay.HomeSources.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's phone detection and audio source list (Haval H7 report, preview r28008722). */
class HomeSourcesTest {
    @Test fun androidAutoSessionCountsAsAPhoneEvenWithoutMusic() {
        // Reported on the car: AA connected and working, Home said "No phone connected".
        val p = HomeSources.projection(androidAutoConnected = true, carPlayActive = false, carPlaySession = false)
        assertEquals(Projection.ANDROID_AUTO, p)
        assertTrue(HomeSources.connected(p))
        assertEquals("Android Auto · Steven's S26", HomeSources.connectionLabel(p, "Steven's S26"))
    }

    @Test fun disconnectingShowsNoPhoneAgain() {
        val p = HomeSources.projection(androidAutoConnected = false, carPlayActive = false, carPlaySession = false)
        assertEquals(Projection.NONE, p)
        assertFalse(HomeSources.connected(p))
        assertEquals("No phone", HomeSources.connectionLabel(p, "Steven's S26"))
    }

    @Test fun streamingCarPlayWinsAndAStartingCarPlayIsNotYetConnected() {
        assertEquals(Projection.CARPLAY, HomeSources.projection(true, carPlayActive = true, carPlaySession = true))
        val starting = HomeSources.projection(false, carPlayActive = false, carPlaySession = true)
        assertEquals(Projection.CARPLAY_STARTING, starting)
        assertFalse(HomeSources.connected(starting))
        assertEquals(Projection.ANDROID_AUTO, HomeSources.projection(true, carPlayActive = false, carPlaySession = true))
    }

    @Test fun bluetoothAudioIsNeverOffered() {
        // The car's Bluetooth music is kept off on purpose; no list may offer it.
        Projection.values().forEach { p ->
            listOf(true, false).forEach { radio -> listOf(true, false).forEach { usb ->
                val names = HomeSources.available(p, radio, usb).map { it.label.lowercase() }
                assertFalse("$p radio=$radio usb=$usb offers Bluetooth: $names", names.any { "bluetooth" in it })
            } }
        }
        assertNull(HomeSources.factorySource("com.gwm.app.media.server.bluetooth"))
        assertTrue("com.gwm.app.media.server.bluetooth" in HomeMediaSessions.IGNORED)
    }

    @Test fun onlySourcesThatCanPlayAreListed() {
        assertEquals(listOf(Source.ANDROID_AUTO, Source.RADIO), HomeSources.available(Projection.ANDROID_AUTO, radioInstalled = true, usbMounted = false))
        assertEquals(listOf(Source.CARPLAY, Source.RADIO, Source.USB), HomeSources.available(Projection.CARPLAY, true, usbMounted = true))
        assertEquals(listOf(Source.RADIO), HomeSources.available(Projection.CARPLAY_STARTING, true, false))
        assertEquals(emptyList<Source>(), HomeSources.available(Projection.NONE, radioInstalled = false, usbMounted = false))
    }

    @Test fun activeSourceIsTheAndroidAutoAudioNotBluetooth() {
        // Reported on the car: music playing through AA, card said "Bluetooth audio".
        assertEquals(Source.ANDROID_AUTO, HomeSources.active(Projection.ANDROID_AUTO, projectionPlaying = true, factoryPkg = null, factoryPlaying = false))
        assertEquals(Source.ANDROID_AUTO, HomeSources.active(Projection.ANDROID_AUTO, true, "com.gwm.app.media.server.radio", factoryPlaying = true))
    }

    @Test fun aPlayingCarSourceWinsOverAnIdleProjection() {
        assertEquals(Source.RADIO, HomeSources.active(Projection.ANDROID_AUTO, projectionPlaying = false, "com.gwm.app.media.server.radio", factoryPlaying = true))
        assertEquals(Source.USB, HomeSources.active(Projection.NONE, false, "com.gwm.app.media.server.usbmusic", true))
        assertEquals(Source.ANDROID_AUTO, HomeSources.active(Projection.ANDROID_AUTO, false, null, false))
        assertNull(HomeSources.active(Projection.NONE, false, null, false))
    }
}
