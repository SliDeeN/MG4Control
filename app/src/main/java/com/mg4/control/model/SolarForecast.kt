package com.mg4.control.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Réponse Open-Meteo pour un point, réduite à ce qu'on lit (requête en `timeformat=unixtime`). */
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
 * Grille de [SIZE] × [SIZE] points espacés d'environ [STEP_KM] : une zone d'environ 60 × 60 km,
 * qu'Open-Meteo sert en une seule requête (coordonnées séparées par des virgules, une réponse par
 * point, dans le même ordre).
 *
 * Le centre est la position arrondie au dixième de degré (~10 km), et les pas ne dépendent que de
 * lui : rien de plus précis ne part sur le réseau.
 */
data class ForecastGrid(
    val latitude: Double,
    val longitude: Double,
    /** Écart entre deux points voisins, en degrés. */
    val stepLatDeg: Double,
    val stepLonDeg: Double,
    /** Points par côté. */
    val size: Int,
) {
    /**
     * Les points (latitude, longitude), ligne par ligne du sud au nord, chacune d'ouest en est :
     * l'ordre de la requête, donc celui des réponses. Arrondis au centième, ce qui efface le bruit
     * du calcul en virgule flottante (`48.639999…`) sans rien déplacer : centre et pas n'ont pas
     * plus de deux décimales.
     */
    fun points(): List<Pair<Double, Double>> {
        val milieu = (size - 1) / 2.0
        return (0 until size).flatMap { i ->
            val lat = arrondi((latitude + (i - milieu) * stepLatDeg).coerceIn(-90.0, 90.0), 2)
            (0 until size).map { j ->
                lat to arrondi(normaliserLon(longitude + (j - milieu) * stepLonDeg), 2)
            }
        }
    }

    companion object {
        const val SIZE = 5
        const val STEP_KM = 15.0
        private const val KM_PAR_DEGRE = 111.195

        /**
         * La grille autour de ([lat], [lon]). Pas arrondis au centième : 0,13° en latitude
         * (14,5 km) ; en longitude, élargi par 1/cos(latitude) pour des mailles à peu près carrées
         * — 0,21° à Paris — et plafonné près des pôles.
         */
        fun around(lat: Double, lon: Double): ForecastGrid {
            val la = arrondi(lat.coerceIn(-90.0, 90.0), 1)
            val cosLat = cos(Math.toRadians(la)).coerceAtLeast(0.1)
            return ForecastGrid(
                latitude = la,
                longitude = arrondi(normaliserLon(lon), 1),
                stepLatDeg = arrondi(STEP_KM / KM_PAR_DEGRE, 2),
                stepLonDeg = arrondi(STEP_KM / (KM_PAR_DEGRE * cosLat), 2),
                size = SIZE,
            )
        }

        private fun arrondi(x: Double, decimales: Int): Double {
            val f = 10.0.pow(decimales)
            return (x * f).roundToLong() / f
        }

        /** Longitude, ou écart de longitudes, ramené dans [-180, 180[ : une grille peut chevaucher l'antiméridien. */
        internal fun normaliserLon(x: Double): Double = when {
            x >= 180.0 -> x - 360.0
            x < -180.0 -> x + 360.0
            else -> x
        }
    }
}

/**
 * Prévision d'ensoleillement mise en cache, sur toute la [grid]. Une requête couvre trois jours :
 * les passages en READY suivants n'ont plus besoin du réseau, qui n'est d'ailleurs pas toujours
 * monté à ce moment. Et en roulant, chaque calcul prend la météo de l'endroit où l'on est : à
 * 20 km du départ, le ciel peut être tout autre.
 */
