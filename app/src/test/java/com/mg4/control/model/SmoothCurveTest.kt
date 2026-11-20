package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Courbe arrondie du module « Graphique » : aucun dépassement au-delà des points (2026-11-20). */
class SmoothCurveTest {

    @Test
    fun `a sudden jump is rounded without overshooting the points`() {
        // Consommation stable, saut brusque, puis chute : la courbe reste entre min et max.
        val ys = listOf(15f, 15.5f, 15f, 46f, 44f, 12f, 13f, 12.5f)
        val xs = ys.indices.map { it * 10f }
        val c = SmoothCurve.controls(xs, ys)
        assertEquals(ys.size - 1, c.size)
        c.forEachIndexed { k, p ->
            val lo = minOf(ys[k], ys[k + 1]) - 1e-3f
            val hi = maxOf(ys[k], ys[k + 1]) + 1e-3f
            assertTrue("segment $k : ${p[1]}", p[1] in lo..hi)
            assertTrue("segment $k : ${p[3]}", p[3] in lo..hi)
            // Les points de contrôle restent dans leur segment, dans l'ordre.
            assertTrue(p[0] > xs[k] && p[2] < xs[k + 1] && p[0] <= p[2])
        }
    }

    @Test
    fun `a peak gets a flat tangent and a flat run stays flat`() {
        val c = SmoothCurve.controls(listOf(0f, 1f, 2f, 3f), listOf(10f, 20f, 10f, 10f))
        // Sommet en x = 1 : tangente nulle, les deux contrôles voisins sont à sa hauteur.
        assertEquals(20f, c[0][3], 1e-4f)
        assertEquals(20f, c[1][1], 1e-4f)
        // Segment plat : contrôles à 10.
        assertEquals(10f, c[2][1], 1e-4f)
        assertEquals(10f, c[2][3], 1e-4f)
        assertTrue(SmoothCurve.controls(listOf(0f), listOf(1f)).isEmpty())
    }
}
