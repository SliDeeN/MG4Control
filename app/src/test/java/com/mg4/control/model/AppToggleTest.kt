package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raccourci qui ouvre une appli, en bascule (2026-11-19). */
class AppToggleTest {

    private val taches = listOf(7 to "com.waze", 3 to "com.saicmotor.launcher", 5 to "com.spotify.music")

    @Test fun `the app in front brings back the previous screen`() {
        assertTrue(AppToggle.isFront(taches, "com.waze"))
        assertEquals(3, AppToggle.returnTask(taches, "com.waze"))
        // Pas devant : l'appui l'ouvre comme avant.
        assertFalse(AppToggle.isFront(taches, "com.spotify.music"))
        assertFalse(AppToggle.isFront(taches, null))
        assertFalse(AppToggle.isFront(emptyList(), "com.waze"))
    }

    @Test fun `no other task means home`() {
        assertNull(AppToggle.returnTask(listOf(7 to "com.waze", 8 to null), "com.waze"))
    }

    @Test fun `presses toggle, automations do not`() {
        assertTrue(AppToggle.appliesTo("btn1_single"))
        assertTrue(AppToggle.appliesTo("floating:abc"))
        assertTrue(AppToggle.appliesTo(""))
        assertFalse(AppToggle.appliesTo("rule:r1"))
    }
}
