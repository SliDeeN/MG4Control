package com.mg4.control.model

import com.mg4.control.model.SystemGestures.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verrou SAIC des glissements depuis le bord (2026-11-20). */
class SystemGesturesTest {

    @Test
    fun `turning full screen on enables the gestures unless already on`() {
        assertEquals(Action.ACTIVER, SystemGestures.action(voulu = true, lu = null, parNous = false))
        assertEquals(Action.ACTIVER, SystemGestures.action(voulu = true, lu = false, parNous = false))
        assertEquals(Action.RIEN, SystemGestures.action(voulu = true, lu = true, parNous = false))
        assertEquals(Action.RIEN, SystemGestures.action(voulu = true, lu = true, parNous = true))
    }

    @Test
    fun `turning it off only restores what MG4Control wrote`() {
        assertEquals(Action.RESTAURER, SystemGestures.action(voulu = false, lu = true, parNous = true))
        // Mise à vrai par quelqu'un d'autre : on n'y touche pas.
        assertEquals(Action.RIEN, SystemGestures.action(voulu = false, lu = true, parNous = false))
        assertEquals(Action.RIEN, SystemGestures.action(voulu = false, lu = false, parNous = true))
        assertEquals(Action.RIEN, SystemGestures.action(voulu = false, lu = null, parNous = false))
    }

    @Test
    fun `the restart notice shows until the screen has rebooted`() {
        assertTrue(SystemGestures.enAttente(lu = true, demarrageEcriture = 41, demarrageActuel = 41))
        assertFalse(SystemGestures.enAttente(lu = true, demarrageEcriture = 41, demarrageActuel = 42))
        assertFalse(SystemGestures.enAttente(lu = true, demarrageEcriture = null, demarrageActuel = 41))
        assertFalse(SystemGestures.enAttente(lu = true, demarrageEcriture = 41, demarrageActuel = null))
        assertFalse(SystemGestures.enAttente(lu = false, demarrageEcriture = 41, demarrageActuel = 41))
    }

    @Test
    fun `the property is read like Android does`() {
        assertEquals(true, SystemGestures.lire("true"))
        assertEquals(true, SystemGestures.lire("1"))
        assertEquals(true, SystemGestures.lire(" Yes "))
        assertEquals(false, SystemGestures.lire("false"))
        assertEquals(false, SystemGestures.lire("0"))
        assertNull(SystemGestures.lire(""))
        assertNull(SystemGestures.lire(null))
        assertNull(SystemGestures.lire("peut-être"))
    }
}
