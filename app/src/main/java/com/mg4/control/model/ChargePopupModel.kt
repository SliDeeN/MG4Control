package com.mg4.control.model

import kotlin.math.roundToInt

/**
 * Limite de charge (`BMS_CHRG_TRGT_SOC` 0x2140f40c) : un code de 1 à 7 pour 40 à 100 % par dix.
 *
 * Correspondance prouvée par l'appli de charge SAIC des A9 (SWI69 `EvCharge`, 2026-10-16) : sa
 * lecture rend 1 → 40 % … 7 → 100 % (0 et le reste → 100 %), son écriture envoie le code tel quel
 * par `CarBMSManager.setGlobalProperty`. Sur SWI133, le service SAIC (`VehicleChargingBinder.
 * setChargingCloseSoc`) écrit la même propriété, bornée par ses ressources à 1..7.
 */
object ChargeLimit {

    const val MIN_PCT = 40
    const val MAX_PCT = 100
    const val PAS_PCT = 10

    /** Les sept crans du curseur. */
    val CRANS: List<Int> = (MIN_PCT..MAX_PCT step PAS_PCT).toList()

    /** Pourcentage quelconque → cran le plus proche, dans 40..100. */
    fun cran(pct: Float): Int =
        ((pct / PAS_PCT).roundToInt() * PAS_PCT).coerceIn(MIN_PCT, MAX_PCT)

    /** Cran → code véhicule (40 % → 1 … 100 % → 7). */
    fun code(pct: Int): Int = (cran(pct.toFloat()) - 30) / PAS_PCT

    /** Code véhicule → cran, ou `null` hors de 1..7. */
    fun pct(code: Int?): Int? = ChargeDisplay.ciblePourcent(code)
}

/** Un point de la courbe : [tMs] depuis le début de la charge, puissance qui entre (kW) et niveau (%). */
data class ChargePoint(val tMs: Long, val kw: Float?, val soc: Float?)

/**
 * Une charge vue par le pop-up de charge, depuis son début : la courbe (un point toutes les
 * [POINT_MS]), l'énergie entrée dans la batterie ([ChargeEnCours], intégrée à CHAQUE relevé), la
 * puissance maximale, et le prix du kWh corrigé à la main pour CETTE charge (« Ne change que cette
 * charge »). Le temps est celui, monotone, de `elapsedRealtime` : l'horloge du boîtier peut sauter.
 *
 * Gardée par le service pendant toute la charge, pop-up fermé ou non : la courbe part du début de
 * la charge, pas de l'ouverture du pop-up (demande du 2026-10-16).
 */
class ChargeLog(
    /** Début, en temps monotone. */
    val debutMs: Long,
    /** Début à l'horloge, ou `null` si l'horloge n'est pas crédible (réveil du boîtier en 2019). */
    val debutHorlogeMs: Long?,
    val socDebut: Float?,
    val autonomieDebutKm: Int?,
) {
    private val pts = ArrayList<ChargePoint>()
    private val energie = ChargeEnCours().also { it.commencer(debutMs) }
    private var dernierPointMs = Long.MIN_VALUE

    val points: List<ChargePoint> get() = pts
    val energieKwh: Float get() = energie.energieKwh
    var kwMax: Float? = null
        private set
    /** Fin de la charge (temps monotone), `null` tant qu'elle dure. */
    var finMs: Long? = null
        private set
    /** Prix du kWh corrigé pour cette charge ; `null` = tarif par défaut du type. */
    var prix: Float? = null

    val finie: Boolean get() = finMs != null

    /** Un relevé : [kw] = puissance qui ENTRE dans la batterie (positive en charge). */
    fun ajouter(maintenantMs: Long, kw: Float?, soc: Float?) {
        if (finie) return
        energie.ajouter(maintenantMs, kw)
        if (kw != null && kw > (kwMax ?: 0f)) kwMax = kw
        if (pts.isEmpty() || maintenantMs - dernierPointMs >= POINT_MS) {
            pts += ChargePoint(maintenantMs - debutMs, kw, soc)
            dernierPointMs = maintenantMs
        }
    }

    fun terminer(maintenantMs: Long) {
        if (finMs == null) finMs = maintenantMs
    }

    /** La charge reprend peu après s'être arrêtée (borne qui marque une pause) : même session. */
    fun reprendre() {
        finMs = null
    }

    /** Durée en minutes, jusqu'à [maintenantMs] ou jusqu'à la fin. */
    fun dureeMinutes(maintenantMs: Long): Int = (((finMs ?: maintenantMs) - debutMs) / 60_000L).toInt()

    /**
     * Au plus [max] points pour le dessin, le dernier toujours compris : une charge de nuit en
     * compte des milliers. On en garde un sur n, ce qui suffit à une courbe lissée.
     */
    fun courbe(max: Int = MAX_POINTS_DESSIN): List<ChargePoint> = echantillonner(pts, max)

    /**
     * La charge chevauche-t-elle l'intervalle [debut, fin] (horloge) ? Sert à retrouver, dans
     * l'onglet Statistiques, la session qui correspond à celle-ci — leurs débuts diffèrent de la
     * cadence de chacun. Faux sans horloge crédible.
     */
    fun recouvre(debut: Long, fin: Long, maintenantHorlogeMs: Long, maintenantMs: Long): Boolean {
        val d = debutHorlogeMs ?: return false
        val f = maintenantHorlogeMs - (maintenantMs - (finMs ?: maintenantMs))
        return debut <= f && fin >= d
    }

    companion object {
        const val POINT_MS = 5_000L
        const val MAX_POINTS_DESSIN = 400

        fun echantillonner(points: List<ChargePoint>, max: Int): List<ChargePoint> {
            if (points.size <= max || max < 2) return points
            val pas = (points.size + max - 2) / (max - 1)
            val r = points.filterIndexed { i, _ -> i % pas == 0 }.toMutableList()
            if (r.last() !== points.last()) r += points.last()
            return r
        }
    }
}
