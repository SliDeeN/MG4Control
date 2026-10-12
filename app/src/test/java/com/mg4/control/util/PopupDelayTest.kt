package com.mg4.control.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Réglage « Délai avant fermeture des pop-ups » (Réglages › Interface) : de 1 à 60 secondes,
 * 10 par défaut, commun au sélecteur de profils, à la confirmation d'automatisation et au
 * pop-up HVAC.
 *
 * Ce qui compte ici : une valeur absente ou aberrante dans les préférences ne doit jamais donner
 * un pop-up qui se ferme aussitôt ou qui ne se ferme plus.
 */
class PopupDelayTest {

    @Test fun `jamais regle - dix secondes`() {
        assertEquals(10, PopupDelay.sanitize(null))
    }

    @Test fun `une valeur de la plage est gardee telle quelle`() {
        assertEquals(1, PopupDelay.sanitize(1))
        assertEquals(25, PopupDelay.sanitize(25))
        assertEquals(60, PopupDelay.sanitize(60))
    }

    @Test fun `une valeur hors plage est ramenee a la borne`() {
        // Zéro ou négatif : le pop-up se fermerait avant d'être vu.
        assertEquals(1, PopupDelay.sanitize(0))
        assertEquals(1, PopupDelay.sanitize(-5))
        assertEquals(60, PopupDelay.sanitize(61))
        assertEquals(60, PopupDelay.sanitize(3600))
    }

    @Test fun `le delai est rendu en millisecondes`() {
        assertEquals(10_000L, PopupDelay.toMillis(10))
        assertEquals(60_000L, PopupDelay.toMillis(60))
    }

    @Test fun `en millisecondes aussi la plage est respectee`() {
        assertEquals(1_000L, PopupDelay.toMillis(0))
        assertEquals(60_000L, PopupDelay.toMillis(999))
    }

    @Test fun `le curseur rend un nombre a virgule - arrondi a la seconde`() {
        assertEquals(10, PopupDelay.fromSlider(10f))
        assertEquals(10, PopupDelay.fromSlider(9.6f))
        assertEquals(1, PopupDelay.fromSlider(0.2f))
        assertEquals(60, PopupDelay.fromSlider(60.4f))
    }
}
