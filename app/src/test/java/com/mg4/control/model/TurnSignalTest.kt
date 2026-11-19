package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clignotants (0x21409326) : valeurs et extinction (immédiate par défaut, délai possible). */
class TurnSignalTest {

    @Test fun `raw values`() {
        assertEquals(listOf("OFF", "LEFT", "RIGHT", "HAZARD"), (0..3).map { TurnSignal.texte(it) })
        assertNull(TurnSignal.texte(7))
        assertNull(TurnSignal.texte(null))
    }

    @Test fun `off counts at once by default`() {
        val f = TurnSignalDebounce()
        f.onSample("LEFT", 0)
        assertTrue(f.onSample("OFF", 250))
        assertEquals("OFF", f.stable)
    }

    @Test fun `with a delay, off only after a quiet second and a half`() {
        val f = TurnSignalDebounce(extinctionMs = 1_500L)
        assertTrue(f.onSample("OFF", 0))
        assertEquals("OFF", f.stable)
        assertTrue(f.onSample("LEFT", 100))
        // La lampe s'éteint entre deux clignotements : la valeur stable ne bouge pas.
        assertFalse(f.onSample("OFF", 500))
        assertFalse(f.onSample("LEFT", 900))
        assertFalse(f.onSample("OFF", 1_300))
        assertFalse(f.onSample("OFF", 2_700))
        assertEquals("LEFT", f.stable)
        assertTrue(f.onSample("OFF", 2_800))
        assertEquals("OFF", f.stable)
    }

    @Test fun `changing side or hazard counts at once, unreadable changes nothing`() {
        val f = TurnSignalDebounce()
        f.onSample("LEFT", 0)
        assertTrue(f.onSample("RIGHT", 250))
        assertTrue(f.onSample("HAZARD", 500))
        assertFalse(f.onSample(null, 750))
        assertEquals("HAZARD", f.stable)
    }
}
