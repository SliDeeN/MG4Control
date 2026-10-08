package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réglages de l'onglet Statistiques : choix de la batterie et filtre des petits trajets.
 *
 * La MG4 n'existe qu'en trois batteries. Le réglage libre d'avant laissait 62 kWh à qui roule en
 * 51 kWh, et toutes ses recharges sortaient surestimées de 20 %.
 */
class StatsSettingsTest {

    private fun trip(integre: Float?, odometre: Int = 0) = Trip(
        startMs = 0L, endMs = 600_000L, distanceKm = odometre, energyKwh = 0.2f,
        climateKwh = null, accessoriesKwh = null, regenKwh = null,
        socStart = null, socEnd = null, outsideTempC = null, integratedKm = integre,
    )

    @Test
    fun `par defaut la batterie est la 64 kWh et sa capacite utile`() {
        val s = StatsSettings()
        assertEquals(StatsSettings.Battery.KWH_64, s.battery)
        assertEquals(61.7f, s.capacityKwh, 0.001f)
    }

    @Test
    fun `les trois batteries portent leur capacite utile`() {
        assertEquals(50.8f, StatsSettings.Battery.KWH_51.usableKwh, 0.001f)
        assertEquals(61.7f, StatsSettings.Battery.KWH_64.usableKwh, 0.001f)
        assertEquals(74.4f, StatsSettings.Battery.KWH_77.usableKwh, 0.001f)
    }

    @Test
    fun `une capacite saisie autrefois rejoint la batterie la plus proche`() {
        // L'ancien réglage était un nombre libre : 62 par défaut, ou la valeur nominale tapée.
        assertEquals(StatsSettings.Battery.KWH_64, StatsSettings.Battery.nearest(62f))
        assertEquals(StatsSettings.Battery.KWH_51, StatsSettings.Battery.nearest(51f))
        assertEquals(StatsSettings.Battery.KWH_51, StatsSettings.Battery.nearest(50.8f))
        assertEquals(StatsSettings.Battery.KWH_77, StatsSettings.Battery.nearest(77f))
        assertEquals(StatsSettings.Battery.KWH_64, StatsSettings.Battery.nearest(58f))
        assertEquals(StatsSettings.Battery.KWH_77, StatsSettings.Battery.nearest(70f))
    }

    @Test
    fun `une capacite hors gamme rejoint l'extreme le plus proche`() {
        assertEquals(StatsSettings.Battery.KWH_51, StatsSettings.Battery.nearest(20f))
        assertEquals(StatsSettings.Battery.KWH_77, StatsSettings.Battery.nearest(120f))
    }

    @Test
    fun `la batterie des reglages suit leur capacite`() {
        assertEquals(StatsSettings.Battery.KWH_51, StatsSettings(capacityKwh = 50.8f).battery)
        assertEquals(StatsSettings.Battery.KWH_77, StatsSettings(capacityKwh = 74.4f).battery)
    }

    @Test
    fun `sans filtre tous les trajets sont enregistres`() {
        val s = StatsSettings()
        assertFalse(s.skipShortTrips)
        assertTrue(s.records(trip(integre = 0.2f)))
    }

    @Test
    fun `le filtre ecarte les trajets sous la distance minimale`() {
        val s = StatsSettings(skipShortTrips = true, minTripKm = 1f)
        assertFalse(s.records(trip(integre = 0.8f)))
        assertTrue("le seuil lui-même est gardé", s.records(trip(integre = 1.0f)))
        assertTrue(s.records(trip(integre = 7.3f, odometre = 7)))
    }

    @Test
    fun `le filtre juge la distance retenue pour l'affichage`() {
        // Sans distance intégrée il ne reste que l'odomètre, au kilomètre entier.
        val s = StatsSettings(skipShortTrips = true, minTripKm = 2.5f)
        assertFalse(s.records(trip(integre = null, odometre = 2)))
        assertTrue(s.records(trip(integre = null, odometre = 3)))
    }

    @Test
    fun `la distance minimale reste dans des bornes raisonnables`() {
        assertEquals(0.1f, StatsSettings.clampMinTrip(0f), 0.0001f)
        assertEquals(2.5f, StatsSettings.clampMinTrip(2.5f), 0.0001f)
        assertEquals(50f, StatsSettings.clampMinTrip(400f), 0.0001f)
    }
}
