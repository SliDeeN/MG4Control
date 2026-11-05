package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Distance intégrée à la seconde (2026-11-05), et son usage par le suivi des trajets. */
class SpeedIntegratorTest {

    @Test
    fun `trapezes a la seconde`() {
        val i = SpeedIntegrator()
        // 0 → 36 km/h en 10 s, puis 10 s à 36 km/h : 50 m + 100 m.
        for (s in 0..10) i.add(s * 1000L, s * 3.6f)
        for (s in 11..20) i.add(s * 1000L, 36f)
        assertEquals(0.15f, i.result().km, 0.0005f)
        assertEquals(21, i.result().samples)
    }

    @Test
    fun `trou et vitesse illisible`() {
        val i = SpeedIntegrator()
        i.add(0L, 36f)
        i.add(1000L, null)
        i.add(10_000L, 36f)      // 10 s sans relevé : trou, rien n'est inventé
        i.add(11_000L, 36f)
        assertEquals(0.01f, i.result().km, 0.0001f)
        assertEquals(1, i.result().gaps)
        i.reset()
        assertEquals(0f, i.result().km, 0f)
    }

    private val t0 = 1_790_000_000_000L

    @Test
    fun `le suivi prend la distance a la seconde et garde l'ancienne en controle`() {
        val t = StatsTracker()
        var h = t0
        fun snap(kmh: Float, fine: Float?) = EnergySnapshot(timestampMs = h.also { h += 10_000L }, socPercent = 80f,
            odometerKm = 1000, speedKmh = kmh, tripDistanceKm = fine)
        t.onSnapshot(snap(0f, 0f), ready = true)
        // Arrêt aux deux relevés, mais la voiture a roulé 60 m entre les deux (lu à la seconde).
        t.onSnapshot(snap(0f, 0.06f), ready = true)
        t.onSnapshot(snap(36f, 0.12f), ready = true)
        // Valeur manquée : repli sur la méthode à 10 s (36 km/h × 10 s = 100 m).
        t.onSnapshot(snap(36f, null), ready = true)
        t.onSnapshot(snap(36f, 0.32f), ready = true)          // repli encore : pas de valeur précédente
        t.onSnapshot(snap(36f, 0.42f), ready = true)
        val trip = (t.onSnapshot(snap(36f, 0.52f), ready = false).single() as StatsTracker.Event.TripEnded).trip
        // 0,06 + 0,06 + 0,1 (repli) + 0,1 (repli) + 0,1 + 0,1
        assertEquals(0.5f, trip.integratedKm!!, 0.001f)
        // Ancienne méthode : 0 + 0,05 + 0,1 × 4
        assertEquals(0.5f, trip.distanceCheckKm!!, 0.051f)
    }

    @Test
    fun `ecart impossible - repli sur la methode a 10 s`() {
        val t = StatsTracker()
        var h = t0
        fun snap(kmh: Float, fine: Float?) = EnergySnapshot(timestampMs = h.also { h += 10_000L }, socPercent = 80f,
            odometerKm = 1000, speedKmh = kmh, tripDistanceKm = fine)
        t.onSnapshot(snap(36f, 5f), ready = true)
        t.onSnapshot(snap(36f, 0.1f), ready = true)           // intégration repartie de zéro
        t.onSnapshot(snap(36f, 3f), ready = true)             // 2,9 km en 10 s : impossible
        val trip = (t.onSnapshot(snap(36f, 3.1f), ready = false).single() as StatsTracker.Event.TripEnded).trip
        assertEquals(0.3f, trip.integratedKm!!, 0.001f)
    }

    @Test
    fun `trajet sans distance a la seconde - methode a 10 s seule`() {
        val t = StatsTracker()
        var h = t0
        fun snap() = EnergySnapshot(timestampMs = h.also { h += 10_000L }, socPercent = 80f, odometerKm = 1000, speedKmh = 72f)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(), ready = true)
        val trip = (t.onSnapshot(snap(), ready = false).single() as StatsTracker.Event.TripEnded).trip
        assertEquals(0.4f, trip.integratedKm!!, 0.001f)
        assertEquals(0.4f, trip.distanceCheckKm!!, 0.001f)
        assertNull(TripCurve.finish(null))
    }
}
