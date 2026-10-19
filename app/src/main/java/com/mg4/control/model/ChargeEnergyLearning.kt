package com.mg4.control.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * D'où vient l'énergie d'une charge des Statistiques (issue #156). `null` dans les enregistrements
 * d'avant : le pourcentage.
 */
enum class ChargeEnergySource {
    /** Écart de pourcentage × capacité utile : la méthode d'origine, celle de l'AC. */
    PERCENT,
    /** Mesurée par le suivi de charge, vue du début à la fin. */
    MEASURED,
    /** Mesurée sur la partie vue, puis la table apprise pour le reste (écran éteint en cours de route). */
    MIXED,
    /** Jamais vue : la table apprise, tranche par tranche. */
    LEARNED,
}

/**
 * Énergie par tranche de 1 % de batterie, apprise pendant les charges que l'application voit
 * (issue #156, idée de l'utilisateur) : la tranche n va de n % à n+1 %.
 *
 * En charge rapide, le pourcentage ne suit pas l'énergie entrée (4,93 kWh à la borne pour 6,1 %,
 * soit 81 kWh par 100 % sur une batterie de 58,9 kWh) ; et l'écran central s'éteint dès le
 * verrouillage, si bien que la plupart des charges rapides ne sont pas vues. La table, nourrie par
 * celles où l'on reste dans la voiture, permet de mieux estimer les autres.
 *
 * Une tranche ne sert qu'à partir de [MIN_OBS] mesures ; avant, et pour toute tranche jamais vue,
 * c'est la valeur par défaut (capacité utile ÷ 100). Les mesures récentes comptent davantage
 * ([RECENTES]), pour suivre le vieillissement de la batterie ; une mesure invraisemblable (hors de
 * [MIN_FACTEUR] à [MAX_FACTEUR] fois le défaut) est refusée.
 */
class SocEnergyTable(
    private val kwh: FloatArray = FloatArray(TRANCHES) { Float.NaN },
    private val obs: IntArray = IntArray(TRANCHES),
) {

    /** Une mesure de la tranche [tranche] ; rend faux si elle est refusée. */
    fun observer(tranche: Int, kwhMesure: Float, defautParPct: Float): Boolean {
        if (tranche !in 0 until TRANCHES) return false
        if (kwhMesure.isNaN() || kwhMesure < defautParPct * MIN_FACTEUR || kwhMesure > defautParPct * MAX_FACTEUR) return false
        val n = obs[tranche]
        kwh[tranche] = if (n == 0 || kwh[tranche].isNaN()) kwhMesure
            else kwh[tranche] + (kwhMesure - kwh[tranche]) / min(n + 1, RECENTES)
        obs[tranche] = min(n + 1, MAX_OBS)
        return true
    }

    /** Énergie apprise de la tranche, ou `null` tant qu'elle n'a pas assez de mesures. */
    fun valeur(tranche: Int): Float? =
        if (tranche in 0 until TRANCHES && obs[tranche] >= MIN_OBS && !kwh[tranche].isNaN()) kwh[tranche] else null

    /** Nombre de tranches utilisables. */
    fun apprises(): Int = (0 until TRANCHES).count { valeur(it) != null }

    /** Les tranches utilisables, pour le Diagnostic : tranche → (kWh, mesures). */
    fun releve(): List<Triple<Int, Float, Int>> =
        (0 until TRANCHES).mapNotNull { t -> valeur(t)?.let { Triple(t, it, obs[t]) } }

    data class Estimation(val kwh: Float, val tranchesApprises: Int, val tranches: Int)

    /**
     * Énergie de [socDebut] à [socFin] : la somme des tranches traversées, une tranche entamée au
     * prorata, la valeur apprise si elle existe, sinon [defautParPct].
     */
    fun estimer(socDebut: Float, socFin: Float, defautParPct: Float): Estimation {
        if (socFin <= socDebut) return Estimation(0f, 0, 0)
        var total = 0f
        var apprises = 0
        var tranches = 0
        for (t in floor(socDebut).toInt() until ceil(socFin).toInt()) {
            val part = min(socFin, t + 1f) - max(socDebut, t.toFloat())
            if (part <= 0f) continue
            tranches++
            val v = valeur(t)
            if (v != null) apprises++
            total += (v ?: defautParPct) * part
        }
        return Estimation(total, apprises, tranches)
    }

    /** Texte pour les préférences : « 1| » puis, par tranche, « kWh:mesures » séparés par « ; ». */
    fun encoder(): String = "1|" + (0 until TRANCHES).joinToString(";") { t ->
        if (obs[t] == 0 || kwh[t].isNaN()) "" else "${kwh[t]}:${obs[t]}"
    }

    companion object {
        const val TRANCHES = 100
        const val MIN_OBS = 2
        /** Au-delà, une nouvelle mesure pèse 1/[RECENTES] : une moyenne glissante. */
        const val RECENTES = 5
        const val MAX_OBS = 999
        const val MIN_FACTEUR = 0.4f
        const val MAX_FACTEUR = 2.5f

        /** Une table vide si [texte] est absent ou abîmé : ce n'est qu'un apprentissage. */
        fun decoder(texte: String?): SocEnergyTable {
            val t = SocEnergyTable()
            if (texte == null || !texte.startsWith("1|")) return t
            val cases = texte.removePrefix("1|").split(';')
            if (cases.size != TRANCHES) return t
            cases.forEachIndexed { i, c ->
                val morceaux = c.split(':')
                if (morceaux.size == 2) {
                    val v = morceaux[0].toFloatOrNull()
                    val n = morceaux[1].toIntOrNull()
                    if (v != null && n != null && n > 0) { t.kwh[i] = v; t.obs[i] = min(n, MAX_OBS) }
                }
            }
            return t
        }
    }
}

