package com.mg4.control.model

import kotlin.math.sqrt

/**
 * Courbe arrondie du module « Graphique » (2026-11-20) : interpolation cubique monotone
 * (Fritsch–Carlson). Les angles sont arrondis, mais entre deux points la courbe ne dépasse jamais
 * leurs valeurs — une montée ou une chute brusque ne fait ni pointe ni rebond au-delà du minimum
 * ou du maximum, contrairement à une spline ordinaire. Kotlin pur.
 */
object SmoothCurve {

    /**
     * Points de contrôle de Bézier de chaque segment entre [xs] et [ys] (même taille, x croissants) :
     * pour le segment k, `[c1x, c1y, c2x, c2y]`. Vide sous deux points.
     */
    fun controls(xs: List<Float>, ys: List<Float>): List<FloatArray> {
        val n = xs.size
        if (n < 2 || ys.size != n) return emptyList()
        val d = FloatArray(n - 1) { k -> (ys[k + 1] - ys[k]) / (xs[k + 1] - xs[k]) }
        val m = FloatArray(n) { k ->
            when {
                k == 0 -> d[0]
                k == n - 1 -> d[n - 2]
                d[k - 1] * d[k] <= 0f -> 0f   // sommet ou creux : tangente plate, pas de rebond
                else -> (d[k - 1] + d[k]) / 2f
            }
        }
        for (k in 0 until n - 1) {
            if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
            val a = m[k] / d[k]
            val b = m[k + 1] / d[k]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[k] = t * a * d[k]
                m[k + 1] = t * b * d[k]
            }
        }
        return (0 until n - 1).map { k ->
            val h = xs[k + 1] - xs[k]
            floatArrayOf(xs[k] + h / 3f, ys[k] + m[k] * h / 3f, xs[k + 1] - h / 3f, ys[k + 1] - m[k + 1] * h / 3f)
        }
    }
}
