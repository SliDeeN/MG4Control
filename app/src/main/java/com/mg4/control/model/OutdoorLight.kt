package com.mg4.control.model

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Éclairement extérieur estimé, en lux, à partir de la hauteur du soleil et — quand on l'a — du
 * rayonnement global prévu (W/m²).
 *
 * Pourquoi le rayonnement et pas l'indice UV : l'UV tombe presque à zéro dès que le soleil est
 * bas, alors qu'il fait encore très clair ; le rayonnement global suit la lumière visible, à
 * environ [LUX_PAR_WM2] lux par W/m².
 */
object OutdoorLight {

    /** Efficacité lumineuse du rayonnement solaire global (ciel clair à couvert : 100–120 lm/W). */
    const val LUX_PAR_WM2 = 110.0

    // Repères du crépuscule (ordre de grandeur, ciel dégagé), interpolés en échelle logarithmique.
    private const val LUX_COUCHER = 400.0        // soleil sur l'horizon
    private const val LUX_FIN_CIVIL = 3.0        // soleil à −6° : fin du crépuscule civil
    private const val LUX_FIN_NAUTIQUE = 0.01    // soleil à −12° : nuit, en pratique

    /**
     * [ghiWm2] : rayonnement global prévu, ou null sans prévision — on suppose alors un ciel
     * dégagé. Le choix est délibéré : un écran un peu trop lumineux par temps couvert gêne moins
     * qu'un écran illisible en plein soleil.
     */
    fun estimateLux(elevationDeg: Double, ghiWm2: Double?): Double {
        val crepuscule = twilightLux(elevationDeg)
        if (elevationDeg <= 0.0) return crepuscule
        val ghi = (ghiWm2 ?: clearSkyGhi(elevationDeg)).coerceAtLeast(0.0)
        // Le rayonnement d'une heure qui contient le lever peut être quasi nul alors qu'il fait
        // déjà clair : le crépuscule sert de plancher.
        return maxOf(ghi * LUX_PAR_WM2, crepuscule)
    }

    /** Rayonnement global par ciel dégagé, modèle de Haurwitz (1945), en W/m². */
    fun clearSkyGhi(elevationDeg: Double): Double {
        if (elevationDeg <= 0.0) return 0.0
        val s = sin(Math.toRadians(elevationDeg))
        return 1098.0 * s * exp(-0.057 / s)
    }

    /** Lumière du crépuscule : 400 lx au coucher, 3 lx à −6°, 0,01 lx à −12° et au-delà. */
    fun twilightLux(elevationDeg: Double): Double = when {
        elevationDeg >= 0.0   -> LUX_COUCHER
        elevationDeg >= -6.0  -> interpLog(LUX_COUCHER, LUX_FIN_CIVIL, -elevationDeg / 6.0)
        elevationDeg >= -12.0 -> interpLog(LUX_FIN_CIVIL, LUX_FIN_NAUTIQUE, (-elevationDeg - 6.0) / 6.0)
        else                  -> LUX_FIN_NAUTIQUE
    }

    private fun interpLog(de: Double, vers: Double, f: Double): Double =
        10.0.pow(log10(de) + (log10(vers) - log10(de)) * f)
}
