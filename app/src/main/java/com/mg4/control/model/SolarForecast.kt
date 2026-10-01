package com.mg4.control.model

import com.google.gson.annotations.SerializedName
import kotlin.math.cos
import kotlin.math.sqrt

/** Réponse Open-Meteo réduite à ce qu'on lit (requête en `timeformat=unixtime`). */
data class OpenMeteoResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hourly: OpenMeteoHourly? = null,
)

data class OpenMeteoHourly(
    /** Heure de chaque valeur, en secondes UTC. */
    val time: List<Long>? = null,
    /** Rayonnement global moyen sur l'heure qui PRÉCÈDE [time], en W/m². */
    @SerializedName("shortwave_radiation") val ghi: List<Double?>? = null,
)

/**
 * Prévision d'ensoleillement mise en cache. Une requête couvre trois jours : les passages en
 * READY suivants n'ont plus besoin du réseau, qui n'est d'ailleurs pas toujours monté à ce moment.
 *
 * [latitude]/[longitude] : la position de la REQUÊTE, déjà arrondie, pas celle de la voiture.
 */
data class SolarForecast(
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMs: Long,
    val hoursS: List<Long>,
    val ghi: List<Double?>,
) {
    /**
     * Rayonnement global à [utcMs], interpolé entre deux heures ; null hors de la plage couverte
     * ou sur un trou des données. Chaque valeur étant la moyenne de l'heure précédente, elle
     * représente l'instant `time − 30 min`.
     */
    fun ghiAt(utcMs: Long): Double? {
        if (hoursS.size < 2 || ghi.size != hoursS.size) return null
        val centres = hoursS.map { it * 1000L - DEMI_HEURE_MS }
        if (utcMs < centres.first() || utcMs > centres.last()) return null
        val i = centres.indexOfLast { it <= utcMs }
        if (i == centres.lastIndex) return ghi[i]
        val a = ghi[i] ?: return null
        val b = ghi[i + 1] ?: return null
        val f = (utcMs - centres[i]).toDouble() / (centres[i + 1] - centres[i])
        return a + (b - a) * f
    }

    /** Vrai si la prévision vaut pour cet endroit (à [MAX_DISTANCE_KM] près) et cet instant. */
    fun covers(lat: Double, lon: Double, utcMs: Long): Boolean =
        distanceKm(lat, lon) <= MAX_DISTANCE_KM && ghiAt(utcMs) != null

    /** Distance au point de la requête (approximation plane, largement suffisante à cette échelle). */
    fun distanceKm(lat: Double, lon: Double): Double {
        val dLat = Math.toRadians(lat - latitude)
        val dLon = Math.toRadians(lon - longitude) * cos(Math.toRadians((lat + latitude) / 2))
        return RAYON_TERRE_KM * sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        /** Au-delà, la météo d'un autre endroit : on redemande. */
        const val MAX_DISTANCE_KM = 30.0
        private const val DEMI_HEURE_MS = 1_800_000L
        private const val RAYON_TERRE_KM = 6371.0

        /** Prévision exploitable tirée de la réponse, ou null si elle est incomplète. */
        fun fromResponse(r: OpenMeteoResponse?, lat: Double, lon: Double, fetchedAtMs: Long): SolarForecast? {
            val heures = r?.hourly?.time ?: return null
            val valeurs = r.hourly.ghi ?: return null
            if (heures.size < 2 || valeurs.size != heures.size) return null
            return SolarForecast(lat, lon, fetchedAtMs, heures, valeurs)
        }
    }
}
