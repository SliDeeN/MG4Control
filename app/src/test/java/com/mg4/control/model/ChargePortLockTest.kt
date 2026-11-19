package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verrou de la prise de charge (0x2140f413) : règles des écrans de charge d'origine. */
class ChargePortLockTest {

    @Test
    fun `le bouton propose le contraire de l etat lu`() {
        assertEquals(ChargePortLock.UNLOCK, ChargePortLock.propose(1, a9 = false))
        assertEquals(ChargePortLock.LOCK, ChargePortLock.propose(2, a9 = false))
        assertEquals(ChargePortLock.UNLOCK, ChargePortLock.propose(1, a9 = true))
        assertEquals(ChargePortLock.LOCK, ChargePortLock.propose(2, a9 = true))
    }

    @Test
    fun `lu 0 - les A9 verrouillent, l ancien SDK cache le bouton`() {
        assertEquals(ChargePortLock.LOCK, ChargePortLock.propose(0, a9 = true))
        assertNull(ChargePortLock.propose(0, a9 = false))
        assertNull(ChargePortLock.propose(null, a9 = true))
        assertNull(ChargePortLock.propose(null, a9 = false))
    }

    @Test
    fun `pris quand la voiture relit la demande`() {
        assertTrue(ChargePortLock.pris(ChargePortLock.LOCK, 1))
        assertTrue(ChargePortLock.pris(ChargePortLock.UNLOCK, 2))
        assertFalse(ChargePortLock.pris(ChargePortLock.LOCK, 2))
        assertFalse(ChargePortLock.pris(ChargePortLock.UNLOCK, null))
    }

    @Test
    fun `valeurs des automatisations`() {
        assertEquals(ChargePortLock.LOCK, ChargePortLock.code("LOCK"))
        assertEquals(ChargePortLock.UNLOCK, ChargePortLock.code(" unlock "))
        assertEquals(ChargePortLock.LOCK, ChargePortLock.code("1"))
        assertEquals(ChargePortLock.UNLOCK, ChargePortLock.code("2"))
        assertNull(ChargePortLock.code("0"))
        assertNull(ChargePortLock.code(null))
    }
}
