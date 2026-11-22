package com.andrerinas.openheadunit

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

// Same Robolectric setup as LocaleHelperTest: no network stack, so Conscrypt stays off.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33], manifest = Config.NONE)
class StopGateTest {
    private class Backend
    private val stops = mutableListOf<Backend>()
    private val timeouts = mutableListOf<Backend>()
    private val gate = StopGate<Backend>(Handler(Looper.getMainLooper()), 10_000L,
        requestStop = { stops += it }, onTimeout = { timeouts += it })
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Test fun runsAtOnceWhenNothingIsRunning() {
        var ran = false
        gate.stopThen { ran = true }
        assertTrue(ran)
        assertFalse(gate.stopping)
    }

    @Test fun waitsForTheBackendToStop() {
        gate.current = Backend()
        var ran = 0
        gate.stopThen { ran++ }
        looper.idleFor(Duration.ofSeconds(5))
        assertEquals(0, ran)
        assertTrue(gate.stopping)
        gate.onStopped()
        looper.idle()
        assertEquals(1, ran)
        assertFalse(gate.stopping)
        assertFalse(gate.needsStop())
    }

    @Test fun repeatedRequestsShareOneStop() {
        gate.current = Backend()
        var ran = 0
        gate.stopThen { ran++ }
        gate.stopThen { ran++ }
        assertEquals(1, stops.size)
        gate.onStopped()
        looper.idle()
        assertEquals(2, ran)
    }

    @Test fun timeoutRunsTheWorkAndDoesNotWaitOnTheStuckBackendAgain() {
        val stuck = Backend()
        gate.current = stuck
        var ran = 0
        gate.stopThen { ran++ }
        looper.idleFor(Duration.ofMillis(10_001))
        assertEquals(1, ran)
        assertEquals(listOf(stuck), timeouts)
        assertFalse("A retry must not wait on the stuck backend", gate.needsStop())
        assertTrue("Handover stays active until the backend really stops", gate.stopping)
        gate.stopThen { ran++ }
        assertEquals(2, ran)
        assertEquals(1, stops.size)
        gate.onStopped()
        assertFalse(gate.stopping)
    }

    @Test fun aNewBackendIsWaitedOnEvenAfterAnEarlierTimeout() {
        gate.current = Backend()
        gate.stopThen {}
        looper.idleFor(Duration.ofMillis(10_001))
        gate.current = Backend()
        assertTrue(gate.needsStop())
        assertFalse(gate.stopping)
    }
}
