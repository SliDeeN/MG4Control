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
import com.mg4.control.model.TripPeriod
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
        /** Compteurs d'une autre période que READY ([com.mg4.control.service.TripMeter], 2026-11-22). */
        val periode: (TripPeriod) -> ClusterInfo.Trip = { trajet },
        /** Minutes en READY d'une autre période que READY ; `null` = inconnu. */
        val minutesPeriode: (TripPeriod) -> Double? = { null },
    ) {
        /** Le trajet de la période choisie par la case [b] : celui du module pour READY. */
        fun trajetDe(b: ClusterBlock): ClusterInfo.Trip =
            TripPeriod.of(b.period).let { if (it == TripPeriod.READY) trajet else periode(it) }
    }

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

    /**
     * Libellé au-dessus de la valeur, toujours en capitales (issue #268, 2026-11-22, variante B) :
     * un mot court pour les informations aux noms longs, comme « MOY. TRAJET » et « RÉCUP. » avant
     * elles ; le nom de l'information en capitales pour les autres.
     */
    fun libelle(ctx: Context, key: String?, mots: AndroidRuleWords, locale: Locale): String =
        (court(key)?.let { ctx.getString(it) } ?: nom(ctx, key, mots)).uppercase(locale)

    /** Libellé court d'une information, ou `null` : son nom suffit. */
    fun court(key: String?): Int? = when (key) {
        ClusterInfo.TRIP_AVERAGE -> R.string.cluster_trip_avg
        ClusterInfo.TRIP_REGEN -> R.string.cluster_regen
        ClusterInfo.TRIP_DISTANCE -> R.string.cluster_lbl_trip_distance
        ClusterInfo.TRIP_ENERGY -> R.string.cluster_lbl_trip_energy
        else -> when (BubbleInfo.signal(key)) {
            RuleSignal.OUTSIDE_TEMP -> R.string.cluster_lbl_outside_temp
            RuleSignal.HVAC_TEMP -> R.string.cluster_lbl_hvac_temp
            RuleSignal.DRIVE_MINUTES -> R.string.cluster_lbl_drive_time
            RuleSignal.DRIVE_MODE -> R.string.cluster_lbl_drive_mode
            RuleSignal.REGEN -> R.string.cluster_lbl_regen_level
            RuleSignal.CHARGE_LIMIT -> R.string.cluster_lbl_charge_limit
            RuleSignal.CHARGE_CURRENT -> R.string.cluster_lbl_charge_current
            RuleSignal.TYRE_PRESSURE_MIN -> R.string.cluster_lbl_tyre_min
            RuleSignal.TYRE_PRESSURE_FL -> R.string.cluster_lbl_tyre_fl
            RuleSignal.TYRE_PRESSURE_FR -> R.string.cluster_lbl_tyre_fr
            RuleSignal.TYRE_PRESSURE_RL -> R.string.cluster_lbl_tyre_rl
            RuleSignal.TYRE_PRESSURE_RR -> R.string.cluster_lbl_tyre_rr
            RuleSignal.TYRE_TEMP_MAX -> R.string.cluster_lbl_tyre_hot
            RuleSignal.TYRE_TEMP_FL -> R.string.cluster_lbl_tyre_temp_fl
            RuleSignal.TYRE_TEMP_FR -> R.string.cluster_lbl_tyre_temp_fr
            RuleSignal.TYRE_TEMP_RL -> R.string.cluster_lbl_tyre_temp_rl
            RuleSignal.TYRE_TEMP_RR -> R.string.cluster_lbl_tyre_temp_rr
            else -> null
        }
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
        val lib = libelle(ctx, b.info, mots, locale)
        // Couleur choisie pour la valeur (2026-11-22) : par-dessus celle de la récupération ; jamais sur « — ».
        fun fait(v: Pair<String, String?>, pastille: com.mg4.control.model.PillContent? = null, couleur: Int? = null) =
            ClusterGaugeView.Bloc(v.first, unite(ctx, v.second), lib, icone, pastille, style, taille,
                if (v.first != BubbleInfo.UNKNOWN) b.color ?: couleur else couleur)

        ClusterInfo.tripValue(b, lu.trajetDe(b), general, locale)?.let { v ->
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
                // Temps depuis READY : en chronomètre, sans unité ; une autre période se cumule (2026-11-22).
                val p = TripPeriod.of(b.period)
                val minutes = if (s == RuleSignal.DRIVE_MINUTES && p != TripPeriod.READY) lu.minutesPeriode(p) else brut
                BubbleInfo.chrono(s, minutes)?.let { return fait(it to null) }
                // Pression des pneus : dans l'unité choisie (bar, PSI, kPa).
                BubbleInfo.pressure(s, brut, com.mg4.control.util.TyreUnitSetting.unit(ctx), locale)?.let { return fait(it) }
                val converti = BubbleInfo.inDistanceUnit(s, brut, ClusterInfo.distance(b, general))
                val v = converti?.first ?: brut
                val u = (converti?.second ?: mots.unit(s).ifEmpty { BubbleInfo.extraUnit(s) }).trim()
                val texte = BubbleInfo.format(v, "", ctx.getString(R.string.common_on), ctx.getString(R.string.common_off),
                    { mots.value(s, it) }, locale, BubbleInfo.oneDecimal(s))
                fait(texte to u.takeIf { it.isNotEmpty() && texte != BubbleInfo.UNKNOWN && v !is Boolean })
            }
        }
    }
}
