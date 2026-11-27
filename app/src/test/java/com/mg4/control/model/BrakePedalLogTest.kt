package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Journal de la pédale de frein (issue #278) : changements bruts, résumé par appui, bilan. */
class BrakePedalLogTest {

    private fun r(t: Long, p: Int?, kmh: Float? = 50f, accel: Float? = null) = BrakePedalLog.Releve(t, p, kmh, accel)

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
    fun `a press is summed up on release with its peak, length and measured deceleration`() {
        val log = BrakePedalLog(brutsMax = 0)
        log.add(r(0, 0, 60f, -0.1f))
        log.add(r(200, 10, 60f, -0.8f))
        log.add(r(600, 35, 52f, -2.1f))
        log.add(r(1000, 20, 45f, -1.4f))
        val lignes = log.add(r(1200, 0, 44f, -0.2f))
        assertEquals(1, lignes.size)
        val l = lignes[0]
        assertTrue(l, l.contains("appui de 1000 ms"))
        assertTrue(l, l.contains("pression maxi 35"))
        assertTrue(l, l.contains("60 km/h → 44 km/h"))
        assertTrue(l, l.contains("accél. mesurée mini -2.10"))
    }

    @Test
    fun `the summary says when nothing was ever readable`() {
        val log = BrakePedalLog()
        log.add(r(0, null))
        log.add(r(200, null))
        val b = log.bilan()
        assertTrue(b, b.contains("2 relevés, 0 lisibles"))
        assertTrue(b, b.contains("pression illisible…illisible"))
        assertTrue(b, b.contains("accél. mesurée illisible…illisible"))
    }

    @Test
    fun `the summary counts presses and the measured range`() {
        val log = BrakePedalLog()
        log.add(r(0, 0, accel = 0.4f))
        log.add(r(200, 12, accel = -1.6f))
        log.add(r(400, 0, accel = -0.1f))
        val b = log.bilan()
        assertTrue(b, b.contains("pression 0…12"))
        assertTrue(b, b.contains("1 appui(s)"))
        assertTrue(b, b.contains("accél. mesurée -1.60…0.40"))
    }
}
