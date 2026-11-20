package com.mg4.control.model

import com.mg4.control.model.ClusterHistory.Bin
import com.mg4.control.model.ClusterHistory.Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Historique des derniers km pour le module « Graphique » (issue #217). */
class ClusterHistoryTest {

    @Test
    fun `a slice is made every 200 m, with net and regenerated energy`() {
        val a = ClusterHistory.Accumulator()
        // 72 km/h = 20 m/s : 200 m en 10 s, à 18 kW.
        var b: Bin? = null
        repeat(9) { assertNull(a.add(1f, 18f, 72f, 300f, 9f, 64f)) }
        b = a.add(1f, 18f, 72f, 300f, 9f, 64f)
        assertNotNull(b)
        assertEquals(0.2f, b!!.km, 0.001f)
        assertEquals(50f, b.wh, 0.01f)          // 18 kW × 10 s = 50 Wh
        assertEquals(0f, b.regenWh, 0.01f)
        assertEquals(300f, b.alt)
        // En récupération : énergie nette négative, et comptée comme récupérée.
        var r: Bin? = null
        repeat(10) { r = a.add(1f, -36f, 72f, null, null, null) ?: r }
        assertEquals(-100f, r!!.wh, 0.01f)
        assertEquals(100f, r!!.regenWh, 0.01f)
        // Un trou de relevé (plus de 30 s) n'invente rien.
        assertNull(a.add(120f, 18f, 72f, null, null, null))
    }

    private fun bins(n: Int, whKm: (Int) -> Float = { 150f }, kmh: Float = 72f) =
        (0 until n).map { i -> Bin(0.2f, whKm(i) * 0.2f, 0f, 0.2f / kmh * 3600f, alt = i.toFloat(), soc = 80f - i) }

    @Test
    fun `about 100 km are kept`() {
        var h = emptyList<Bin>()
        repeat(600) { h = ClusterHistory.append(h, Bin(0.2f, 30f, 0f, 10f)) }
        val km = h.sumOf { it.km.toDouble() }
        assertTrue("$km", km in 102.0..102.3)
    }

    @Test
    fun `fifty bars over the chosen distance, the latest on the right`() {
        // 50 km : 250 tranches ; la consommation croît avec le rang (la plus récente = 249).
        val h = bins(250, whKm = { it.toFloat() })
        val s = ClusterHistory.series(h, Info.CONSUMPTION, 50)
        assertEquals(50, s.size)
        assertTrue(s.all { it != null })
        assertTrue(s.first()!! < s.last()!!)
        assertEquals(247f, s.last()!!, 0.01f)     // moyenne des 5 dernières tranches (245..249)
        // 20 km : 2 tranches par barre, seulement les 100 dernières.
        val v = ClusterHistory.series(h, Info.CONSUMPTION, 20)
        assertEquals(150.5f, v.first()!!, 0.01f)
        // Historique plus court que la distance : les barres anciennes sont vides.
        val court = ClusterHistory.series(bins(25), Info.CONSUMPTION, 50)
        assertNull(court.first())
        assertNotNull(court.last())
    }

    @Test
    fun `each information is computed its own way`() {
        val h = bins(250, kmh = 90f)
        assertEquals(90f, ClusterHistory.series(h, Info.SPEED, 50).last()!!, 0.1f)
        // 150 Wh/km à 90 km/h = 13,5 kW.
        assertEquals(13.5f, ClusterHistory.series(h, Info.POWER, 50).last()!!, 0.1f)
        assertEquals(247f, ClusterHistory.series(h, Info.ALTITUDE, 50).last()!!, 0.01f)
        assertEquals(80f - 249f, ClusterHistory.series(h, Info.SOC, 50).last()!!, 0.01f)
        assertNull(ClusterHistory.series(h, Info.OUTSIDE_TEMP, 50).last())
        assertEquals(150f, ClusterHistory.average(h, Info.CONSUMPTION, 50)!!, 0.01f)
        assertEquals(Info.CONSUMPTION, Info.fromKey("inconnu"))
    }
}
