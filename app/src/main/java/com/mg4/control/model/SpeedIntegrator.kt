package com.mg4.control.model

/**
 * Distance d'un trajet par intégration de la vitesse **à la seconde** (2026-11-05), méthode des
 * trapèzes — sur le même tic que la puissance ([PowerIntegrator]).
 *
 * L'odomètre du véhicule est au kilomètre entier, et l'intégration faite à chaque relevé (10 s)
 * approxime une accélération ou un freinage par une seule droite : en ville, l'erreur se voit sur
 * les barres de 200 m du graphique d'un trajet ([TripCurve]). Lue chaque seconde, la vitesse suit
 * la conduite. L'intégration à 10 s reste calculée en contrôle ([Trip.distanceCheckKm]).
 *
 * Aucun accès au véhicule : l'appelant fournit des instants monotones (`elapsedRealtime`) et la
 * vitesse lue, `null` quand elle est illisible.
 */
class SpeedIntegrator {

    data class Result(
        val km: Float,
        /** Relevés utilisables. */
        val samples: Int,
        /** Intervalles laissés de côté parce que trop longs pour être intégrés. */
        val gaps: Int,
    )

    // En km/h·ms, en double : des milliers de petits trapèzes.
    private var aire = 0.0
    private var samples = 0
    private var gaps = 0
    private var lastMs = 0L
    private var lastKmh = 0f
    private var hasLast = false

    fun add(timeMs: Long, kmh: Float?) {
        if (kmh == null || !kmh.isFinite() || kmh < 0f) return
        samples++
        if (hasLast) {
            val dt = timeMs - lastMs
            when {
                dt <= 0L -> {}
                dt > MAX_GAP_MS -> gaps++
                else -> aire += (lastKmh + kmh) / 2.0 * dt
            }
        }
        lastMs = timeMs
        lastKmh = kmh
        hasLast = true
    }

    fun result() = Result((aire / MS_PER_HOUR).toFloat(), samples, gaps)

    fun reset() {
        aire = 0.0
        samples = 0
        gaps = 0
        hasLast = false
    }

    companion object {
        /** Cinq relevés manqués d'affilée à la cadence prévue d'une seconde. */
        const val MAX_GAP_MS = 5_000L
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
