package com.andrerinas.openheadunit.gwm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GwmMusicStateTest {
    @Test fun `progress advances while playing and stops at the duration`() {
        val s = GwmMusicState.Snapshot(title = "Song", durationMillis = 10_000L, positionMillis = 4_000L,
            playing = true, positionAt = 1_000L)
        assertEquals(6_000L, s.positionNow(now = 3_000L))
        assertEquals(10_000L, s.positionNow(now = 60_000L))
    }

    @Test fun `paused progress does not move and an unknown position stays unknown`() {
        val paused = GwmMusicState.Snapshot(title = "Song", positionMillis = 4_000L, playing = false, positionAt = 1_000L)
        assertEquals(4_000L, paused.positionNow(now = 9_000L))
        assertNull(GwmMusicState.Snapshot(title = "Song").positionNow(now = 9_000L))
    }

    @Test fun `a blank title is no track`() {
        assertFalse(GwmMusicState.Snapshot(title = " ").hasTrack)
        assertTrue(GwmMusicState.Snapshot(title = "Song").hasTrack)
    }
}
