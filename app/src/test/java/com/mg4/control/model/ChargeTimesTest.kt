package com.mg4.control.model

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Placement des heures saisies pour une charge.
 *
 * Fuseau figé sur Paris : le changement d'heure fait partie du problème, et un test qui dépendrait
 * du fuseau de la machine qui le lance ne prouverait rien.
 */
class ChargeTimesTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")

    private fun t(an: Int, mois: Int, jour: Int, h: Int, min: Int): Long =
        Calendar.getInstance(paris).apply {
            clear()
            set(an, mois - 1, jour, h, min, 0)
        }.timeInMillis

    @Test
    fun `regression du 2026-09-21 - un releve de debut date de 2019 ne deplace plus la session`() {
        // Le relevé de début a été pris par un boîtier réveillé avec son horloge d'usine.
        val (debut, fin) = ChargeTimes.resolve(
            "22:00", "00:25",
            startMs = t(2019, 1, 1, 0, 5), endMs = t(2026, 9, 21, 7, 30), tz = paris,
        )
        assertEquals(t(2026, 9, 20, 22, 0), debut)
        assertEquals(t(2026, 9, 21, 0, 25), fin)
    }

    @Test
    fun `une charge de nuit enjambe minuit`() {
        val (debut, fin) = ChargeTimes.resolve(
            "22:30", "02:10", t(2026, 9, 20, 19, 50), t(2026, 9, 21, 7, 30), paris,
        )
        assertEquals(t(2026, 9, 20, 22, 30), debut)
        assertEquals(t(2026, 9, 21, 2, 10), fin)
    }

    @Test
    fun `une charge finie avant minuit reste la veille`() {
        val (debut, fin) = ChargeTimes.resolve(
            "20:15", "23:40", t(2026, 9, 20, 19, 50), t(2026, 9, 21, 7, 30), paris,
        )
        assertEquals(t(2026, 9, 20, 20, 15), debut)
        assertEquals(t(2026, 9, 20, 23, 40), fin)
    }

    @Test
    fun `le debut seul se cale sur son propre releve`() {
        val (debut, fin) = ChargeTimes.resolve(
            "22:00", "", t(2026, 9, 20, 19, 50), t(2026, 9, 21, 7, 30), paris,
        )
        assertEquals(t(2026, 9, 20, 22, 0), debut)
        assertNull(fin)
    }

    @Test
    fun `sans aucun releve bien date rien n'est place`() {
        val (debut, fin) = ChargeTimes.resolve(
            "22:00", "00:25", t(2019, 1, 1, 0, 5), t(2019, 1, 1, 0, 9), paris,
        )
        assertNull(debut)
        assertNull(fin)
    }

    @Test
    fun `les formats courants sont lus`() {
        assertEquals(22 to 30, ChargeTimes.parse("22:30"))
        assertEquals(22 to 30, ChargeTimes.parse("22h30"))
        assertEquals(7 to 5, ChargeTimes.parse(" 7:05 "))
        assertNull(ChargeTimes.parse("25:00"))
        assertNull(ChargeTimes.parse("midi"))
    }

    @Test
    fun `l'heure affichee est sur vingt-quatre heures`() {
        assertEquals("22:05", ChargeTimes.hhmm(t(2026, 9, 20, 22, 5), paris))
    }
}
