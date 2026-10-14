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

    // ── Reprise des anciennes lignes Dég. AV / Dég. AR ─────────────────────

    @Test
    fun `un ancien degivrage en marche devient le bouton correspondant`() {
        assertEquals(AirFlow.WINDSHIELD, AirFlow.fromLegacyDefrost(front = true, rear = null))
        assertEquals(AirFlow.REAR_DEFROST, AirFlow.fromLegacyDefrost(front = null, rear = true))
        assertEquals(AirFlow.WINDSHIELD or AirFlow.REAR_DEFROST,
            AirFlow.fromLegacyDefrost(front = true, rear = true))
    }

    @Test
    fun `un ancien profil sans degivrage en marche reste inchange`() {
        // « Off » seul n'a pas d'équivalent dans la ligne Air : un masque vide y signifie
        // « Inchangé ». Mieux vaut ne plus rien imposer que forcer un sens de l'air inventé.
        assertNull(AirFlow.fromLegacyDefrost(front = null, rear = null))
        assertNull(AirFlow.fromLegacyDefrost(front = false, rear = false))
        assertNull(AirFlow.fromLegacyDefrost(front = false, rear = null))
    }

    @Test
    fun `le degivrage arriere seul ne touche pas au sens de l air`() {
        // La lunette arrière n'est pas dans l'échelle du sens de l'air : elle a sa propre commande.
        assertNull(AirFlow.directionForMask(AirFlow.REAR_DEFROST))
    }

    // ── Appui sur un bouton cumulable (page Clim et pop-up HVAC) ──────────────

    @Test
    fun `un appui allume le bouton en gardant les autres`() {
        assertEquals(1, AirFlow.toggled(0, AirFlow.FEET))         // visage → visage + pieds
        assertEquals(6, AirFlow.toggled(0, AirFlow.WINDSHIELD))   // visage → visage + pare-brise
        assertEquals(5, AirFlow.toggled(1, AirFlow.WINDSHIELD))   // visage + pieds → les trois
    }

    @Test
    fun `un appui eteint le bouton deja allume`() {
        assertEquals(2, AirFlow.toggled(1, AirFlow.FACE))         // visage + pieds → pieds
        assertEquals(1, AirFlow.toggled(5, AirFlow.WINDSHIELD))   // les trois → visage + pieds
    }

    @Test
    fun `le dernier bouton allume ne s eteint pas`() {
        // L'air doit bien sortir quelque part : rien à écrire.
        assertNull(AirFlow.toggled(0, AirFlow.FACE))
        assertNull(AirFlow.toggled(2, AirFlow.FEET))
        assertNull(AirFlow.toggled(4, AirFlow.WINDSHIELD))
    }

    @Test
    fun `depuis aucun sens un appui allume ce seul bouton`() {
        // 7 = « aucun » dans le firmware : lu mais hors échelle, les trois boutons sont éteints.
        assertEquals(0, AirFlow.toggled(7, AirFlow.FACE))
        assertEquals(2, AirFlow.toggled(7, AirFlow.FEET))
        assertEquals(4, AirFlow.toggled(7, AirFlow.WINDSHIELD))
    }

    @Test
    fun `la lunette arriere n est pas un sens de l air`() {
        // Elle a sa propre commande (dégivrage arrière) : aucun sens de l'air à écrire pour elle.
        assertNull(AirFlow.toggled(0, AirFlow.REAR_DEFROST))
    }
}
