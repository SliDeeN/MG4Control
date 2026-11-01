package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Écran conducteur en miles (2026-10-23) : libellés, valeurs et moyenne dans l'unité choisie. */
class ClusterDistanceTest {

    private val fr = Locale.FRANCE

    @Test fun `les libelles suivent la distance`() {
        assertEquals("Wh/km", ConsumptionUnit.WH_PER_KM.labelFor(DistanceUnit.KM))
        assertEquals("Wh/mi", ConsumptionUnit.WH_PER_KM.labelFor(DistanceUnit.MILES))
        assertEquals("kWh/100 mi", ConsumptionUnit.KWH_PER_100KM.labelFor(DistanceUnit.MILES))
        assertEquals("mi/kWh", AverageUnit.KM_PER_KWH.labelFor(DistanceUnit.MILES))
        assertEquals("km/kWh", AverageUnit.KM_PER_KWH.labelFor(DistanceUnit.KM))
    }

    @Test fun `la jauge passe en Wh par mile`() {
        // 17,2 kW à 100 km/h = 172 Wh/km = 277 Wh/mi.
        val km = ClusterGauge.consumption(17.2f, 100f)
        val mi = ClusterGauge.consumption(17.2f, 100f, DistanceUnit.MILES)
        assertEquals(172f, km.value, 0.01f)
        assertEquals(276.81f, mi.value, 0.01f)
        // Mêmes graduations lues en Wh/mi : l'anneau monte un peu plus pour la même conduite.
        assertEquals(ClusterGauge.consumptionFraction(276.81f), mi.fraction, 0.0001f)
    }

    @Test fun `la moyenne se convertit`() {
        assertEquals("6,1", ClusterGauge.formatAverage(164f, AverageUnit.KM_PER_KWH, fr))
        assertEquals("3,8", ClusterGauge.formatAverage(164f, AverageUnit.KM_PER_KWH, fr, DistanceUnit.MILES))
        assertEquals("264", ClusterGauge.formatAverage(164f, AverageUnit.WH_PER_KM, fr, DistanceUnit.MILES))
        assertEquals("26,4", ClusterGauge.formatAverage(164f, AverageUnit.KWH_PER_100KM, fr, DistanceUnit.MILES))
    }

    @Test fun `km par defaut`() {
        assertEquals(DistanceUnit.KM, DistanceUnit.fromKey(null))
        assertEquals(DistanceUnit.MILES, DistanceUnit.fromKey("mi"))
    }

    @Test fun `auto suit la voiture et retombe sur km`() {
        // Aucun choix (installations d'avant) ou « Auto » : l'unité de la voiture.
        assertEquals(DistanceUnit.MILES, DistanceUnit.resolve(null, DistanceUnit.MILES))
        assertEquals(DistanceUnit.MILES, DistanceUnit.resolve(DistanceUnit.AUTO, DistanceUnit.MILES))
        assertEquals(DistanceUnit.KM, DistanceUnit.resolve(DistanceUnit.AUTO, null))
        // Un choix explicite l'emporte sur la voiture.
        assertEquals(DistanceUnit.KM, DistanceUnit.resolve("km", DistanceUnit.MILES))
        assertEquals(DistanceUnit.MILES, DistanceUnit.resolve("mi", DistanceUnit.KM))
    }

    @Test fun `unite de la voiture 0 km 1 miles`() {
        assertEquals(DistanceUnit.KM, DistanceUnit.fromVehicle(0))
        assertEquals(DistanceUnit.MILES, DistanceUnit.fromVehicle(1))
        assertEquals(null, DistanceUnit.fromVehicle(2))
        assertEquals(null, DistanceUnit.fromVehicle(null))
    }
}
