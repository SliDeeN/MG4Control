package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sièges chauffants — atteindre un niveau sur une voiture qui ne sait qu'AVANCER d'un cran
 * (éteint → 1 → 2 → 3 → éteint) et qui annonce son niveau avec retard.
 *
 * Constaté sur SWI133 le 2026-10-09 : demander « 2 » depuis « 1 » faisait défiler 2, 3, éteint,
 * 1, 2. L'ancienne boucle recliquait tant que le niveau LU n'était pas le bon ; un cran partait
 * donc en trop dès que la voiture tardait à l'annoncer, et il fallait refaire tout le tour.
 */
class SeatHeatTest {

    /** Voiture factice : chaque cran avance d'un niveau, et le niveau LU a [retard] ms de retard. */
    private class Voiture(depart: Int, private val retard: Long, private val sourde: Boolean = false) {
        var maintenant = 0L
        val crans = mutableListOf<Long>()
        private val histoire = mutableListOf(Long.MIN_VALUE / 2 to depart)

        val niveau: Int get() = histoire.last().second
        fun lire(): Int = histoire.last { it.first <= maintenant - retard }.second
        fun attendre(ms: Long) { maintenant += ms }
        fun cran() {
            crans += maintenant
            if (!sourde) histoire += maintenant to (niveau + 1) % 4
        }

        fun atteindre(cible: Int) = SeatHeat.reach(cible, ::lire, ::cran, ::attendre)
    }

    // ── Combien de crans ─────────────────────────────────────────────────────

    @Test
    fun `le nombre de crans suit le sens unique du cycle`() {
        assertEquals(0, SeatHeat.steps(2, 2))
        assertEquals(1, SeatHeat.steps(1, 2))
        assertEquals(1, SeatHeat.steps(3, 0))
        assertEquals(3, SeatHeat.steps(0, 3))
        // Descendre d'un niveau, c'est passer par les autres : la voiture ne recule pas.
        assertEquals(3, SeatHeat.steps(2, 1))
    }

    @Test
    fun `hors echelle il n y a pas de compte possible`() {
        assertNull(SeatHeat.steps(-1, 2))    // niveau illisible
        assertNull(SeatHeat.steps(1, 7))     // cible qui n'existe pas
    }

    // ── Atteindre un niveau ──────────────────────────────────────────────────

    @Test
    fun `un cran suffit meme si la voiture annonce le niveau avec retard`() {
        val voiture = Voiture(depart = 1, retard = 1_000)
        assertTrue(voiture.atteindre(2))
        assertEquals("crans envoyés", 1, voiture.crans.size)
        assertEquals(2, voiture.niveau)
    }

    @Test
    fun `descendre d un niveau fait le tour sans depasser`() {
        val voiture = Voiture(depart = 2, retard = 1_000)
        assertTrue(voiture.atteindre(1))
        assertEquals("crans envoyés", 3, voiture.crans.size)
        assertEquals(1, voiture.niveau)
    }

    @Test
    fun `les crans sont espaces`() {
        // Trop rapprochés, la voiture pourrait en ignorer un.
        val voiture = Voiture(depart = 0, retard = 1_000)
        voiture.atteindre(3)
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
        val voiture = Voiture(depart = 1, retard = 0, sourde = true)
        assertFalse(voiture.atteindre(2))
        assertEquals("crans envoyés", 1, voiture.crans.size)
    }
}
