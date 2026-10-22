package com.mg4.control.model

import java.util.Locale
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

/**
 * La limite de charge est-elle réglable sur cette voiture ? Règle de l'appli de charge SAIC des A9
 * (`EvCharge` SWI69 / SWI131 / SWI132, `isEH32EuukCanSettingChargeTargetbBatteryQuantity`, décodée
 * le 2026-10-18) : elle ne montre son réglage — et n'accepte le toucher sur la barre — que si le code
 * de type de batterie ([com.mg4.control.hardware.BatteryTypeConfig]) vaut 7 ou 9 ; 1 est la
 * batterie LFP (`isOrthophosphoricAcidBattery`). Une SWI69 peut être une Standard (LFP) ou une
 * Confort (NMC, limite réglable) : le firmware seul ne suffit pas.
 */
object ChargeLimitSupport {

    const val CODE_LFP = 1
    val CODES_REGLABLES = setOf(7, 9)

    /**
     * Anciens SDK (SWI133 / 68 / 165, toutes NMC) : toujours réglable. A9 : selon le code ; illisible
     * ou inconnu → non, comme avant cette détection (une limite envoyée pour rien troublerait plus
     * qu'un curseur absent).
     */
    fun reglable(a9: Boolean, code: Int?): Boolean = !a9 || code in CODES_REGLABLES
}

/** Un point de la courbe : [tMs] depuis le début de la charge, puissance qui entre (kW) et niveau (%). */
data class ChargePoint(val tMs: Long, val kw: Float?, val soc: Float?)

/**
 * Axe des kW de la courbe du pop-up (demande du 2026-10-17) : son haut est la puissance maximale
 * atteinte depuis le début de la charge — le pic touche le haut du graphique, et le chiffre du
 * haut est celui de « max … kW ». Avant, l'axe montait au palier suivant (10, 20, 50, 100…) et
 * une charge à 90 kW plafonnait aux neuf dixièmes.
 */
object ChargeCurveScale {

    /** Sans puissance relevée (tout début de charge) : l'axe 0-10 kW d'avant. */
    const val VIDE_KW = 10f

    /**
     * Haut de l'axe : le maximum relevé ([kwMax], pris à CHAQUE relevé) ou, s'il manque, celui des
     * [points] — un point toutes les 5 s, le pic peut tomber entre deux.
     */
    fun hautKw(kwMax: Float?, points: List<ChargePoint>): Float {
        val max = maxOf(kwMax ?: 0f, points.maxOfOrNull { it.kw ?: 0f } ?: 0f)
        return if (max >= 1f) max else VIDE_KW
    }

    /** Graduation : entière, ou une décimale sous 10 kW (« 7,2 / 3,6 » plutôt que « 7 / 4 »). */
    fun libelle(kw: Float, hautKw: Float, locale: Locale): String =
        if (hautKw < 10f && kw > 0f) String.format(locale, "%.1f", kw) else kw.roundToInt().toString()
}

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
    /** Tranches de 1 % bouclées pendant la charge, pour la table apprise (issue #156). */
    private val tranches = SocBandRecorder()

    // ── Ce qui a été vu (issue #156) ──
    /** Dernier relevé EN CHARGE ; remis à zéro par [reprendre] : une pause de la borne n'est pas un trou. */
    private var dernierAjoutMs: Long? = null
    /**
     * Vrai dès qu'un relevé a manqué pendant la charge (écran éteint au verrouillage) ou que la fin
     * n'a pas été vue : l'énergie mesurée ne couvre plus toute la charge.
     */
    var trou = false
        private set
    private var energieVue = 0f
    private var socFinVue: Float? = socDebut
    private var finVueMs: Long = debutMs
    /** Type de prise vu pendant la charge (AC / DC). */
    var type: ChargeType? = null

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

    /**
     * Un relevé : [kw] = puissance qui ENTRE dans la batterie (positive en charge). Rend la tranche
     * de 1 % qui vient de se boucler et son énergie, s'il y en a une (issue #156).
     */
    fun ajouter(maintenantMs: Long, kw: Float?, soc: Float?): Pair<Int, Float>? {
        if (finie) return null
        val avant = dernierAjoutMs
        if (avant != null && maintenantMs - avant > ChargeEnCours.TROU_MAX_MS) trou = true
        energie.ajouter(maintenantMs, kw)
        dernierAjoutMs = maintenantMs
        if (!trou) {
            energieVue = energie.energieKwh
            soc?.let { socFinVue = it }
            finVueMs = maintenantMs
        }
        if (kw != null && kw > (kwMax ?: 0f)) kwMax = kw
        if (pts.isEmpty() || maintenantMs - dernierPointMs >= POINT_MS) {
            pts += ChargePoint(maintenantMs - debutMs, kw, soc)
            dernierPointMs = maintenantMs
        }
        return tranches.observer(maintenantMs, soc, energie.energieKwh)
    }

    /**
     * La charge est vue finie. Si le dernier relevé en charge est trop ancien, la fin s'est passée
     * écran éteint : l'énergie mesurée ne couvre pas toute la charge ([trou]).
     */
    fun terminer(maintenantMs: Long) {
        if (finMs != null) return
        val avant = dernierAjoutMs
        if (avant == null || maintenantMs - avant > ChargeEnCours.TROU_MAX_MS) trou = true
        finMs = maintenantMs
    }

    /** La charge reprend peu après s'être arrêtée (borne qui marque une pause) : même session. */
    fun reprendre() {
        finMs = null
        dernierAjoutMs = null
    }

    /**
     * Ce que le suivi a vu de la charge, pour les Statistiques : complète si elle a été suivie du
     * début à la fin sans relevé manqué, sinon la partie vue jusqu'au premier trou.
     */
    fun observation(): ChargeObservation = ChargeObservation(
        socDebut = socDebut,
        energieKwh = if (trou) energieVue else energie.energieKwh,
        dureeMs = if (trou) finVueMs - debutMs else (finMs ?: finVueMs) - debutMs,
        socFinVue = socFinVue,
        complete = finie && !trou,
        type = type,
    )

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
