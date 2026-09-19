package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Grandeurs déduites d'un trajet.
 *
 * La voiture ne publie ni l'énergie du moteur ni la consommation moyenne : les deux se calculent,
 * et l'honnêteté de l'affichage tient à ces règles.
 */
class TripTest {

    private fun trip(
        km: Int = 100,
        kwh: Float = 20f,
        clim: Float? = null,
        accessoires: Float? = null,
    ) = Trip(
        startMs = 0L,
        endMs = 3_600_000L,
        distanceKm = km,
        energyKwh = kwh,
        climateKwh = clim,
        accessoriesKwh = accessoires,
        regenKwh = null,
        socStart = null,
        socEnd = null,
        outsideTempC = null,
    )

    @Test
    fun `l'energie moteur est le total moins les postes annexes`() {
        val t = trip(kwh = 15.1f, clim = 0.2f, accessoires = 0.5f)
        assertEquals(14.4f, t.motorKwh!!, 0.01f)
    }

    @Test
    fun `sans aucun poste connu l'energie moteur n'est pas annoncee`() {
        assertNull("sinon on présenterait le total comme une mesure séparée", trip().motorKwh)
    }

    @Test
    fun `un poste seul suffit a deduire l'energie moteur`() {
        assertEquals(19.8f, trip(kwh = 20f, clim = 0.2f).motorKwh!!, 0.01f)
    }

    @Test
    fun `l'energie moteur ne descend jamais sous zero`() {
        // Postes incohérents (lecture partielle) : mieux vaut zéro qu'une valeur négative.
        assertEquals(0f, trip(kwh = 0.4f, clim = 0.5f, accessoires = 0.5f).motorKwh!!, 0.01f)
    }

    @Test
    fun `sous cinq kilometres aucun ratio n'est annonce`() {
        val court = trip(km = 2, kwh = 0.4f)
        assertNull(court.consumptionPer100)
        assertNull(court.averageSpeedKmh)
    }

    @Test
    fun `au dela du plancher la consommation se calcule`() {
        assertEquals(20f, trip(km = 100, kwh = 20f).consumptionPer100!!, 0.01f)
    }
}
