package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Moyenne du trajet et énergie récupérée des modules du combiné, depuis le passage en READY. */
class ClusterTripTest {

    /** Un relevé par seconde pendant [secondes], à puissance et vitesse constantes. */
    private fun ClusterTrip.rouler(depuisMs: Long, secondes: Int, kw: Float, kmh: Float): Long {
        for (i in 0..secondes) add(depuisMs + i * 1_000L, kw, kmh)
        return depuisMs + secondes * 1_000L
    }

    @Test fun `20 kW a 100 km par h font 200 Wh par km`() {
        val t = ClusterTrip()
        t.rouler(0L, 36, 20f, 100f)   // 1 km, 0,2 kWh
        assertEquals(1f, t.distanceKm, 0.001f)
        assertEquals(200f, t.averageWhPerKm()!!, 0.5f)
    }

    @Test fun `la moyenne compte le net, recuperation deduite`() {
        val t = ClusterTrip()
        val fin = t.rouler(0L, 36, 20f, 100f)          // 0,2 kWh consommés sur 1 km
        t.rouler(fin + 1L, 10, -36f, 100f)             // ~0,1 kWh récupérés sur ~0,28 km
        assertEquals(100f, t.regenWh(), 1f)
        val consomme = 200f
        assertEquals((consomme - t.regenWh()) / t.distanceKm, t.averageWhPerKm()!!, 1f)
    }

    @Test fun `pas de moyenne sur quelques metres`() {
        val t = ClusterTrip()
        t.rouler(0L, 10, 20f, 50f)   // ~140 m
        assertNull(t.averageWhPerKm())
    }

    @Test fun `un trou dans les releves n invente pas de distance`() {
        val t = ClusterTrip()
        t.add(0L, 20f, 100f)
        t.add(60_000L, 20f, 100f)   // une minute sans relevé
        assertEquals(0f, t.distanceKm, 0.0001f)
    }

    @Test fun `une vitesse illisible ne compte pas`() {
        val t = ClusterTrip()
        t.add(0L, 20f, 100f)
        t.add(1_000L, 20f, null)
        t.add(2_000L, 20f, 100f)
        // Le trapèze 0 → 2 s couvre le relevé manqué : 2 s à 100 km/h.
        assertEquals(100f * 2f / 3600f, t.distanceKm, 0.0001f)
    }

    @Test fun `remise a zero au depart`() {
        val t = ClusterTrip()
        t.rouler(0L, 36, 20f, 100f)
        t.reset()
        assertEquals(0f, t.distanceKm, 0f)
        assertEquals(0f, t.regenWh(), 0f)
        assertNull(t.averageWhPerKm())
    }
}
