package com.mg4.control.model

import kotlin.math.roundToInt

/**
 * Historique des derniers kilomètres pour le module « Graphique » de l'écran conducteur (issue #217,
 * maquette validée le 2026-11-20). Kotlin pur.
 *
 * La route est découpée en tranches de [BIN_KM] : chacune garde sa distance, son énergie nette et
 * récupérée, sa durée, et la dernière altitude, température et batterie lues. On en garde
 * [KEEP_KM] — de quoi dessiner jusqu'à 100 km —, d'un trajet à l'autre. Le graphique regroupe les
 * tranches en [BARS] barres sur la distance choisie (20, 50 ou 100 km).
 */
object ClusterHistory {

    const val BIN_KM = 0.2f
    const val KEEP_KM = 102f
    const val BARS = 50
    val DISTANCES = listOf(20, 50, 100)
    const val DEFAULT_KM = 50

    /** Ce que le graphique peut montrer. */
    enum class Info(val key: String) {
        CONSUMPTION("consumption"), REGEN("regen"), SPEED("speed"), ALTITUDE("altitude"),
        OUTSIDE_TEMP("temp"), SOC("soc"), POWER("power");

        companion object {
            fun fromKey(k: String?): Info = entries.firstOrNull { it.key == k } ?: CONSUMPTION
        }
    }

    /** Une tranche de route. Énergies en Wh (nette : consommée moins récupérée), durée en s. */
    data class Bin(
        val km: Float, val wh: Float, val regenWh: Float, val s: Float,
        val alt: Float? = null, val temp: Float? = null, val soc: Float? = null,
    )

    /**
     * Accumule les relevés du combiné (puissance positive en décharge, vitesse) et rend une tranche
     * chaque fois que [BIN_KM] sont parcourus. Les relevés à l'arrêt comptent dans la tranche
     * suivante : la consommation à l'arrêt (chauffage) se paie au kilomètre suivant.
     */
    class Accumulator {
        private var km = 0f
        private var wh = 0f
        private var regen = 0f
        private var s = 0f

        fun add(dtS: Float, kw: Float?, kmh: Float?, alt: Float?, temp: Float?, soc: Float?): Bin? {
            if (dtS <= 0f || dtS > 30f) return null   // trou de relevé : rien d'inventé
            val v = kmh?.coerceIn(0f, 250f) ?: 0f
            km += v * dtS / 3600f
            kw?.let { p ->
                wh += p * dtS / 3.6f
                if (p < 0f) regen += -p * dtS / 3.6f
            }
            s += dtS
            // Marge d'arrondi : 10 × 20 m font 199,99… m en flottants.
            if (km < BIN_KM - 1e-4f) return null
            val b = Bin(km, wh, regen, s, alt, temp, soc)
            km = 0f; wh = 0f; regen = 0f; s = 0f
            return b
        }
    }

    /** L'historique avec [b] ajoutée, les plus anciennes au-delà de [KEEP_KM] retirées. */
    fun append(bins: List<Bin>, b: Bin): List<Bin> {
        val tout = bins + b
        var total = 0f
        var debut = tout.size
        for (i in tout.indices.reversed()) {
            total += tout[i].km
            debut = i
            if (total >= KEEP_KM) break
        }
        return tout.subList(debut, tout.size)
    }

    /**
     * Les [BARS] valeurs du graphique sur les derniers [totalKm] km, de la plus ancienne à la plus
     * récente ; `null` pour une barre sans donnée (historique plus court, information illisible).
     * Consommation et récupération en Wh/km, vitesse en km/h, puissance en kW, le reste tel quel.
     */
    fun series(bins: List<Bin>, info: Info, totalKm: Int, bars: Int = BARS): List<Float?> = series(bins, info, totalKm.toFloat(), bars)

    fun series(bins: List<Bin>, info: Info, totalKm: Float, bars: Int = BARS): List<Float?> {
        val pas = totalKm / bars
        val groupes = List(bars) { mutableListOf<Bin>() }
        var recul = 0f
        for (b in bins.asReversed()) {
            val milieu = recul + b.km / 2f
            recul += b.km
            if (milieu >= totalKm) break
            val rang = bars - 1 - (milieu / pas).toInt().coerceIn(0, bars - 1)
            groupes[rang] += b
        }
        return groupes.map { g -> valeur(g, info) }
    }

    private fun valeur(g: List<Bin>, info: Info): Float? {
        if (g.isEmpty()) return null
        val km = g.sumOf { it.km.toDouble() }.toFloat()
        val s = g.sumOf { it.s.toDouble() }.toFloat()
        fun moyenne(f: (Bin) -> Float?): Float? {
            val lus = g.mapNotNull { b -> f(b)?.let { it to b.km } }
            val poids = lus.sumOf { it.second.toDouble() }.toFloat()
            return if (lus.isEmpty() || poids <= 0f) null else lus.sumOf { (v, k) -> (v * k).toDouble() }.toFloat() / poids
        }
        return when (info) {
            Info.CONSUMPTION -> if (km > 0f) g.sumOf { it.wh.toDouble() }.toFloat() / km else null
            Info.REGEN -> if (km > 0f) g.sumOf { it.regenWh.toDouble() }.toFloat() / km else null
            Info.SPEED -> if (s > 0f) km / s * 3600f else null
            Info.POWER -> if (s > 0f) g.sumOf { it.wh.toDouble() }.toFloat() / s * 3.6f else null
            Info.ALTITUDE -> moyenne { it.alt }
            Info.OUTSIDE_TEMP -> moyenne { it.temp }
            // Les tranches d'une barre sont rangées de la plus récente à la plus ancienne.
            Info.SOC -> g.firstOrNull { it.soc != null }?.soc
        }
    }

    /** Moyenne du graphique : pondérée par la distance pour les rapports au km, simple sinon. */
    fun average(bins: List<Bin>, info: Info, totalKm: Int): Float? = average(bins, info, totalKm.toFloat())

    fun average(bins: List<Bin>, info: Info, totalKm: Float): Float? = series(bins, info, totalKm, 1).firstOrNull()

    /** Pas simples de l'axe tant que l'historique est plus court que la distance réglée. */
    private val PAS_AXE = listOf(1f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 15f, 20f, 25f, 30f, 40f, 50f, 60f, 80f, 100f)

    /**
     * Distance couverte par l'axe (maquette du 2026-11-20) : l'historique enregistré, arrondi au pas
     * simple suivant, qui s'allonge en roulant jusqu'à la distance réglée [totalKm].
     */
    fun shownKm(bins: List<Bin>, totalKm: Int): Float {
        val roule = coveredKm(bins, totalKm)
        if (roule >= totalKm) return totalKm.toFloat()
        return (PAS_AXE.firstOrNull { it >= roule - 1e-3f } ?: totalKm.toFloat()).coerceAtMost(totalKm.toFloat())
    }

    /** Barres pour [shownKm] : [BARS] au plus, jamais plus fines qu'une tranche de [BIN_KM]. */
    fun barsFor(shownKm: Float): Int = (shownKm / BIN_KM).roundToInt().coerceIn(1, BARS)

    /** Distance couverte par l'historique (km), plafonnée à [totalKm]. */
    fun coveredKm(bins: List<Bin>, totalKm: Int): Float = bins.sumOf { it.km.toDouble() }.toFloat().coerceAtMost(totalKm.toFloat())
}
