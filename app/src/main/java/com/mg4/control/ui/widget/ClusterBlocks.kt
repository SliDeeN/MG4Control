package com.mg4.control.ui.widget

import android.content.Context
import com.mg4.control.R
import com.mg4.control.model.BubbleInfo
import com.mg4.control.model.ClusterBlock
import com.mg4.control.model.ClusterGauge
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.ClusterLayouts
import com.mg4.control.model.ClusterPill
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.StatsTracker
import com.mg4.control.ui.AndroidRuleWords
import com.mg4.control.ui.InfoIcons
import java.util.Date
import java.util.Locale

/**
 * Les cases d'information de l'écran conducteur mises en mots (issue #229) : sur le combiné
 * ([com.mg4.control.service.ClusterModuleOverlay], valeurs lues) comme dans l'aperçu des réglages
 * (valeurs simulées). Rien n'est lu ici : [Lecture] apporte tout.
 */
object ClusterBlocks {

    /**
     * Ce qu'il faut pour remplir les cases. [signaux] : les valeurs lues des signaux demandés par
     * [signauxLus], par nom ; [vitesseKmh] sert à la vitesse, déjà relevée pour la jauge.
     */
    class Lecture(
        val trajet: ClusterInfo.Trip,
        val vitesseKmh: Float?,
        val profilNom: String?,
        val profilMode: DriveMode?,
        val mode: DriveMode?,
        val signaux: Map<String, Any?> = emptyMap(),
    )

    /** Signaux à lire pour [l] : ceux des cases, sauf ce que le module relève déjà (profil, mode, vitesse). */
    fun signauxLus(l: ClusterLayout): List<RuleSignal> = ClusterLayouts.slots(l).mapNotNull { b ->
        BubbleInfo.signal(b?.info)?.takeIf { it != RuleSignal.PROFILE && it != RuleSignal.DRIVE_MODE && it != RuleSignal.SPEED }
    }.distinct()

    fun usesProfile(l: ClusterLayout): Boolean = ClusterLayouts.slots(l).any { it?.info == RuleSignal.PROFILE.name }

    /** Nom d'une information (choix, libellé d'une case). */
    fun nom(ctx: Context, key: String?, mots: AndroidRuleWords): String = when (key) {
        ClusterInfo.TRIP_AVERAGE -> ctx.getString(R.string.cluster_info_trip_average)
        ClusterInfo.TRIP_DISTANCE -> ctx.getString(R.string.cluster_info_trip_distance)
        ClusterInfo.TRIP_ENERGY -> ctx.getString(R.string.cluster_info_trip_energy)
        ClusterInfo.TRIP_REGEN -> ctx.getString(R.string.cluster_info_trip_regen)
        ClusterInfo.POWER -> ctx.getString(R.string.cluster_info_power)
        BubbleInfo.TIME -> ctx.getString(R.string.floating_info_time)
        else -> BubbleInfo.signal(key)?.let { mots.signal(it) } ?: key.orEmpty()
    }

    /** Libellé court au-dessus de la valeur : les mots du pied d'avant pour la moyenne et la récupération. */
    private fun libelle(ctx: Context, key: String?, mots: AndroidRuleWords): String = when (key) {
        ClusterInfo.TRIP_AVERAGE -> ctx.getString(R.string.cluster_trip_avg)
        ClusterInfo.TRIP_REGEN -> ctx.getString(R.string.cluster_regen)
        else -> nom(ctx, key, mots)
    }

    /** Mot d'une unité de bloc, à l'écran (« ch », « PS »… pour la puissance en chevaux). */
    fun unite(ctx: Context, u: String?): String? = if (u == ClusterInfo.UNIT_HP) ctx.getString(R.string.cluster_unit_hp) else u

    fun build(
        ctx: Context, l: ClusterLayout, lu: Lecture, general: DistanceUnit, mots: AndroidRuleWords, locale: Locale,
    ): List<ClusterGaugeView.Bloc?> {
        val recup = l.regenColor ?: ctx.getColor(R.color.cluster_accent)
        return ClusterLayouts.slots(l).map { b -> b?.let { bloc(ctx, it, lu, general, mots, locale, recup) } }
    }

    private fun bloc(
        ctx: Context, b: ClusterBlock, lu: Lecture, general: DistanceUnit, mots: AndroidRuleWords, locale: Locale, recup: Int,
    ): ClusterGaugeView.Bloc {
        val style = ClusterInfo.style(b)
        val taille = ClusterInfo.size(b)
        val icone = InfoIcons.of(b.info)
        val lib = libelle(ctx, b.info, mots)
        fun fait(v: Pair<String, String?>, pastille: com.mg4.control.model.PillContent? = null, couleur: Int? = null) =
            ClusterGaugeView.Bloc(v.first, unite(ctx, v.second), lib, icone, pastille, style, taille, couleur)

        ClusterInfo.tripValue(b, lu.trajet, general, locale)?.let { v ->
            return fait(v, couleur = if (b.info == ClusterInfo.TRIP_REGEN && v.first != BubbleInfo.UNKNOWN) recup else null)
        }
        return when (b.info) {
            RuleSignal.PROFILE.name -> {
                val nom = lu.profilNom?.trim()?.takeIf { it.isNotEmpty() }
                val p = ClusterGauge.pill(ClusterPill.PROFILE, lu.mode, nom, lu.profilMode, locale)
                fait((nom ?: BubbleInfo.UNKNOWN) to null, pastille = p)
            }
            RuleSignal.DRIVE_MODE.name -> {
                val p = ClusterGauge.pill(ClusterPill.DRIVE_MODE, lu.mode, null, null, locale)
                fait((lu.mode?.let { mots.value(RuleSignal.DRIVE_MODE, it.name) } ?: BubbleInfo.UNKNOWN) to null, pastille = p)
            }
            BubbleInfo.TIME -> {
                val maintenant = System.currentTimeMillis()
                // Horloge d'usine au réveil (~2019) : pas d'heure plutôt qu'une heure fausse.
                fait((if (StatsTracker.clockPlausible(maintenant))
                    android.text.format.DateFormat.getTimeFormat(ctx).format(Date(maintenant)) else BubbleInfo.UNKNOWN) to null)
            }
            else -> {
                val s = BubbleInfo.signal(b.info) ?: return fait(BubbleInfo.UNKNOWN to null)
                val brut = if (s == RuleSignal.SPEED) lu.vitesseKmh?.toDouble() else lu.signaux[s.name]
                val converti = BubbleInfo.inDistanceUnit(s, brut, ClusterInfo.distance(b, general))
                val v = converti?.first ?: brut
                val u = (converti?.second ?: mots.unit(s).ifEmpty { BubbleInfo.extraUnit(s) }).trim()
                val texte = BubbleInfo.format(v, "", ctx.getString(R.string.common_on), ctx.getString(R.string.common_off),
                    { mots.value(s, it) }, locale)
                fait(texte to u.takeIf { it.isNotEmpty() && texte != BubbleInfo.UNKNOWN && v !is Boolean })
            }
        }
    }
}
