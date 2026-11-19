package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marche / arrêt de la charge (0x2140f412) : 1 démarrer, 2 arrêter, lu 3 = préparation. */
class ChargeControlTest {

    @Test fun `the button offers the opposite of what the car reads`() {
        assertTrue(ChargeControl.proposeArret(1, enCharge = false))
        assertFalse(ChargeControl.proposeArret(2, enCharge = true))
        // Inconnu (0 sur les A9, illisible) : selon que la voiture charge.
        assertTrue(ChargeControl.proposeArret(0, enCharge = true))
        assertFalse(ChargeControl.proposeArret(null, enCharge = false))
    }

    @Test fun `hidden when unplugged`() {
        assertTrue(ChargeControl.visible(enCharge = true, branchee = null))
        assertTrue(ChargeControl.visible(enCharge = false, branchee = true))
        assertFalse(ChargeControl.visible(enCharge = false, branchee = false))
        assertFalse(ChargeControl.visible(enCharge = false, branchee = null))
    }

    @Test fun `a start is taken once the car prepares or charges`() {
        assertTrue(ChargeControl.pris(ChargeControl.START, 1))
        assertTrue(ChargeControl.pris(ChargeControl.START, 3))
        assertFalse(ChargeControl.pris(ChargeControl.START, 2))
        assertTrue(ChargeControl.pris(ChargeControl.STOP, 2))
        assertFalse(ChargeControl.pris(ChargeControl.STOP, 3))
    }

    @Test fun `automation values`() {
        assertEquals(1, ChargeControl.code("START"))
        assertEquals(2, ChargeControl.code(" stop "))
        assertNull(ChargeControl.code("PAUSE"))
        assertNull(ChargeControl.code(null))
    }
}
