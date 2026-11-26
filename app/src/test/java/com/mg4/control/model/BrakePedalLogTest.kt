package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sonde de la pédale de frein (issue #278) : ce que le journal retient d'un cycle READY. */
class BrakePedalLogTest {

    private fun r(t: Long, p: Int?, v: Int? = 1, kmh: Float? = 50f) = BrakePedalLog.Releve(t, p, v, kmh)

    @Test
    fun `an unchanged value writes only its first reading`() {
        val log = BrakePedalLog()
        assertEquals(1, log.add(r(0, 0)).size)
        assertTrue(log.add(r(200, 0)).isEmpty())
        assertTrue(log.add(r(400, 0)).isEmpty())
    }

    @Test
    fun `raw changes stop after the cap`() {
        val log = BrakePedalLog(brutsMax = 2, appuisMax = 0)
        assertEquals(1, log.add(r(0, 0)).size)
        assertEquals(1, log.add(r(200, 5)).size)
        assertTrue(log.add(r(400, 9)).isEmpty())
    }

    @Test
    fun `a press is summed up on release with its peak, length and deceleration`() {
        val log = BrakePedalLog(brutsMax = 0)
        log.add(r(0, 0, kmh = 60f))
        log.add(r(200, 10, kmh = 60f))
        log.add(r(600, 35, kmh = 52f))
        log.add(r(1000, 20, kmh = 45f))
        val lignes = log.add(r(1200, 0, kmh = 44f))
        assertEquals(1, lignes.size)
        val l = lignes[0]
        assertTrue(l, l.contains("appui de 1000 ms"))
        assertTrue(l, l.contains("pression maxi 35"))
        assertTrue(l, l.contains("60 km/h → 44 km/h"))
        // 8 km/h en 400 ms ≈ 5,6 m/s².
        assertTrue(l, l.contains("5.6 m/s²"))
    }

    @Test
    fun `the summary says when nothing was ever readable`() {
        val log = BrakePedalLog()
        log.add(r(0, null, null))
        log.add(r(200, null, null))
        val b = log.bilan()
        assertTrue(b, b.contains("2 relevés, 0 lisibles"))
        assertTrue(b, b.contains("pression illisible…illisible"))
        assertTrue(b, b.contains("valide vu jamais"))
    }

    @Test
    fun `the summary counts presses and validity values`() {
        val log = BrakePedalLog()
        log.add(r(0, 0, 0))
        log.add(r(200, 12, 1))
        log.add(r(400, 0, 1))
        val b = log.bilan()
        assertTrue(b, b.contains("pression 0…12"))
        assertTrue(b, b.contains("1 appui(s)"))
        assertTrue(b, b.contains("valide vu 0/1"))
    }

    @Test
    fun `a change of the electric mode is written with its context (2026-11-26)`() {
        val log = BrakePedalLog(brutsMax = 0)
        log.add(BrakePedalLog.Releve(0, 0, 0, 50f, accel = -0.1f, mode = 3))
        val l = log.add(BrakePedalLog.Releve(200, 0, 0, 49f, accel = -1.4f, mode = 7))
        assertEquals(1, l.size)
        assertTrue(l[0], l[0].contains("mode élec. 3→7"))
        assertTrue(l[0], l[0].contains("accél. -1.40"))
    }

    @Test
    fun `a regeneration episode is summed up with its peak measured deceleration`() {
        val log = BrakePedalLog(brutsMax = 0, signauxMax = 0)
        log.add(BrakePedalLog.Releve(0, 0, 0, 60f, accel = -0.2f, mode = 3))
        log.add(BrakePedalLog.Releve(200, 0, 0, 59f, accel = -0.9f, mode = 7))
        log.add(BrakePedalLog.Releve(400, 0, 0, 57f, accel = -1.6f, mode = 7))
        val l = log.add(BrakePedalLog.Releve(1200, 0, 0, 52f, accel = -0.1f, mode = 3))
        assertEquals(1, l.size)
        assertTrue(l[0], l[0].contains("récupération (mode 7) : 1000 ms"))
        assertTrue(l[0], l[0].contains("mini -1.60"))
        assertTrue(l[0], l[0].contains("pédale jamais"))
        val b = log.bilan()
        assertTrue(b, b.contains("modes élec. vus 3/7"))
        assertTrue(b, b.contains("1 récupération(s)"))
        assertTrue(b, b.contains("accél. mesurée -1.60…-0.10"))
    }
}