data class SolarForecast(
    val grid: ForecastGrid,
    val fetchedAtMs: Long,
    /** Heures communes à tous les points, en secondes UTC. */
    val hoursS: List<Long>,
    /** Rayonnement de chaque point, dans l'ordre de [ForecastGrid.points]. */
    val ghi: List<List<Double?>>,
) {
    /**
     * Rayonnement global en ([lat], [lon]) à [utcMs] ; null hors de la zone, hors de la plage
     * couverte, ou si les points voisins n'ont pas de valeur.
     *
     * Dans l'espace : moyenne des quatre points de la grille qui entourent la position, pondérée
     * par leur proximité (interpolation bilinéaire) ; jusqu'à un pas au-delà de la grille, son
     * bord. Dans le temps : chaque valeur étant la moyenne de l'heure qui précède, elle représente
     * l'instant `time − 30 min` ; entre deux, interpolation.
     */
    fun ghiAt(lat: Double, lon: Double, utcMs: Long): Double? {
        val n = grid.size
        val milieu = (n - 1) / 2.0
        val x = (lat - grid.latitude) / grid.stepLatDeg + milieu
        val y = ForecastGrid.normaliserLon(lon - grid.longitude) / grid.stepLonDeg + milieu
        if (x < -1.0 || x > n.toDouble() || y < -1.0 || y > n.toDouble()) return null
        val instant = instant(utcMs) ?: return null
        val xc = x.coerceIn(0.0, n - 1.0)
        val yc = y.coerceIn(0.0, n - 1.0)
        val i0 = floor(xc).toInt()
        val j0 = floor(yc).toInt()
        val i1 = min(i0 + 1, n - 1)
        val j1 = min(j0 + 1, n - 1)
        val u = xc - i0
        val v = yc - j0
        // Un voisin sans valeur est écarté, les autres gardent leur poids relatif.
        var somme = 0.0
        var poids = 0.0
        for ((point, w) in listOf(
            i0 * n + j0 to (1 - u) * (1 - v), i1 * n + j0 to u * (1 - v),
            i0 * n + j1 to (1 - u) * v,       i1 * n + j1 to u * v,
        )) {
            if (w <= 0.0) continue
            val g = valeur(ghi[point], instant) ?: continue
            somme += w * g
            poids += w
        }
        return if (poids > 0.0) somme / poids else null
    }

    /** Vrai si la prévision a une valeur pour cet endroit et cet instant. */
    fun covers(lat: Double, lon: Double, utcMs: Long): Boolean = ghiAt(lat, lon, utcMs) != null

    /** Rayonnement de chaque point à [utcMs] (null : pas de valeur), pour le journal. */
    fun ghiOfPointsAt(utcMs: Long): List<Double?> {
        val instant = instant(utcMs) ?: return ghi.map { null }
        return ghi.map { valeur(it, instant) }
    }

    /** Distance au centre de la grille (approximation plane, largement suffisante à cette échelle). */
    fun distanceKm(lat: Double, lon: Double): Double {
        val dLat = Math.toRadians(lat - grid.latitude)
        val dLon = Math.toRadians(ForecastGrid.normaliserLon(lon - grid.longitude)) *
            cos(Math.toRadians((lat + grid.latitude) / 2))
        return RAYON_TERRE_KM * sqrt(dLat * dLat + dLon * dLon)
    }

    /**
     * Vrai si la prévision relue du cache est entière. Gson n'appelle pas le constructeur : une
     * entrée abîmée laisserait des champs nuls malgré le typage non nul.
     */
    @Suppress("SENSELESS_COMPARISON")
    fun isComplete(): Boolean =
        grid != null && hoursS != null && ghi != null &&
            grid.size >= 1 && grid.stepLatDeg > 0.0 && grid.stepLonDeg > 0.0 &&
            ghi.size == grid.size * grid.size &&
            ghi.all { it != null && it.size == hoursS.size }

    fun toJson(): String = Gson().toJson(this)

    /** Où tombe [utcMs] : l'heure qui le précède et la fraction vers la suivante ; null hors plage. */
    private fun instant(utcMs: Long): Pair<Int, Double>? {
        if (hoursS.size < 2) return null
        val centres = hoursS.map { it * 1000L - DEMI_HEURE_MS }
        if (utcMs < centres.first() || utcMs > centres.last()) return null
        val i = centres.indexOfLast { it <= utcMs }
        if (i == centres.lastIndex) return i to 0.0
        return i to (utcMs - centres[i]).toDouble() / (centres[i + 1] - centres[i])
    }

    private fun valeur(serie: List<Double?>, instant: Pair<Int, Double>): Double? {
        val (i, f) = instant
        val a = serie.getOrNull(i) ?: return null
        if (f == 0.0) return a
        val b = serie.getOrNull(i + 1) ?: return null
        return a + (b - a) * f
    }

    companion object {
        /**
         * Au-delà de cette distance du centre, la voiture sort de la zone : la grille est
         * retéléchargée autour d'elle. En attendant, son bord sert encore, jusqu'à un pas au-delà.
         */
        const val MAX_DISTANCE_KM = 30.0
        private const val DEMI_HEURE_MS = 1_800_000L
        private const val RAYON_TERRE_KM = 6371.0

        /**
         * Prévision tirée des réponses, une par point dans l'ordre de [ForecastGrid.points] ; null
         * s'il en manque une, ou si elles n'ont pas toutes les mêmes heures.
         */
        fun fromResponses(responses: List<OpenMeteoResponse?>?, grid: ForecastGrid, fetchedAtMs: Long): SolarForecast? {
            if (responses == null || responses.size != grid.size * grid.size) return null
            val heures = responses.firstOrNull()?.hourly?.time ?: return null
            if (heures.size < 2) return null
            val series = responses.map { r ->
                val h = r?.hourly ?: return null
                val valeurs = h.ghi ?: return null
                if (h.time != heures || valeurs.size != heures.size) return null
                valeurs
            }
            return SolarForecast(grid, fetchedAtMs, heures, series)
        }

        /** Relit le cache ; null s'il est illisible, incomplet, ou de l'ancien format à un seul point. */
        fun fromJson(json: String): SolarForecast? =
            runCatching { Gson().fromJson(json, SolarForecast::class.java) }.getOrNull()
                ?.takeIf { it.isComplete() }
    }
}
