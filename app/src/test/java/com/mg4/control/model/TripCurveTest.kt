package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Courbe d'un trajet, un point tous les 500 m (2026-11-05). */
class TripCurveTest {

    private val t0 = 1_790_000_000_000L

    @Test
    fun `un releve qui franchit deux bornes donne deux points interpoles`() {
        var s = TripCurve.advance(TripCurve.start(t0), 0f, 0f, 0f, t0, 100f, true)
        // 1,2 km en 60 s, 0,24 kWh, altitude 100 → 112 m.
        s = TripCurve.advance(s, 1.2f, 0.24f, 0f, t0 + 60_000L, 112f, true)
        val p = TripCurve.decode(s.points)
        assertEquals(2, p.size)
        assertEquals(100, p[0].wh)
        assertEquals(25, p[0].seconds)
        assertEquals(105, p[0].altitudeM)
        assertEquals(20f, p[0].kwhPer100, 0.01f)
        assertEquals(72f, p[0].speedKmh!!, 0.5f)
        assertEquals(110, p[1].altitudeM)
        // Le tronçon en cours repart de la seconde borne.
        assertEquals(1.0f, s.startKm, 0.001f)
    }

    @Test
    fun `la recuperation donne un troncon negatif`() {
        var s = TripCurve.advance(TripCurve.start(t0), 0f, 1f, 0f, t0, null, true)
        s = TripCurve.advance(s, 0.5f, 1.01f, 0.06f, t0 + 30_000L, null, true)
        val p = TripCurve.decode(s.points).single()
        assertEquals(-50, p.wh)
        assertNull(p.altitudeM)
    }

    @Test
    fun `un intervalle sans distance mesuree ne gonfle pas le troncon`() {
        var s = TripCurve.advance(TripCurve.start(t0), 0f, 0f, 0f, t0, null, true)
        s = TripCurve.advance(s, 0.2f, 0.04f, 0f, t0 + 10_000L, null, true)
        // Trou : 0,5 kWh dépensés sans distance mesurable.
        s = TripCurve.advance(s, 0.2f, 0.54f, 0f, t0 + 120_000L, null, false)
        s = TripCurve.advance(s, 0.5f, 0.6f, 0f, t0 + 130_000L, null, true)
        val p = TripCurve.decode(s.points).single()
        assertEquals(100, p.wh)
        assertEquals(20, p.seconds)
    }

    @Test
    fun `energie illisible - releve ignore, puis rattrape`() {
        var s = TripCurve.advance(TripCurve.start(t0), 0f, 0f, 0f, t0, null, true)
        s = TripCurve.advance(s, 0.3f, null, null, t0 + 10_000L, null, true)
        s = TripCurve.advance(s, 0.6f, 0.12f, 0f, t0 + 20_000L, null, true)
        assertEquals(100, TripCurve.decode(s.points).single().wh)
    }

    @Test
    fun `encodage et decodage`() {
        val p = listOf(CurvePoint(85, 15, 312), CurvePoint(-40, 18, null))
        assertEquals(p, TripCurve.decode(TripCurve.encode(p)))
        assertTrue(TripCurve.decode(null).isEmpty())
        assertNull(TripCurve.finish(TripCurve.start(t0)))
    }

    @Test
    fun `denivele avec seuil - le bruit GPS ne compte pas`() {
        val alts = listOf(100, 102, 99, 101, 120, 140, 138, 141, 110, 112)
        val (plus, moins) = TripCurve.elevation(alts.map { CurvePoint(0, 10, it) })!!
        assertEquals(40, plus)
        assertEquals(30, moins)
        assertNull(TripCurve.elevation(listOf(CurvePoint(0, 10, 100), CurvePoint(0, 10, null))))
    }

    @Test
    fun `le suivi enregistre la courbe du trajet`() {
        val t = StatsTracker()
        var h = t0
        fun snap(odo: Int, kmh: Float, conso: Float) = EnergySnapshot(timestampMs = h.also { h += 10_000L }, socPercent = 80f,
            odometerKm = odo, speedKmh = kmh, tripConsumedKwh = conso, tripRegenKwh = 0f, altitudeM = 50f)
        t.onSnapshot(snap(1000, 90f, 0f), ready = true)
        var e = 0f
        repeat(40) { e += 0.04f; t.onSnapshot(snap(1000 + it / 4, 90f, e), ready = true) }
        val trip = (t.onSnapshot(snap(1010, 90f, e), ready = false).single() as StatsTracker.Event.TripEnded).trip
        val p = TripCurve.decode(trip.curve)
        // 41 intervalles de 10 s à 90 km/h = 10,25 km → 20 tronçons complets.
        assertEquals(20, p.size)
        assertEquals(50, p.first().altitudeM)
        assertTrue(p.all { it.seconds in 19..21 })
    }
}
