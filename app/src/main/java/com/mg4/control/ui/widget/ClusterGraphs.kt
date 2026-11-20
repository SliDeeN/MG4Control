package com.mg4.control.ui.widget

import android.content.Context
import com.mg4.control.R
import com.mg4.control.model.ClusterHistory
import com.mg4.control.model.ClusterHistory.Info
import com.mg4.control.model.ClusterModuleDef
import com.mg4.control.model.ConsumptionUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Le module « Graphique » (issue #217) mis en mots : valeurs dans l'unité du module, titre, valeur
 * actuelle, moyenne et graduations de distance, prêts pour [ClusterGaugeView]. Commun au combiné et
 * à l'aperçu de la personnalisation.
 */
object ClusterGraphs {

    fun info(def: ClusterModuleDef?): Info = Info.fromKey(def?.graphInfo)

    fun km(def: ClusterModuleDef?): Int = def?.graphKm?.takeIf { it in ClusterHistory.DISTANCES } ?: ClusterHistory.DEFAULT_KM

    fun points(def: ClusterModuleDef?): Int = def?.graphPoints?.takeIf { it in ClusterHistory.POINTS } ?: ClusterHistory.BARS

    fun nom(ctx: Context, i: Info): String = ctx.getString(when (i) {
        Info.CONSUMPTION -> R.string.cluster_graph_info_consumption
        Info.REGEN -> R.string.cluster_graph_info_regen
        Info.SPEED -> R.string.cluster_graph_info_speed
        Info.ALTITUDE -> R.string.cluster_graph_info_altitude
        Info.OUTSIDE_TEMP -> R.string.cluster_graph_info_temp
        Info.SOC -> R.string.cluster_graph_info_soc
        Info.POWER -> R.string.cluster_graph_info_power
    })

    fun build(ctx: Context, def: ClusterModuleDef?, bins: List<ClusterHistory.Bin>, locale: Locale): ClusterGaugeView.Graphe {
        val i = info(def)
        val total = km(def)
        val unite = ConsumptionUnit.fromKey(def?.unit)
        // Consommation : Wh/km, ou kWh/100 km au dixième (÷ 10).
        val kwh100 = i == Info.CONSUMPTION && unite == ConsumptionUnit.KWH_PER_100KM
        fun conv(v: Float) = if (kwh100) v / 10f else v
        // Axe dynamique (2026-11-20) : seulement les km enregistrés, jusqu'à la distance réglée.
        val montre = ClusterHistory.shownKm(bins, total)
        val valeurs = ClusterHistory.series(bins, i, montre, ClusterHistory.barsFor(montre, points(def))).map { it?.let(::conv) }
        val moyenne = ClusterHistory.average(bins, i, montre)?.let(::conv)
        fun texte(v: Float): String = if (kwh100) String.format(locale, "%.1f", v) else v.roundToInt().toString()
        fun km(v: Float): String = (if (v % 1f == 0f) v.roundToInt().toString() else String.format(locale, "%.1f", v)) + " km"
        val lues = valeurs.filterNotNull()
        val min = lues.minOrNull()
        val max = lues.maxOrNull()
        val vide = ClusterHistory.coveredKm(bins, total) < ClusterHistory.BIN_KM * 2
        return ClusterGaugeView.Graphe(
            info = i,
            titre = "${nom(ctx, i)} · $total km",
            valeurs = valeurs,
            moyenne = moyenne,
            moyenneTexte = moyenne?.let { ctx.getString(R.string.cluster_graph_avg_short, texte(it)) },
            courbe = def?.graphLine == true,
            avecMoyenne = def?.graphAverage != false,
            min = min,
            minTexte = min?.let { ctx.getString(R.string.cluster_graph_min_short, texte(it)) },
            max = max,
            maxTexte = max?.let { ctx.getString(R.string.cluster_graph_max_short, texte(it)) },
            avecMinMax = def?.graphMinMax != false,
            couleur = def?.graphColor,
            axeDebut = km(montre),
            axeMilieu = km(montre / 2f),
            axeFin = ctx.getString(R.string.cluster_graph_now),
            vide = if (vide) ctx.getString(R.string.cluster_graph_empty) else null,
        )
    }

    private fun rampe(k: Float, a: Float, b: Float): Float = ((k - a) / (b - a)).coerceIn(0f, 1f)

    /** Un trajet simulé de 50 km pour l'aperçu de la personnalisation : rien n'est lu sur la voiture. */
    val SIMULE: List<ClusterHistory.Bin> by lazy {
        (0 until 500).map { n ->
            val k = n * ClusterHistory.BIN_KM
            val descente = k in 64f..76f
            val whKm = if (descente) -40f - 30f * kotlin.math.sin(k) else 150f + 50f * kotlin.math.sin(k / 6f) + 30f * kotlin.math.sin(k / 2.3f)
            val kmh = 70f + 30f * kotlin.math.sin(k / 9f) + 10f * kotlin.math.sin(k / 2f)
            val km = ClusterHistory.BIN_KM
            ClusterHistory.Bin(km, whKm * km, (if (descente) 60f else 15f + 10f * kotlin.math.sin(k / 4f)).coerceAtLeast(0f) * km,
                // Altitude continue : la descente (64 → 76 km) fait perdre 140 m, regagnés jusqu'à 96 km.
                km / kmh * 3600f, 300f + 120f * kotlin.math.sin(k / 12f) - 140f * rampe(k, 64f, 76f) + 140f * rampe(k, 76f, 96f),
                9f + 3f * kotlin.math.sin(k / 15f), 80f - k * 0.16f)
        }
    }
}