/**
 * Découpe une charge vue en tranches de 1 % : à chaque pourcentage entier franchi, rend la tranche
 * qui vient de se boucler et l'énergie entrée pendant (issue #156).
 *
 * Seules comptent les tranches vues EN ENTIER, d'un entier au suivant : la première, entamée au
 * début du suivi, ne fait que poser la référence. Un trou de plus de [trouMs] entre deux relevés
 * (écran éteint), un saut de plus d'un pourcentage ou un recul font repartir de zéro.
 */
class SocBandRecorder(private val trouMs: Long = ChargeEnCours.TROU_MAX_MS) {

    private var palier: Int? = null
    /** Faux tant que [palier] n'a pas été atteint en le franchissant : sa tranche est alors entamée. */
    private var aligne = false
    private var energieAuPalier = 0f
    private var dernierMs: Long? = null

    /** Un relevé : temps monotone, pourcentage, énergie cumulée de la charge. */
    fun observer(tMs: Long, soc: Float?, energieKwh: Float): Pair<Int, Float>? {
        val avant = dernierMs
        dernierMs = tMs
        if (soc == null) return null
        val n = floor(soc).toInt()
        val p = palier
        if (p == null || avant == null || tMs - avant > trouMs || n < p || n > p + 1) {
            palier = n
            aligne = false
            energieAuPalier = energieKwh
            return null
        }
        if (n == p) return null
        // n == p + 1 : un entier franchi.
        val bouclee = if (aligne) p to (energieKwh - energieAuPalier) else null
        palier = n
        aligne = true
        energieAuPalier = energieKwh
        return bouclee
    }
}

/** Ce que le suivi de charge a vu d'une charge, pour les Statistiques (issue #156). */
data class ChargeObservation(
    /** Pourcentage au début du suivi. */
    val socDebut: Float?,
    /** Énergie mesurée sur la partie vue sans trou, depuis le début du suivi. */
    val energieKwh: Float,
    /** Durée de cette partie. */
    val dureeMs: Long,
    /** Dernier pourcentage de cette partie. */
    val socFinVue: Float?,
    /** Vue du début à la fin, sans trou : l'énergie mesurée est celle de toute la charge. */
    val complete: Boolean,
    /** Type de prise vu pendant la charge, si l'onglet Statistiques ne l'a pas. */
    val type: ChargeType? = null,
)

/**
 * Énergie retenue pour une charge des Statistiques (issue #156) :
 *  - **AC** (ou type inconnu) : l'écart de pourcentage × capacité, comme avant. Une charge AC est
 *    longue, se fait surtout écran éteint, et la mesure côté batterie y donnerait ~10 % de moins
 *    que la wallbox.
 *  - **DC vue en entier** : l'énergie mesurée, et sa puissance moyenne (énergie ÷ durée).
 *  - **DC vue en partie** (écran éteint au verrouillage) : la mesure, puis la table pour le reste.
 *  - **DC jamais vue** : la table ; tant qu'elle est vide, cela revient au pourcentage.
 */
object ChargeEnergy {

    /** [puissanceKw] `null` : garder la puissance de la session. */
    data class Resultat(val kwh: Float?, val source: ChargeEnergySource, val puissanceKw: Float?)

    fun calculer(
        type: ChargeType?, socDebut: Float?, socFin: Float?, capaciteKwh: Float,
        observation: ChargeObservation?, tableDc: SocEnergyTable,
    ): Resultat {
        val defaut = capaciteKwh / 100f
        val pourcentage = if (socDebut != null && socFin != null && socFin > socDebut)
            ((socFin - socDebut) / 100f * capaciteKwh).roundTenth() else null
        if (type != ChargeType.DC || socFin == null) return Resultat(pourcentage, ChargeEnergySource.PERCENT, null)

        val o = observation
        if (o != null && o.energieKwh > 0f) {
            val heures = o.dureeMs / 3_600_000f
            val puissance = if (heures > 0f) o.energieKwh / heures else null
            if (o.complete) return Resultat(o.energieKwh.roundTenth(), ChargeEnergySource.MEASURED, puissance?.roundTenth())
            val depuis = o.socFinVue ?: o.socDebut ?: socDebut
            if (depuis != null) {
                val reste = tableDc.estimer(depuis, socFin, defaut).kwh
                return Resultat((o.energieKwh + reste).roundTenth(), ChargeEnergySource.MIXED, puissance?.roundTenth())
            }
        }
        val debut = o?.socDebut ?: socDebut ?: return Resultat(pourcentage, ChargeEnergySource.PERCENT, null)
        val e = tableDc.estimer(debut, socFin, defaut)
        if (e.tranches == 0) return Resultat(pourcentage, ChargeEnergySource.PERCENT, null)
        return Resultat(e.kwh.roundTenth(),
            if (e.tranchesApprises > 0) ChargeEnergySource.LEARNED else ChargeEnergySource.PERCENT, null)
    }
}
