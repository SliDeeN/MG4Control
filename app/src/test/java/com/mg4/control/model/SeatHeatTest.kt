package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sièges chauffants — atteindre un niveau sur une voiture qui ne connaît qu'une commande, « un
 * cran », et dont chaque cran fait DESCENDRE : éteint → 3 → 2 → 1 → éteint.
 *
 * Le sens a été établi sur véhicule (SWI133, 2026-10-09) par sept essais sur les boutons de
 * niveau, alors que le compte supposait à tort que chaque cran montait : demander 1 depuis éteint
 * donnait 3, demander 2 depuis 3 éteignait le siège… Ces sept essais sont rejoués ici.
 */
class SeatHeatTest {

    /** Voiture factice : chaque cran DESCEND d'un niveau, et le niveau LU a [retard] ms de retard. */
    private class Voiture(depart: Int, private val retard: Long, private val sourde: Boolean = false) {
        var maintenant = 0L
        val crans = mutableListOf<Long>()
        private val histoire = mutableListOf(Long.MIN_VALUE / 2 to depart)

        val niveau: Int get() = histoire.last().second
        fun lire(): Int = histoire.last { it.first <= maintenant - retard }.second
        fun attendre(ms: Long) { maintenant += ms }
        fun cran() {
            crans += maintenant
            if (!sourde) histoire += maintenant to (niveau + 3) % 4
        }

        fun atteindre(cible: Int) = SeatHeat.reach(cible, ::lire, ::cran, ::attendre)
    }

    // ── Le sens du cycle ─────────────────────────────────────────────────────

    @Test
    fun `un cran suit l ordre de la voiture`() {
        // Éteint → 3 → 2 → 1 → éteint : c'est ce que montre le pop-up après un appui.
        assertEquals(3, SeatHeat.next(0))
        assertEquals(2, SeatHeat.next(3))
        assertEquals(1, SeatHeat.next(2))
        assertEquals(0, SeatHeat.next(1))
    }

    @Test
    fun `sans niveau lisible on ne predit rien`() {
        assertNull(SeatHeat.next(null))
    }

    @Test
    fun `un niveau hors echelle repart de l echelle`() {
        assertEquals(2, SeatHeat.next(7))
        assertEquals(3, SeatHeat.next(-1))
    }

    // ── Combien de crans ─────────────────────────────────────────────────────

    @Test
    fun `descendre d un niveau coute un cran et monter en coute trois`() {
        assertEquals(0, SeatHeat.steps(2, 2))
        assertEquals(1, SeatHeat.steps(2, 1))
        assertEquals(1, SeatHeat.steps(1, 0))
        assertEquals(1, SeatHeat.steps(0, 3))    // éteint → 3 : le premier cran
        // Monter, c'est faire le tour par « éteint » : la voiture ne remonte pas.
        assertEquals(3, SeatHeat.steps(1, 2))
        assertEquals(3, SeatHeat.steps(0, 1))
    }

    @Test
    fun `hors echelle il n y a pas de compte possible`() {
        assertNull(SeatHeat.steps(-1, 2))    // niveau illisible
        assertNull(SeatHeat.steps(1, 7))     // cible qui n'existe pas
    }

    // ── Atteindre un niveau ──────────────────────────────────────────────────

    @Test
    fun `les sept essais faits sur la voiture arrivent au niveau demande`() {
        // (départ, demandé). Avec le compte à l'envers, ils donnaient 3, 2, 3, 3, éteint, 1 et 1.
        val essais = listOf(0 to 1, 0 to 2, 2 to 1, 1 to 3, 3 to 2, 2 to 3, 0 to 3)
        essais.forEach { (depart, demande) ->
            val voiture = Voiture(depart, retard = 1_000)
            assertTrue("$depart → $demande confirmé", voiture.atteindre(demande))
            assertEquals("$depart → $demande", demande, voiture.niveau)
        }
    }

    @Test
    fun `descendre d un niveau prend un seul cran meme si la voiture l annonce en retard`() {
        val voiture = Voiture(depart = 2, retard = 1_000)
        assertTrue(voiture.atteindre(1))
        assertEquals("crans envoyés", 1, voiture.crans.size)
    }

    @Test
    fun `monter d un niveau fait le tour sans depasser`() {
        val voiture = Voiture(depart = 1, retard = 1_000)
        assertTrue(voiture.atteindre(2))
        assertEquals("crans envoyés", 3, voiture.crans.size)
        assertEquals(2, voiture.niveau)
    }

    @Test
    fun `les crans sont espaces`() {
        // Trop rapprochés, la voiture pourrait en ignorer un.
        val voiture = Voiture(depart = 0, retard = 1_000)
        voiture.atteindre(1)
        voiture.crans.zipWithNext { a, b -> assertTrue("écart ${b - a} ms", b - a >= SeatHeat.CLICK_INTERVAL_MS) }
        assertEquals(3, voiture.crans.size)
    }

    @Test
    fun `deja au bon niveau rien n est envoye`() {
        val voiture = Voiture(depart = 2, retard = 0)
        assertTrue(voiture.atteindre(2))
        assertEquals(0, voiture.crans.size)
    }

    @Test
    fun `sans niveau lisible rien n est envoye`() {
        var crans = 0
        assertFalse(SeatHeat.reach(2, lire = { -1 }, cran = { crans++ }, attendre = {}))
        assertEquals(0, crans)
    }

    @Test
    fun `une cible hors echelle n est pas envoyee`() {
        val voiture = Voiture(depart = 1, retard = 0)
        assertFalse(voiture.atteindre(7))
        assertEquals(0, voiture.crans.size)
    }

    @Test
    fun `si la voiture ne suit pas on le signale sans insister`() {
        // Aucun cran de rattrapage : avec une lecture en retard, insister ferait dépasser.
        val voiture = Voiture(depart = 2, retard = 0, sourde = true)
        assertFalse(voiture.atteindre(1))
        assertEquals("crans envoyés", 1, voiture.crans.size)
    }
}
