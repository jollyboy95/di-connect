package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.UpdateUiConfigRequest
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionClimateInsetTest {
    @Test fun homeClearsBothClimateInsetsWhileKeepingMargins() {
        val config = UpdateUiConfigRequest(5, 6, 7, 8, contentBottom = 0)
            .parse(Media.UpdateUiConfigRequest.newBuilder()).build().uiConfig

        assertTrue(config.hasContentInsets())
        assertTrue(config.hasStableContentInsets())
        assertTrue(config.contentInsets.hasBottom())
        assertEquals(0, config.contentInsets.bottom)
        assertEquals(0, config.stableContentInsets.bottom)
        assertEquals(6, config.margins.top)
        assertEquals(8, config.margins.bottom)
    }

    @Test fun ordinaryProjectionCanRestoreItsClimateReservation() {
        val config = UpdateUiConfigRequest(0, 0, 0, 0, contentBottom = 81)
            .parse(Media.UpdateUiConfigRequest.newBuilder()).build().uiConfig

        assertEquals(81, config.contentInsets.bottom)
        assertEquals(81, config.stableContentInsets.bottom)
    }

    @Test fun marginOnlyUpdatesKeepTheOriginalMessageShape() {
        val config = UpdateUiConfigRequest(4, 3, 2, 1)
            .parse(Media.UpdateUiConfigRequest.newBuilder()).build().uiConfig

        assertEquals(1, config.margins.bottom)
        assertFalse(config.hasContentInsets())
        assertFalse(config.hasStableContentInsets())
    }
}
