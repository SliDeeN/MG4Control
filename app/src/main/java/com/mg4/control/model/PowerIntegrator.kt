package com.mg4.control.model

/**
 * Énergie d'un trajet par intégration de la puissance batterie (tension × courant), méthode des
 * trapèzes.
 *
 * Pourquoi : sur SWI68 les compteurs d'énergie du véhicule restent à zéro et les trajets
 * s'enregistrent sans énergie (issue #117) ; ceux de l'écran d'origine n'avancent que par kWh
 * entier (mesuré le 2026-10-05). Tension et courant, eux, se lisent partout.
 *
 * La puissance est comptée **positive en décharge**. Sa partie positive va dans la consommation,
 * sa partie négative dans la récupération ; un intervalle qui change de signe est partagé au
 * passage par zéro. Le **net** est la grandeur à comparer aux compteurs : la répartition, elle,
 * diffère un peu par construction — pendant un freinage, les accessoires consomment une part de
 * ce que le moteur récupère, et la batterie n'en voit que le solde.
 *
 * Aucun accès au véhicule : l'appelant fournit des instants monotones (`elapsedRealtime`,
 * insensibles aux sauts de l'horloge) et la puissance lue, `null` quand elle est illisible.
 */
class PowerIntegrator {

    data class Result(
        val consumedKwh: Float,
        val regenKwh: Float,
        /** Relevés utilisables. */
        val samples: Int,
        /** Relevés où la puissance était illisible. */
        val missed: Int,
        /** Intervalles laissés de côté parce que trop longs pour être intégrés. */
        val gaps: Int,
        /** Durée réellement intégrée : comparée à celle du trajet, elle dit ce qui a manqué. */
        val coveredMs: Long,
    ) {
        val netKwh: Float get() = consumedKwh - regenKwh
    }

    // En kW·ms, et en double : des milliers de petits trapèzes s'additionnent mal en flottant simple.
    private var consumed = 0.0
    private var regen = 0.0
    private var samples = 0
    private var missed = 0
    private var gaps = 0
    private var coveredMs = 0L

    private var lastMs = 0L
    private var lastKw = 0f
    private var hasLast = false

    fun add(timeMs: Long, dischargeKw: Float?) {
        if (dischargeKw == null || !dischargeKw.isFinite()) {
            missed++
            return
        }
        samples++
        if (hasLast) {
            val dt = timeMs - lastMs
            when {
                dt <= 0L -> {}
                // Au-delà, supposer la puissance linéaire entre deux points reviendrait à inventer.
                dt > MAX_GAP_MS -> gaps++
                else -> {
                    trapeze(lastKw, dischargeKw, dt)
                    coveredMs += dt
                }
            }
        }
        lastMs = timeMs
        lastKw = dischargeKw
        hasLast = true
    }

    private fun trapeze(a: Float, b: Float, dt: Long) {
        if (a >= 0f && b >= 0f) {
            consumed += (a + b) / 2.0 * dt
        } else if (a <= 0f && b <= 0f) {
            regen -= (a + b) / 2.0 * dt
        } else {
            // Changement de signe : deux triangles, de part et d'autre du passage par zéro.
            val avant = dt * (a / (a - b).toDouble())
            val apres = dt - avant
            val aireAvant = a / 2.0 * avant
            val aireApres = b / 2.0 * apres
            if (aireAvant >= 0) consumed += aireAvant else regen -= aireAvant
            if (aireApres >= 0) consumed += aireApres else regen -= aireApres
        }
    }

    fun result() = Result(
        consumedKwh = (consumed / MS_PER_HOUR).toFloat(),
        regenKwh = (regen / MS_PER_HOUR).toFloat(),
        samples = samples,
        missed = missed,
        gaps = gaps,
        coveredMs = coveredMs,
    )

    fun reset() {
        consumed = 0.0
        regen = 0.0
        samples = 0
        missed = 0
        gaps = 0
        coveredMs = 0L
        hasLast = false
    }

    companion object {
        /** Cinq relevés manqués d'affilée à la cadence prévue d'une seconde. */
        const val MAX_GAP_MS = 5_000L

        private const val MS_PER_HOUR = 3_600_000.0
    }
}
