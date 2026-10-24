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

    @Test fun `le halo entoure la bulle et s arrete aux bords de l ecran`() {
        // Au milieu : la bulle agrandie de la marge de chaque côté.
        assertEquals(FloatingButtons.HaloFrame(478, 278, 244, 164),
            FloatingButtons.haloFrame(500, 300, 200, 120, 22, 1920, 720))
        // Collée en haut à gauche : rognée, jamais en dehors de l'écran.
        assertEquals(FloatingButtons.HaloFrame(0, 0, 222, 142),
            FloatingButtons.haloFrame(0, 0, 200, 120, 22, 1920, 720))
        // Collée en bas à droite.
        assertEquals(FloatingButtons.HaloFrame(1698, 578, 222, 142),
            FloatingButtons.haloFrame(1720, 600, 200, 120, 22, 1920, 720))
    }

    // ── Barre du haut (2026-10-24) ───────────────────────────────────────────

    private val barre = 1000f
    private val languette = FloatingButtons.topBarHvac(barre, 320f)   // 340..660

    @Test fun `barre du haut - la place voulue si elle est libre`() {
        assertEquals(100f, FloatingButtons.placeInTopBar(100f, 80f, barre, listOf(languette), 6f), 0.01f)
    }

    @Test fun `barre du haut - jamais sur la languette, poussé du côté le plus proche`() {
        // Voulu à 360 (dans la languette, plus près de son bord gauche) : collé à gauche.
        assertEquals(254f, FloatingButtons.placeInTopBar(360f, 80f, barre, listOf(languette), 6f), 0.01f)
        // Voulu à 620 : collé à droite.
        assertEquals(666f, FloatingButtons.placeInTopBar(620f, 80f, barre, listOf(languette), 6f), 0.01f)
    }

    @Test fun `barre du haut - à côté d un voisin plutôt que dessus`() {
        val voisin = 100f..180f
        assertEquals(186f, FloatingButtons.placeInTopBar(150f, 60f, barre, listOf(languette, voisin), 6f), 0.01f)
    }

    @Test fun `barre du haut - gardé entre les bords`() {
        assertEquals(6f, FloatingButtons.placeInTopBar(-50f, 80f, barre, listOf(languette), 6f), 0.01f)
        assertEquals(914f, FloatingButtons.placeInTopBar(990f, 80f, barre, listOf(languette), 6f), 0.01f)
    }

    @Test fun `barre du haut - plus de place libre, ramené dans la barre`() {
        val pleine = listOf(0f..1000f)
        assertEquals(920f, FloatingButtons.placeInTopBar(990f, 80f, barre, pleine, 6f), 0.01f)
    }

    @Test fun `barre du haut - texte limité à 14 sp`() {
        val b = FloatingButton(id = "a", action = "HVAC_POPUP", textSp = 20)
        assertEquals(20, FloatingButtons.textFor(b))
        assertEquals(14, FloatingButtons.textFor(b.copy(topBar = true)))
        assertEquals(false, FloatingButtons.inTopBar(b))
    }
}
