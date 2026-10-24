package com.andrerinas.openheadunit.gwm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GwmSteeringKeysTest {
    @Test fun `the adapter's key string parses into code and action`() {
        val down = GwmSteeringKeys.parse("[1004, 0]", 5L)!!
        assertEquals(1004, down.code)
        assertTrue(down.pressed)
        assertFalse(down.released)
        assertTrue(GwmSteeringKeys.parse("[2006,1]", 0L)!!.released)
        assertTrue(GwmSteeringKeys.parse("[1000, 3]", 0L)!!.released)
        assertTrue(GwmSteeringKeys.parse("[1000, 4]", 0L)!!.released)
        val held = GwmSteeringKeys.parse("[1005, 2]", 0L)!!
        assertFalse(held.pressed)
        assertFalse(held.released)
    }

    @Test fun `junk and unknown actions are ignored`() {
        assertNull(GwmSteeringKeys.parse(null, 0L))
        assertNull(GwmSteeringKeys.parse("", 0L))
        assertNull(GwmSteeringKeys.parse("[1004]", 0L))
        assertNull(GwmSteeringKeys.parse("[1004, 9]", 0L))
        assertNull(GwmSteeringKeys.parse("[-1, 0]", 0L))
    }
}
