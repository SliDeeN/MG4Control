package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trajet découpé par profil (2026-11-05). */
class TripSegmentsTest {

    private val depart = 1_790_000_000_000L
    private var horloge = depart

    private fun snap(odo: Int, energie: Float, regen: Float, profil: String?) =
        EnergySnapshot(timestampMs = horloge.also { horloge += 60_000L }, socPercent = 80f, odometerKm = odo,
            energySinceStartKwh = energie, regenSinceStartKwh = regen, profileId = profil, profileName = profil)

    private fun seg(p: String?, km: Float, e: Float = km / 6f) = TripSegment(p, p, 60_000L, km, e, null)

    @Test
    fun `un changement de profil coupe le trajet en deux morceaux`() {
        val t = StatsTracker()
        t.onSnapshot(snap(10_000, 0f, 0f, "auto"), ready = true)
        t.onSnapshot(snap(10_042, 7.6f, 0.4f, "auto"), ready = true)
        t.onSnapshot(snap(10_042, 7.6f, 0.4f, "confort"), ready = true)
        t.onSnapshot(snap(10_050, 8.7f, 0.5f, "confort"), ready = true)
        val trip = (t.onSnapshot(snap(10_050, 8.7f, 0.5f, "confort"), ready = false).single()
            as StatsTracker.Event.TripEnded).trip

        val s = trip.segments!!
        assertEquals(listOf("auto", "confort"), s.map { it.profileId })
        assertEquals(42f, s[0].distanceKm, 0.01f)
        assertEquals(7.6f, s[0].energyKwh, 0.01f)
        assertEquals(8f, s[1].distanceKm, 0.01f)
        assertEquals(1.1f, s[1].energyKwh, 0.01f)
        assertEquals(0.1f, s[1].regenKwh!!, 0.01f)
        assertTrue(trip.isMixed)
    }

    @Test
    fun `un seul profil - un morceau, pas mixte`() {
        val t = StatsTracker()
        t.onSnapshot(snap(10_000, 0f, 0f, "paul"), ready = true)
        t.onSnapshot(snap(10_011, 1.7f, 0f, "paul"), ready = true)
        val trip = (t.onSnapshot(snap(10_011, 1.7f, 0f, "paul"), ready = false).single()
            as StatsTracker.Event.TripEnded).trip
        assertEquals(1, trip.segments!!.size)
        assertFalse(trip.isMixed)
    }

    @Test
    fun `petits morceaux fondus, voisins identiques reunis`() {
        // Profil changé à l'arrêt avant de partir (0 km), puis aller-retour rapide sur Sport.
        val f = TripSegments.fusionner(listOf(seg("paul", 0f), seg("auto", 30f), seg("sport", 0.3f), seg("auto", 12f)))
        assertEquals(listOf("auto"), f.map { it.profileId })
        assertEquals(42.3f, f.single().distanceKm, 0.01f)
    }

    @Test
    fun `part d'un profil - distance, energie et duree de ses morceaux`() {
        val trip = Trip(startMs = depart, endMs = depart + 3 * 60_000L, distanceKm = 61, energyKwh = 10.8f,
            climateKwh = 0.5f, accessoriesKwh = 0.2f, regenKwh = null, socStart = 80f, socEnd = 70f, outsideTempC = 10f,
            segments = listOf(seg("paul", 18f, 2.6f), seg("sport", 24f, 5.3f), seg("paul", 19f, 2.9f)))
        val p = trip.portion("paul")!!
        assertEquals(37f, p.distance, 0.01f)
        assertEquals(5.5f, p.energyKwh, 0.01f)
        assertEquals(2 * 60_000L, p.durationMs)
        assertNull(p.climateKwh)
        assertNull(trip.portion("confort"))
    }

    @Test
    fun `trajet d'avant la fonction - aucun morceau`() {
        assertNull(TripSegments.build(null, SegmentStart(null, null, 0L, 0f, null, null, null, null, null), true, false))
    }
}
