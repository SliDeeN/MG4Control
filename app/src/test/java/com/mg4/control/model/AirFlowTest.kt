package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sens de l'air — logique pure : combinaison de boutons ↔ valeur de HVAC_BLOWER_DIRECTION.
 *
 * L'échelle vient du firmware (tableau d'icônes de SystemUI, identique sur SWI69 et SWI131) et
 * n'est PAS un masque de bits : « visage + pare-brise » vaut 6, « tout » vaut 5. Une erreur dans
 * cette table enverrait l'air au mauvais endroit sans rien signaler — d'où le tour complet.
 */
class AirFlowTest {

    @Test
    fun `chaque combinaison donne la valeur du firmware`() {
        assertEquals(0, AirFlow.directionFor(face = true,  feet = false, windshield = false))
        assertEquals(1, AirFlow.directionFor(face = true,  feet = true,  windshield = false))
        assertEquals(2, AirFlow.directionFor(face = false, feet = true,  windshield = false))
        assertEquals(3, AirFlow.directionFor(face = false, feet = true,  windshield = true))
        assertEquals(4, AirFlow.directionFor(face = false, feet = false, windshield = true))
        assertEquals(5, AirFlow.directionFor(face = true,  feet = true,  windshield = true))
        assertEquals(6, AirFlow.directionFor(face = true,  feet = false, windshield = true))
    }

    @Test
    fun `aucun bouton ne donne aucune valeur`() {
        // Rien à écrire : l'air doit bien sortir quelque part, c'est à l'écran de l'empêcher.
        assertNull(AirFlow.directionFor(face = false, feet = false, windshield = false))
    }

    @Test
    fun `toute valeur ecrite se relit en la meme combinaison`() {
        (0..6).forEach { v ->
            val p = AirFlow.partsOf(v)!!
            assertEquals("valeur $v", v, AirFlow.directionFor(p.face, p.feet, p.windshield))
        }
    }

    @Test
    fun `une valeur hors echelle n allume aucun bouton`() {
        // 7 = « aucun » (icône null du firmware) ; -1 = illisible ; 8 n'existe pas.
        assertNull(AirFlow.partsOf(7))
        assertNull(AirFlow.partsOf(-1))
        assertNull(AirFlow.partsOf(8))
    }

    @Test
    fun `le masque du profil se traduit en valeur`() {
        assertEquals(2, AirFlow.directionForMask(AirFlow.FEET))
        assertEquals(3, AirFlow.directionForMask(AirFlow.FEET or AirFlow.WINDSHIELD))
        assertEquals(6, AirFlow.directionForMask(AirFlow.FACE or AirFlow.WINDSHIELD or AirFlow.REAR_DEFROST))
    }

    @Test
    fun `le degivrage arriere seul ne touche pas au sens de l air`() {
        // La lunette arrière n'est pas dans l'échelle du sens de l'air : elle a sa propre commande.
        assertNull(AirFlow.directionForMask(AirFlow.REAR_DEFROST))
    }
}
