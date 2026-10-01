package com.mg4.control.model

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Courbe « lumière extérieure → luminosité de l'écran » : quatre points réglables par
 * l'utilisateur, en %, posés sur des éclairements de référence fixes.
 *
 * L'interpolation se fait en log de l'éclairement : l'œil perçoit la lumière en échelle
 * logarithmique, et la plage va de quelques lux (nuit) à cent mille (plein soleil). En dessous
 * du premier point et au-dessus du dernier, la valeur reste celle du point.
 */
data class BrightnessCurve(
    val night: Int,
    val twilight: Int,
    val overcast: Int,
    val sunny: Int,
) {
    fun percentFor(lux: Double): Int {
        val x = log10(lux.coerceAtLeast(1e-3))
        val points = listOf(
            log10(LUX_NIGHT) to night,
            log10(LUX_TWILIGHT) to twilight,
            log10(LUX_OVERCAST) to overcast,
            log10(LUX_SUNNY) to sunny,
        )
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        val i = points.indexOfLast { it.first <= x }
        val (x0, y0) = points[i]
        val (x1, y1) = points[i + 1]
        return (y0 + (y1 - y0) * (x - x0) / (x1 - x0)).roundToInt()
    }

    companion object {
        /** Éclairements de référence des quatre points, en lux. */
        const val LUX_NIGHT = 10.0          // nuit, rue éclairée
        const val LUX_TWILIGHT = 400.0      // coucher du soleil
        const val LUX_OVERCAST = 10_000.0   // jour couvert
        const val LUX_SUNNY = 80_000.0      // plein soleil

        val DEFAULT = BrightnessCurve(night = 15, twilight = 35, overcast = 70, sunny = 100)
    }
}
