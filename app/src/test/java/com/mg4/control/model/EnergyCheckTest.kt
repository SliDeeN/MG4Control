package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comparaison de l'énergie intégrée et de celle des compteurs, trajet par trajet (issue #117). */
class EnergyCheckTest {

    private fun trajet(brut: Float = 1.4f, recup: Float? = 0.5f, debut: Float? = 79.9f, fin: Float? = 78.5f) = Trip(
        startMs = 0L, endMs = 13 * 60_000L, distanceKm = 7,
        energyKwh = brut, climateKwh = 0f, accessoriesKwh = 0.1f, regenKwh = recup,
        socStart = debut, socEnd = fin, outsideTempC = 16.5f, integratedKm = 7.3f,
    )

    private fun integre(brut: Float = 1.31f, recup: Float = 0.42f, releves: Int = 780) =
        PowerIntegrator.Result(brut, recup, releves, 0, 0, 13 * 60_000L)

    @Test
    fun `ecart du net integre au net des compteurs`() {
        val c = EnergyCheck(trajet(), integre(), capacityKwh = 62f)
        assertEquals(0.9f, c.counterNetKwh, 0.0001f)
        assertEquals(-0.01f, c.deltaKwh!!, 0.0001f)
    }

    @Test
    fun `sans mesure integree pas d ecart`() {
        assertNull(EnergyCheck(trajet(), integre(0f, 0f, releves = 0), 62f).deltaKwh)
    }

    @Test
    fun `sans recuperation relevee le net des compteurs vaut le brut`() {
        assertEquals(1.4f, EnergyCheck(trajet(recup = null), integre(), 62f).counterNetKwh, 0.0001f)
    }

    @Test
    fun `energie deduite du pourcentage de batterie`() {
        assertEquals(0.868f, EnergyCheck(trajet(), integre(), 62f).socKwh!!, 0.001f)
        assertNull(EnergyCheck(trajet(debut = null), integre(), 62f).socKwh)
    }

    @Test
    fun `part du trajet couverte par l integration`() {
        val moitie = PowerIntegrator.Result(0.6f, 0.2f, 390, 0, 1, 13 * 30_000L)
        assertEquals(50, EnergyCheck(trajet(), moitie, 62f).coveragePercent)
    }

    @Test
    fun `la ligne porte les trois estimations`() {
        val ligne = EnergyCheck(trajet(), integre(), 62f).line()
        assertTrue(ligne, ligne.contains("7.3 km"))
        assertTrue(ligne, ligne.contains("net 0.89 kWh"))
        assertTrue(ligne, ligne.contains("net 0.90 kWh"))
        assertTrue(ligne, ligne.contains("écart -0.01 kWh"))
        assertTrue(ligne, ligne.contains("≈ 0.87 kWh"))
    }

    @Test
    fun `la ligne dit quand rien n a ete integre`() {
        val ligne = EnergyCheck(trajet(), integre(0f, 0f, releves = 0), 62f).line()
        assertTrue(ligne, ligne.contains("aucune mesure"))
    }
}
