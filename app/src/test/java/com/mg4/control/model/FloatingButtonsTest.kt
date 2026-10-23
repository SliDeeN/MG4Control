package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Boutons flottants (2026-10-23) : taille aimantée, titre par défaut, positions. */
class FloatingButtonsTest {

    @Test fun `la taille se cale sur les reperes proches et garde les valeurs intermediaires`() {
        assertEquals(0, FloatingButtons.snapSize(2))
        assertEquals(50, FloatingButtons.snapSize(47))
        assertEquals(100, FloatingButtons.snapSize(98))
        assertEquals(72, FloatingButtons.snapSize(72))
        assertEquals(100, FloatingButtons.snapSize(130))
        assertEquals(1, FloatingButtons.presetIndex(50))
        assertNull(FloatingButtons.presetIndex(72))
        assertEquals(44f, FloatingButtons.heightDp(0), 0.01f)
        assertEquals(96f, FloatingButtons.heightDp(100), 0.01f)
    }

    @Test fun `le titre suit le raccourci tant qu il n a pas ete saisi`() {
        val b = FloatingButton(id = "1", action = "HVAC_AC_TOGGLE")
        assertEquals("Clim : A/C (ON/OFF)", FloatingButtons.title(b, "Clim : A/C (ON/OFF)"))
        assertEquals("A/C", FloatingButtons.title(b.copy(customTitle = "A/C"), "Clim : A/C (ON/OFF)"))
        // Effacé ou remis au nom du raccourci : il suit de nouveau l'action.
        assertNull(FloatingButtons.customTitleFor("  ", "Clim : A/C (ON/OFF)"))
        assertNull(FloatingButtons.customTitleFor("Clim : A/C (ON/OFF)", "Clim : A/C (ON/OFF)"))
        assertEquals("A/C", FloatingButtons.customTitleFor(" A/C ", "Clim : A/C (ON/OFF)"))
        assertEquals(24, FloatingButtons.customTitleFor("x".repeat(40), "y")!!.length)
    }

    @Test fun `une bulle reste entiere a l ecran et retrouve son centre`() {
        // Centre à droite, au bord : la bulle est ramenée dans l'écran.
        assertEquals(1920 - 200 to 300, FloatingButtons.topLeft(1f, 0.5f, 200, 120, 1920, 720))
        val (x, y) = FloatingButtons.topLeft(0.5f, 0.5f, 200, 120, 1920, 720)
        val (cx, cy) = FloatingButtons.centre(x, y, 200, 120, 1920, 720)
        assertEquals(0.5f, cx, 0.001f)
        assertEquals(0.5f, cy, 0.001f)
        assertEquals(0.92f to 0.22f, FloatingButtons.defaultPosition(0))
        assertEquals(0.9f, FloatingButtons.defaultPosition(9).second, 0.001f)
    }
}
