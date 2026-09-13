package com.mg4.control.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Taille du texte — logique pure : correspondance clé ↔ taille et coefficients.
 *
 * Ce qui est à risque, c'est la PROMESSE faite dans l'écran : « chaque taille ajoute 2 ». Elle
 * n'est exacte que sur 16 sp, la taille courante de l'app ; un coefficient retouché sans y penser
 * la casserait sans que rien ne le signale.
 */
class TextSizeTest {

    @Test
    fun `chaque taille ajoute 2 sp au texte courant de 16 sp`() {
        assertEquals(16f, 16f * TextSize.STANDARD.scale, 0.001f)
        assertEquals(18f, 16f * TextSize.LARGE.scale, 0.001f)
        assertEquals(20f, 16f * TextSize.XLARGE.scale, 0.001f)
    }

    @Test
    fun `une cle enregistree retrouve sa taille`() {
        TextSize.entries.forEach { assertEquals(it, TextSize.fromKey(it.key)) }
    }

    @Test
    fun `une cle absente ou inconnue donne la taille standard`() {
        // Absente : installation neuve ou mise à jour depuis une version sans le réglage.
        assertEquals(TextSize.STANDARD, TextSize.fromKey(null))
        // Inconnue : valeur écrite par une version future puis retour arrière.
        assertEquals(TextSize.STANDARD, TextSize.fromKey("gigantesque"))
    }
}
