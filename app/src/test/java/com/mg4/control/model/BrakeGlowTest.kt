package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Halo de freinage (issue #278) : décélération tirée de la vitesse, seuils 1,3 / 0,7 m/s². */
class BrakeGlowTest {

    /** Relevés toutes les 200 ms, la vitesse perdant [kmhParS] km/h par seconde. */
    private fun rouler(g: BrakeGlow, depuisMs: Long, kmh: Float, kmhParS: Float, n: Int): Pair<Long, Float> {
        var t = depuisMs
        var v = kmh
        repeat(n) {
            g.add(t, v)
            t += 200
            v -= kmhParS * 0.2f
        }
        return t to v
    }

    @Test
    fun `a steady speed never lights it`() {
        val g = BrakeGlow()
        rouler(g, 0, 90f, 0f, 20)
        assertFalse(g.on)
    }

    @Test
    fun `lifting off the pedal stays below the threshold`() {
        val g = BrakeGlow()
        // 3 km/h par seconde ≈ 0,83 m/s².
        rouler(g, 0, 90f, 3f, 20)
        assertFalse(g.on)
    }

    @Test
    fun `a firm braking lights it, then it holds above 0_7`() {
        val g = BrakeGlow()
        val (t, v) = rouler(g, 0, 90f, 8f, 6)
        assertTrue(g.on)
        // Le freinage s'adoucit à ≈ 0,83 m/s² : l'hystérésis le garde allumé.
        rouler(g, t, v, 3f, 6)
        assertTrue(g.on)
    }

    @Test
    fun `it goes out once the car stops slowing`() {
        val g = BrakeGlow()
        val (t, v) = rouler(g, 0, 50f, 8f, 6)
        assertTrue(g.on)
        rouler(g, t, v, 0f, 6)
        assertFalse(g.on)
    }

    @Test
    fun `nothing is decided on less than 400 ms`() {
        val g = BrakeGlow()
        g.add(0, 60f)
        g.add(200, 55f)
        assertFalse(g.on)
        g.add(400, 50f)
        assertTrue(g.on)
    }

    @Test
    fun `a one km_h step of the reading does not light it`() {
        val g = BrakeGlow()
        listOf(50f, 50f, 50f, 49f, 49f, 49f, 48f, 48f, 48f).forEachIndexed { i, v -> g.add(i * 200L, v) }
        assertFalse(g.on)
    }

    @Test
    fun `an unreadable speed or a gap puts it out`() {
        val g = BrakeGlow()
        val (t, _) = rouler(g, 0, 90f, 8f, 6)
        assertTrue(g.on)
        assertFalse(g.add(t, null))
        rouler(g, 0, 90f, 8f, 6)
        assertTrue(g.on)
        // Trou de 2 s : on repart de zéro, éteint.
        assertFalse(g.add(10_000, 40f))
    }

    @Test
    fun `speeding up is not braking`() {
        val g = BrakeGlow()
        rouler(g, 0, 30f, -10f, 10)
        assertFalse(g.on)
    }

    @Test
    fun `intensity defaults to medium`() {
        assertEquals(BrakeGlow.Intensity.MEDIUM, BrakeGlow.Intensity.fromKey(null))
        assertEquals(BrakeGlow.Intensity.STRONG, BrakeGlow.Intensity.fromKey("strong"))
    }
}
