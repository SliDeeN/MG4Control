package com.mg4.control.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Courbe d'un trajet (2026-11-05) : un point tous les [STEP_KM] le long de la route, pour le
 * graphique en barres du détail d'un trajet (maquette B, avec vitesse et dénivelé en option).
 *
 * Chaque point porte l'énergie NETTE du tronçon (Wh, négative en récupération), sa durée, et
 * l'altitude GPS à sa fin. On en tire la consommation du tronçon et sa vitesse moyenne. L'axe est en
 * kilomètres et non en minutes : un bouchon ne doit pas écraser la courbe.
 *
 * L'énergie vient de l'intégration de la puissance batterie ([PowerIntegrator], un relevé par
 * seconde) et non des compteurs du véhicule : au dixième de kWh, ils ne voient rien sur 500 m. La
 * distance est celle que le suivi intègre déjà à partir de la vitesse. Les relevés tombant toutes
 * les dix secondes, les bornes des tronçons sont interpolées entre deux relevés.
 *
 * Gardée sous forme de texte compact (`wh,s,alt;…`) : environ 2,5 Ko pour 100 km, réécrits à
 * chaque relevé dans l'état en cours.
 */
data class CurvePoint(
    /** Énergie nette du tronçon en Wh ; négative quand la récupération l'emporte. */
    val wh: Int,
    /** Durée du tronçon en secondes. */
    val seconds: Int,
    /** Altitude GPS à la fin du tronçon, en mètres ; null sans position ou sans altitude. */
    val altitudeM: Int?,
) {
    /** Consommation du tronçon en kWh/100 km. */
    val kwhPer100: Float get() = wh * 100f / (TripCurve.STEP_KM * 1000f)

    /** Vitesse moyenne du tronçon en km/h ; null pour une durée nulle. */
    val speedKmh: Float? get() = if (seconds > 0) TripCurve.STEP_KM * 3600f / seconds else null
}

/**
 * Courbe en cours de construction, gardée dans [PendingTrip] : les points déjà clos et le début du
 * tronçon en cours, plus le dernier relevé (l'autre côté de l'interpolation).
 */
data class CurveState(
    val points: String = "",
    val count: Int = 0,
    /** Début du tronçon en cours : distance intégrée, énergie nette intégrée (kWh), heure. */
    val startKm: Float = 0f,
    val startNetKwh: Float? = null,
    val startMs: Long = 0L,
    /** Dernier relevé pris en compte. */
    val lastKm: Float = 0f,
    val lastNetKwh: Float? = null,
    val lastConsumedKwh: Float? = null,
    val lastMs: Long = 0L,
    val lastAltM: Float? = null,
)

object TripCurve {

    const val STEP_KM = 0.5f

    /** Au-delà (1 000 km), on cesse d'ajouter des points : le fichier d'historique reste petit. */
    const val MAX_POINTS = 2000

    /** Seuil du dénivelé : sous 5 m, un écart d'altitude GPS est du bruit, pas une côte. */
    const val ELEVATION_THRESHOLD_M = 5f

    fun start(ms: Long) = CurveState(startMs = ms, lastMs = ms)

