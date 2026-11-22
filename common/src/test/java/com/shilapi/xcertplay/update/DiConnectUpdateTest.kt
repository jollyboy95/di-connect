package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiConnectUpdateTest {
    @Test fun aLaterPreviewRevisionIsAnUpdate() {
        assertTrue(UpdateVersion.isNewer("v0.1.0-preview.r25700000", "0.1.0-preview.r25625761"))
    }

    @Test fun theSameOrAnEarlierRevisionIsNot() {
        assertFalse(UpdateVersion.isNewer("v0.1.0-preview.r25625761", "0.1.0-preview.r25625761"))
        assertFalse(UpdateVersion.isNewer("v0.1.0-preview.r25600000", "0.1.0-preview.r25625761"))
    }

    @Test fun aHigherVersionStillWinsWhateverTheRevision() {
        assertTrue(UpdateVersion.isNewer("v0.2.0-preview.r1", "0.1.0-preview.r25625761"))
        assertFalse(UpdateVersion.isNewer("v0.1.0-preview.r99999999", "0.2.0-preview.r1"))
    }

    @Test fun releaseCandidatesAreNotRevisions() {
        assertEquals(null, UpdateVersion.revision("0.2.14-rc.1"))
        assertFalse(UpdateVersion.isNewer("0.2.14-rc.1", "0.2.14"))
    }

    @Test fun onlyTheSameSigningKeyMatches() {
        assertTrue(UpdateSignature.sameSigners(setOf("aa"), setOf("aa")))
        assertFalse(UpdateSignature.sameSigners(setOf("bb"), setOf("aa")))
        assertFalse(UpdateSignature.sameSigners(emptySet(), emptySet()))
    }
}
