package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        integre: Float? = null,
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
        integratedKm = integre,
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
    fun `l'energie nette retranche la regeneration`() {
        val t = Trip(
            startMs = 0L, endMs = 2_040_000L, distanceKm = 34, energyKwh = 5.8f,
            climateKwh = 0f, accessoriesKwh = 0.1f, regenKwh = 1f,
            socStart = 69f, socEnd = 60f, outsideTempC = 25f,
        )
        assertEquals(4.8f, t.netEnergyKwh, 0.01f)
        // Relevé réel du 2026-09-20 : la voiture affichait 14,1 kWh/100 km sur ce trajet, quand le
        // compteur brut donnait 17,1. Notre calcul doit tomber du côté de la voiture.
        assertEquals(14.1f, t.consumptionPer100!!, 0.4f)
    }

    @Test
    fun `sans regeneration connue le net vaut le brut`() {
        assertEquals(20f, trip(kwh = 20f).netEnergyKwh, 0.01f)
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

    @Test
    fun `la distance integree l'emporte quand elle concorde avec l'odometre`() {
        val t = trip(km = 34, integre = 34.9f)
        assertEquals(34.9f, t.distance, 0.01f)
        assertTrue(t.distancePrecise)
    }

    @Test
    fun `une integration en desaccord franc avec l'odometre est ecartee`() {
        // 40 km intégrés contre 34 à l'odomètre : des relevés ont été manqués, ou la vitesse a
        // été mal lue. L'odomètre, lui, ne dérive jamais.
        val t = trip(km = 34, integre = 40f)
        assertEquals(34f, t.distance, 0.01f)
        assertFalse(t.distancePrecise)
    }

    @Test
    fun `sans odometre la distance integree est prise telle quelle`() {
        // Trajet plus court qu'un kilomètre : l'odomètre n'a pas bougé, l'intégration si.
        val t = trip(km = 0, integre = 0.6f)
        assertEquals(0.6f, t.distance, 0.01f)
        assertTrue(t.distancePrecise)
    }

    @Test
    fun `au dixieme de kilometre le plancher des ratios descend a un kilometre`() {
        val t = trip(km = 2, kwh = 0.4f, integre = 2.4f)
        assertEquals("2,4 km mesurés au dixième : le ratio a un sens", 16.7f, t.consumptionPer100!!, 0.1f)
    }

    @Test
    fun `sous un kilometre aucun ratio meme avec l'integration`() {
        assertNull(trip(km = 0, kwh = 0.1f, integre = 0.6f).consumptionPer100)
    }

    @Test
    fun `la consommation suit le net, pas le brut`() {
        val t = Trip(
            startMs = 0L, endMs = 3_600_000L, distanceKm = 100, energyKwh = 20f,
            climateKwh = null, accessoriesKwh = null, regenKwh = 4f,
            socStart = null, socEnd = null, outsideTempC = null,
        )
        assertEquals(16f, t.consumptionPer100!!, 0.01f)
    }
}
