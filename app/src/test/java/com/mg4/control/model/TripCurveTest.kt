package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Courbe d'un trajet : un point par relevé, barres calculées à l'affichage (2026-11-05). */
class TripCurveTest {

    private val t0 = 1_790_000_000_000L

    private fun s(m: Int, wh: Int, sec: Int = 10, alt: Int? = null) = CurveSample(m, wh, sec, alt)

    @Test
    fun `un point par releve, sans derive des arrondis`() {
        var c = TripCurve.advance(TripCurve.start(t0), 0f, 0f, 0f, t0, null, true)
        repeat(30) { i ->
            val k = i + 1
            c = TripCurve.advance(c, k * 0.1234f, k * 0.0177f, 0f, t0 + k * 10_000L, 50f, true)
        }
        val p = TripCurve.decode(c.points)
        assertEquals(30, p.size)
        assertEquals(3702, p.sumOf { it.metres }, 1)
        assertEquals(531, p.sumOf { it.wh }, 1)
        assertEquals(300, p.sumOf { it.seconds })
        assertEquals(50, p.first().altitudeM)
    }

    private fun assertEquals(attendu: Int, vu: Int, tolerance: Int) =
        assertTrue("$vu au lieu de $attendu", kotlin.math.abs(attendu - vu) <= tolerance)

    @Test
    fun `un intervalle sans distance mesuree est laisse de cote`() {
        var c = TripCurve.advance(TripCurve.start(t0), 0f, 0f, 0f, t0, null, true)
        c = TripCurve.advance(c, 0.2f, 0.04f, 0f, t0 + 10_000L, null, true)
        c = TripCurve.advance(c, 0.2f, 0.54f, 0f, t0 + 120_000L, null, false)
        c = TripCurve.advance(c, 0.5f, 0.6f, 0f, t0 + 130_000L, null, true)
        val p = TripCurve.decode(c.points)
        assertEquals(listOf(200, 300), p.map { it.metres })
        assertEquals(listOf(40, 60), p.map { it.wh })
        assertEquals(20, p.sumOf { it.seconds })
    }

    @Test
    fun `energie illisible - releve ignore, rattrape au suivant`() {
        var c = TripCurve.advance(TripCurve.start(t0), 0f, 0f, 0f, t0, null, true)
        c = TripCurve.advance(c, 0.3f, null, null, t0 + 10_000L, null, true)
        c = TripCurve.advance(c, 0.6f, 0.12f, 0f, t0 + 20_000L, null, true)
        assertEquals(CurveSample(600, 120, 20, null), TripCurve.decode(c.points).single())
    }

    @Test
    fun `la recuperation donne un point negatif`() {
        var c = TripCurve.advance(TripCurve.start(t0), 0f, 1f, 0f, t0, null, true)
        c = TripCurve.advance(c, 0.3f, 1.01f, 0.06f, t0 + 10_000L, null, true)
        assertEquals(-50, TripCurve.decode(c.points).single().wh)
    }

    @Test
    fun `largeur des barres selon la longueur du trajet`() {
        assertEquals(0.2f, TripCurve.step(3f), 0f)
        assertEquals(0.25f, TripCurve.step(30f), 0f)
        assertEquals(1f, TripCurve.step(100f), 0f)
        assertEquals(2.5f, TripCurve.step(300f), 0f)
    }

    @Test
    fun `trajet court - barres de 200 m`() {
        val b = TripCurve.bars(List(12) { s(250, 50) })!!
        assertEquals(0.2f, b.stepKm, 0f)
        assertEquals(15, b.bars.size)
        b.bars.forEach {
            assertEquals(20f, it.kwhPer100, 0.01f)
            assertEquals(90f, it.speedKmh!!, 0.01f)
        }
    }

    @Test
    fun `un releve a cheval sur deux barres est partage`() {
        val b = TripCurve.bars(listOf(s(300, 60), s(300, 60)))!!
        assertEquals(3, b.bars.size)
        b.bars.forEach { assertEquals(20f, it.kwhPer100, 0.01f) }
    }

    @Test
    fun `un arret charge la barre ou se trouve la voiture`() {
        val b = TripCurve.bars(listOf(s(200, 40), s(0, 20, 60), s(200, 40)))!!
        assertEquals(30f, b.bars[1].kwhPer100, 0.01f)
        assertEquals(20f, b.bars[0].kwhPer100, 0.01f)
    }

    @Test
    fun `derniere barre trop courte ecartee`() {
        val b = TripCurve.bars(listOf(s(1050, 210)))!!
        assertEquals(5, b.bars.size)
        assertEquals(1f, b.totalKm, 0.001f)
    }

    @Test
    fun `ancien format - un point tous les 500 m`() {
        val p = TripCurve.decode("100,25,105;100,25,110;")
        assertEquals(listOf(CurveSample(500, 100, 25, 105), CurveSample(500, 100, 25, 110)), p)
        val b = TripCurve.bars(p)!!
        assertEquals(0.5f, b.stepKm, 0f)
        assertEquals(2, b.bars.size)
        assertEquals(20f, b.bars[0].kwhPer100, 0.01f)
    }

    @Test
    fun `altitude interpolee, trou apres un long passage sans GPS`() {
        val b = TripCurve.bars(listOf(s(100, 20, alt = 100), s(200, 40, alt = 130), s(2000, 400), s(100, 20, alt = 200)))!!
        assertEquals(115f, b.bars[0].altitudeM!!, 0.01f)
        assertNull(b.bars[5].altitudeM)
    }

    @Test
    fun `encodage et decodage`() {
        val p = listOf(CurveSample(333, 58, 10, 312), CurveSample(0, -4, 10, null))
        assertEquals(p, TripCurve.decode(TripCurve.encode(p)))
        assertTrue(TripCurve.decode(null).isEmpty())
        assertNull(TripCurve.finish(TripCurve.start(t0)))
        assertNull(TripCurve.bars(emptyList()))
    }

    @Test
    fun `denivele avec seuil - le bruit GPS ne compte pas`() {
        val alts = listOf(100, 102, 99, 101, 120, 140, 138, 141, 110, 112)
        val (plus, moins) = TripCurve.elevation(alts.map { s(100, 0, alt = it) })!!
        assertEquals(40, plus)
        assertEquals(30, moins)
        assertNull(TripCurve.elevation(listOf(s(100, 0, alt = 100), s(100, 0))))
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
        // 41 intervalles de 10 s à 90 km/h : 250 m chacun, 10,25 km.
        assertEquals(41, p.size)
        assertEquals(10250, p.sumOf { it.metres }, 2)
        assertEquals(50, p.first().altitudeM)
        val b = TripCurve.bars(p)!!
        assertEquals(0.2f, b.stepKm, 0f)
        assertEquals(51, b.bars.size)
    }
}
