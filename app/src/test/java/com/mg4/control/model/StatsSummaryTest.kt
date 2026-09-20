package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agrégats d'une période et règles de coût.
 *
 * Deux pièges sont couverts ici : moyenner des moyennes (un trajet de 2 km pèserait autant qu'un
 * trajet de 200), et facturer l'énergie roulée au tarif domicile alors qu'une partie vient d'une
 * borne rapide.
 */
class StatsSummaryTest {

    private val settings = StatsSettings(priceAc = 0.2f, priceDc = 0.5f, capacityKwh = 60f)

    private fun trip(km: Int, kwh: Float, dureeMin: Long = 30, regen: Float = 0f) = Trip(
        startMs = 0L,
        endMs = dureeMin * 60_000L,
        distanceKm = km,
        energyKwh = kwh,
        climateKwh = null,
        accessoriesKwh = null,
        regenKwh = regen,
        socStart = null,
        socEnd = null,
        outsideTempC = null,
    )

    private fun charge(kwh: Float, type: ChargeType, tarif: Float? = null) = ChargeSession(
        startMs = 0L,
        endMs = 3_600_000L,
        type = type,
        socStart = 20f,
        socEnd = 60f,
        energyKwh = kwh,
        measuredPowerKw = null,
        outsideTempC = null,
        tariffOverride = tarif,
    )

    @Test
    fun `la consommation de periode se calcule sur les totaux`() {
        // 2 km à 40 kWh/100 et 200 km à 15 kWh/100 : la moyenne des moyennes dirait 27,5.
        val sum = StatsSummary.of(listOf(trip(2, 0.8f), trip(200, 30f)), emptyList(), settings)
        assertEquals(202, sum.distanceKm)
        assertEquals(30.8f * 100f / 202f, sum.consumptionPer100!!, 0.05f)
    }

    @Test
    fun `sous la distance plancher aucun ratio n'est annonce`() {
        val sum = StatsSummary.of(listOf(trip(2, 0.8f)), emptyList(), settings)
        assertNull("odomètre au km entier : le ratio n'aurait pas de sens", sum.consumptionPer100)
        assertNull(sum.averageSpeedKmh)
        assertNull(sum.costPer100)
    }

    @Test
    fun `le resume compte l'energie NETTE des trajets`() {
        // Le compteur du véhicule est brut : 20 kWh dont 4 rendus par la régénération font 16 nets.
        val sum = StatsSummary.of(listOf(trip(100, 20f, regen = 4f)), emptyList(), settings)
        assertEquals(16f, sum.energyKwh, 0.01f)
        assertEquals(16f, sum.consumptionPer100!!, 0.01f)
        assertEquals("la régénération reste affichée à part", 4f, sum.regenKwh, 0.01f)
    }

    @Test
    fun `le prix moyen suit l'energie reellement rechargee`() {
        val sum = StatsSummary.of(
            emptyList(),
            listOf(charge(30f, ChargeType.AC), charge(10f, ChargeType.DC)),
            settings
        )
        // 30 kWh à 0,20 + 10 kWh à 0,50 = 11 € pour 40 kWh.
        assertEquals(11f, sum.chargeCost, 0.01f)
        assertEquals(0.275f, sum.averagePricePerKwh!!, 0.001f)
        assertEquals(1, sum.acCount)
        assertEquals(1, sum.dcCount)
    }

    @Test
    fun `un tarif corrige tire la moyenne avec lui`() {
        val avant = StatsSummary.of(emptyList(), listOf(charge(10f, ChargeType.DC)), settings)
        val apres = StatsSummary.of(
            emptyList(), listOf(charge(10f, ChargeType.DC, tarif = 0.8f)), settings
        )
        assertTrue(apres.averagePricePerKwh!! > avant.averagePricePerKwh!!)
        assertEquals(8f, apres.chargeCost, 0.01f)
    }

    @Test
    fun `le cout roule suit le prix moyen paye, pas le tarif domicile`() {
        val trips = listOf(trip(100, 20f))
        val charges = listOf(charge(40f, ChargeType.DC))   // tout rechargé en rapide, à 0,50
        val sum = StatsSummary.of(trips, charges, settings)
        assertEquals("20 kWh au prix réellement payé", 10f, sum.drivingCost!!, 0.01f)
    }

    @Test
    fun `sans charge sur la periode on retombe sur le tarif alternatif`() {
        val sum = StatsSummary.of(listOf(trip(100, 20f)), emptyList(), settings)
        assertEquals(4f, sum.drivingCost!!, 0.01f)
        assertNull(sum.averagePricePerKwh)
    }
}
