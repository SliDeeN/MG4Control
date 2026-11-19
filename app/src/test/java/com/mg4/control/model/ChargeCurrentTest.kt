package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Courant de charge AC (#215) : codes 1..4 = 6 A, 8 A, 16 A, Max. */
class ChargeCurrentTest {

    @Test fun `codes and amperes follow the stock widget`() {
        assertEquals(listOf(6, 8, 16, null), (1..4).map { ChargeCurrent.amperes(it) })
        assertTrue((1..4).all { ChargeCurrent.valide(it) })
        assertFalse(ChargeCurrent.valide(0))
        assertFalse(ChargeCurrent.valide(-1))
        assertFalse(ChargeCurrent.valide(5))
        assertFalse(ChargeCurrent.valide(null))
    }

    @Test fun `automation values round-trip`() {
        (1..4).forEach { assertEquals(it, ChargeCurrent.code(ChargeCurrent.texte(it))) }
        assertNull(ChargeCurrent.texte(0))
        assertEquals(3, ChargeCurrent.code("16 A"))
        assertEquals(3, ChargeCurrent.code("16"))
        assertEquals(1, ChargeCurrent.code("6a"))
        assertEquals(4, ChargeCurrent.code("max"))
        assertNull(ChargeCurrent.code("12A"))
        assertNull(ChargeCurrent.code(""))
        assertNull(ChargeCurrent.code(null))
    }
}