    /**
     * Un relevé de plus. [km] : distance intégrée depuis le début du trajet ; [consumedKwh] et
     * [regenKwh] : énergie intégrée depuis le début du trajet ; [altM] : altitude GPS, ou null ;
     * [measured] : faux quand l'intervalle n'a pas pu être intégré en distance (vitesse manquante,
     * trou), son énergie est alors retirée du tronçon plutôt que d'y gonfler la consommation.
     */
    fun advance(s: CurveState, km: Float, consumedKwh: Float?, regenKwh: Float?, ms: Long, altM: Float?, measured: Boolean): CurveState {
        consumedKwh ?: return s.copy(lastAltM = altM ?: s.lastAltM)
        val net = consumedKwh - (regenKwh ?: 0f)
        val lastNet = s.lastNetKwh
        val startNet = s.startNetKwh
        // Premier relevé avec énergie : le tronçon commence ici.
        if (lastNet == null || startNet == null) {
            return s.copy(startKm = km, startNetKwh = net, startMs = ms,
                lastKm = km, lastNetKwh = net, lastConsumedKwh = consumedKwh, lastMs = ms, lastAltM = altM)
        }
        // Intervalle non mesuré en distance, ou intégration repartie de zéro : l'énergie de
        // l'intervalle n'est pas attribuée au tronçon (on décale son départ d'autant).
        val repartie = s.lastConsumedKwh != null && consumedKwh < s.lastConsumedKwh - 0.01f
        if (!measured || repartie || km < s.lastKm) {
            val dNet = if (repartie) net else net - lastNet
            return s.copy(startNetKwh = startNet + dNet, startMs = s.startMs + (ms - s.lastMs),
                lastKm = km, lastNetKwh = net, lastConsumedKwh = consumedKwh, lastMs = ms, lastAltM = altM ?: s.lastAltM)
        }
        val pts = StringBuilder(s.points)
        var count = s.count
        var debutKm = s.startKm
        var debutNet: Float = startNet
        var debutMs = s.startMs
        while (count < MAX_POINTS && debutKm + STEP_KM <= km + 1e-4f) {
            val borne = debutKm + STEP_KM
            val f = if (km > s.lastKm) ((borne - s.lastKm) / (km - s.lastKm)).coerceIn(0f, 1f) else 1f
            val eBorne = lastNet + f * (net - lastNet)
            val tBorne = s.lastMs + (f * (ms - s.lastMs)).toLong()
            val alt = interp(s.lastAltM, altM, f)
            pts.append(((eBorne - debutNet) * 1000f).roundToInt()).append(',')
                .append(((tBorne - debutMs) / 1000f).roundToInt().coerceAtLeast(0)).append(',')
            if (alt != null) pts.append(alt.roundToInt())
            pts.append(';')
            count++
            debutKm = borne; debutNet = eBorne; debutMs = tBorne
        }
        // Courbe pleine : le tronçon en cours suit le relevé, sans plus jamais se clore.
        if (count >= MAX_POINTS) { debutKm = km; debutNet = net; debutMs = ms }
        return s.copy(points = pts.toString(), count = count, startKm = debutKm, startNetKwh = debutNet, startMs = debutMs,
            lastKm = km, lastNetKwh = net, lastConsumedKwh = consumedKwh, lastMs = ms, lastAltM = altM)
    }

    private fun interp(a: Float?, b: Float?, f: Float): Float? =
        if (a != null && b != null) a + f * (b - a) else b ?: a

    /** Ce qui est gardé dans [Trip.curve] : null sans aucun point. */
    fun finish(s: CurveState?): String? = s?.points?.takeIf { s.count > 0 }

    fun decode(encoded: String?): List<CurvePoint> {
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split(';').mapNotNull { p ->
            if (p.isEmpty()) return@mapNotNull null
            val c = p.split(',')
            val wh = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val s = c.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            CurvePoint(wh, s, c.getOrNull(2)?.toIntOrNull())
        }
    }

    fun encode(points: List<CurvePoint>): String = points.joinToString("") {
        String.format(Locale.US, "%d,%d,%s;", it.wh, it.seconds, it.altitudeM?.toString() ?: "")
    }

    /**
     * Montée et descente cumulées, en mètres, avec un seuil : l'altitude GPS bouge de quelques mètres
     * à plat, additionner ces écarts inventerait des côtes. Null sans au moins deux altitudes.
     */
    fun elevation(points: List<CurvePoint>): Pair<Int, Int>? {
        val alts = points.mapNotNull { it.altitudeM }
        if (alts.size < 2) return null
        var ref = alts.first().toFloat()
        var plus = 0f
        var moins = 0f
        alts.drop(1).forEach { a ->
            val d = a - ref
            if (d >= ELEVATION_THRESHOLD_M) { plus += d; ref = a.toFloat() }
            else if (-d >= ELEVATION_THRESHOLD_M) { moins += -d; ref = a.toFloat() }
        }
        return plus.roundToInt() to moins.roundToInt()
    }
}
