package com.mg4.control.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hauteur du soleil au-dessus de l'horizon, calculée sans réseau à partir de la position et de
 * l'heure UTC.
 *
 * Algorithme « basse précision » de l'Astronomical Almanac : environ 0,01° d'erreur entre 1950
 * et 2050, très au-delà de ce qu'exige un réglage de luminosité. Vérifié sur des valeurs de
 * référence (solstices à Paris, équinoxe à l'équateur, hiver austral à Sydney).
 */
object SunPosition {

    /** Hauteur en degrés, négative sous l'horizon. Sans réfraction : le lever officiel est à −0,83°. */
    fun elevationDeg(latDeg: Double, lonDeg: Double, utcMs: Long): Double {
        // Jours écoulés depuis J2000.0 (1er janvier 2000, 12 h TT).
        val n = utcMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
        val longitudeMoyenne = norm360(280.460 + 0.9856474 * n)
        val anomalie = Math.toRadians(norm360(357.528 + 0.9856003 * n))
        val longitudeEcliptique = Math.toRadians(
            longitudeMoyenne + 1.915 * sin(anomalie) + 0.020 * sin(2 * anomalie)
        )
        val obliquite = Math.toRadians(23.439 - 0.0000004 * n)
        val declinaison = asin(sin(obliquite) * sin(longitudeEcliptique))
        val ascensionDroite = atan2(cos(obliquite) * sin(longitudeEcliptique), cos(longitudeEcliptique))
        val tempsSideralH = norm24(18.697374558 + 24.06570982441908 * n)
        val angleHoraire = Math.toRadians(tempsSideralH * 15.0 + lonDeg) - ascensionDroite
        val latitude = Math.toRadians(latDeg)
        return Math.toDegrees(asin(
            sin(latitude) * sin(declinaison) + cos(latitude) * cos(declinaison) * cos(angleHoraire)
        ))
    }

    private fun norm360(x: Double) = ((x % 360.0) + 360.0) % 360.0
    private fun norm24(x: Double) = ((x % 24.0) + 24.0) % 24.0
}
