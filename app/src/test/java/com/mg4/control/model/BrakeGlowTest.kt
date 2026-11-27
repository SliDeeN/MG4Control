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
    fun `the slightest pedal press lights it, even standing still`() {
        val g = BrakeGlow()
        assertFalse(g.add(0, 0f, 0))
        // Un pas de 75 (le plus petit relevé) suffit, comme les feux stop au moindre effleurement.
        assertTrue(g.add(200, 0f, 75))
        assertTrue(g.parPedale)
        assertTrue(g.add(400, 0f, 750))
        assertFalse(g.add(600, 0f, 0))
    }

    @Test
    fun `strong regeneration without the pedal still lights it`() {
        val g = BrakeGlow()
        var t = 0L
        var v = 90f
        repeat(6) { g.add(t, v, 0); t += 200; v -= 1.6f }
        assertTrue(g.on)
        assertTrue(g.parDeceleration)
        assertFalse(g.parPedale)
    }

    @Test
    fun `an unreadable pedal falls back to deceleration`() {
        val g = BrakeGlow()
        assertFalse(g.add(0, 50f, null))
        assertFalse(g.add(200, 50f, null))
        assertFalse(g.add(400, 50f, null))
        assertTrue(g.add(600, 45f, null))
    }

    @Test
    fun `an unreadable speed keeps the pedal working`() {
        val g = BrakeGlow()
        assertTrue(g.add(0, null, 4000))
        assertFalse(g.add(200, null, 0))
    }

    // Capteur d'accélération de la voiture (2026-11-27) : prioritaire sur la vitesse, moyenné sur 3 relevés.
    @Test
    fun `the measured deceleration lights it above 1_3 and holds it above 0_7`() {
        val g = BrakeGlow()
        assertFalse(g.add(0, 40f, 0, -0.5f))
        assertFalse(g.add(200, 40f, 0, -1.5f))
        // (−0,5 − 1,5 − 1,6) / 3 = −1,2 : pas encore.
        assertFalse(g.add(400, 40f, 0, -1.6f))
        assertTrue(g.add(600, 39f, 0, -1.6f))
        assertTrue(g.add(800, 38f, 0, -0.9f))
        assertTrue(g.add(1000, 37f, 0, -0.8f))
        assertFalse(g.add(1200, 36f, 0, -0.2f))
    }

    @Test
    fun `a single jolt of the sensor does not light it`() {
        val g = BrakeGlow()
        assertFalse(g.add(0, 30f, 0, -0.3f))
        assertFalse(g.add(200, 30f, 0, -2.4f))
        assertFalse(g.add(400, 30f, 0, -0.3f))
    }

    @Test
    fun `the sensor wins over the speed`() {
        val g = BrakeGlow()
        // La vitesse chute fort, mais le capteur dit à peine −0,3 : rien.
        assertFalse(g.add(0, 60f, 0, -0.3f))
        assertFalse(g.add(200, 55f, 0, -0.3f))
        assertFalse(g.add(400, 50f, 0, -0.3f))
        assertFalse(g.add(600, 45f, 0, -0.3f))
        assertEquals(0.3f, g.deceleration!!, 0.01f)
    }

    @Test
    fun `intensity defaults to medium`() {
        assertEquals(BrakeGlow.Intensity.MEDIUM, BrakeGlow.Intensity.fromKey(null))
        assertEquals(BrakeGlow.Intensity.STRONG, BrakeGlow.Intensity.fromKey("strong"))
    }
}
