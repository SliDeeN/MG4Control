package com.mg4.control.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Libellés de bouton (2026-11-03) : deux lignes s'il y a la place, sinon une ; « … » au-delà.
 */
class ButtonLinesTest {

    @Test
    fun `hauteur libre - toujours deux lignes`() {
        assertEquals(2, ButtonLines.lignes(utile = 10, ligne = 30, marges = 0, libre = true))
    }

    @Test
    fun `hauteur fixe qui loge deux lignes`() {
        // 64 dp à 1,5 : 96 px utiles, ligne de 19 sp ≈ 33 px, 4 px de marges de police.
        assertEquals(2, ButtonLines.lignes(utile = 96, ligne = 33, marges = 4, libre = false))
    }

    @Test
    fun `texte agrandi - une seule ligne`() {
        // Même bouton en « Très grand » : la ligne passe à ≈ 46 px, deux ne tiennent plus.
        assertEquals(1, ButtonLines.lignes(utile = 96, ligne = 46, marges = 6, libre = false))
    }

    @Test
    fun `jamais moins d'une ni plus de deux`() {
        assertEquals(1, ButtonLines.lignes(utile = 10, ligne = 33, marges = 4, libre = false))
        assertEquals(2, ButtonLines.lignes(utile = 400, ligne = 33, marges = 4, libre = false))
        assertEquals(1, ButtonLines.lignes(utile = 96, ligne = 0, marges = 0, libre = false))
    }
}
