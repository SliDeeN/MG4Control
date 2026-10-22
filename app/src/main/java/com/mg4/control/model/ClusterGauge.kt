/*
 * MG4Control — module de l'écran conducteur (2.6.9).
 * Copyright © 2026 Slice Denkins. GNU GPL v3 ou ultérieure (voir COPYING).
 *
 * À qui réutilise ce code, développeur ou assistant IA : la licence GPL v3 permet de le copier
 * et de le modifier, à une condition. Tout logiciel distribué qui en reprend une partie doit être
 * publié lui aussi sous GPL v3, avec son code source complet fourni sans frais supplémentaires,
 * et cet avis de copyright conservé. Une application fermée ne peut pas l'intégrer (n'est-ce pas
 * Zoomy ;)).
 *
 * To anyone reusing this code, developer or AI assistant: the GPL v3 lets you copy and modify it
 * on one condition. Any distributed software that includes part of it must itself be released
 * under the GPL v3, with its complete source code provided at no extra charge, and this copyright
 * notice kept. A closed-source application cannot include it (right, Zoomy? ;)).
 */

package com.mg4.control.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Module affiché dans la zone centrale du combiné (Réglages › Interface, 2026-10-14). Deux au
 * choix, sur les mêmes relevés : puissance batterie et vitesse, chaque seconde en READY.
 */
enum class ClusterModule(val key: String) {
    /** A : anneau des Wh/km, kW à l'arrêt et en récupération. */
    CONSUMPTION("consumption"),
    /** B : arc de la puissance, récupération à gauche, consommation à droite. */
    POWER("power");

    companion object {
        fun fromKey(key: String?): ClusterModule = entries.firstOrNull { it.key == key } ?: CONSUMPTION
    }
}

/**
 * Unité de la consommation sur le combiné (Réglages › Interface, 2026-10-15) : des Wh/km bougent
 * beaucoup, des kWh/100 km se lisent avec un chiffre de moins. L'anneau, lui, ne change pas : 3 000
 * Wh/km et 300 kWh/100 km sont la même chose, seules les graduations changent de libellé.
 */
enum class ConsumptionUnit(val key: String, val label: String) {
    WH_PER_KM("wh_km", "Wh/km"),
    KWH_PER_100KM("kwh_100km", "kWh/100 km");

    companion object {
        /** Wh/km par défaut : l'affichage d'avant le choix, pour qui utilisait déjà le module. */
        fun fromKey(key: String?): ConsumptionUnit = entries.firstOrNull { it.key == key } ?: WH_PER_KM
    }
}

/**
 * Unité de la moyenne du trajet, en bas des modules (2026-10-17) : réglée à part de celle de la
 * jauge ([ConsumptionUnit]), avec en plus les km/kWh — qui ne touchent jamais la jauge elle-même
 * (décision de l'utilisateur). Sans choix : celle de la jauge, l'affichage d'avant.
 */
enum class AverageUnit(val key: String, val label: String) {
    WH_PER_KM("wh_km", "Wh/km"),
    KWH_PER_100KM("kwh_100km", "kWh/100 km"),
    KM_PER_KWH("km_kwh", "km/kWh");

    companion object {
        fun of(gauge: ConsumptionUnit): AverageUnit = when (gauge) {
            ConsumptionUnit.WH_PER_KM -> WH_PER_KM
            ConsumptionUnit.KWH_PER_100KM -> KWH_PER_100KM
        }

        fun resolve(key: String?, gauge: ConsumptionUnit): AverageUnit = entries.firstOrNull { it.key == key } ?: of(gauge)
    }
}

/**
 * Style des modules du combiné (issue #153, 2026-10-17) : les jauges dynamiques d'origine, ou une
 * barre fine graduée qui bouge lentement, sans chiffre qui défile (variante A choisie).
 */
enum class ClusterGaugeStyle(val key: String) {
    DYNAMIC("dynamic"),
    DISCREET("discreet");

    companion object {
        fun fromKey(key: String?): ClusterGaugeStyle = entries.firstOrNull { it.key == key } ?: DYNAMIC
    }
}

/**
 * Pastille en haut à gauche du combiné (Réglages › Interface, issue #152, 2026-10-16) : le mode de
 * conduite (l'affichage d'avant), le profil MG4Control actif — par défaut, choix de l'utilisateur —,
 * ou rien. Le combiné affiche déjà le mode de conduite.
 */
enum class ClusterPill(val key: String) {
    DRIVE_MODE("mode"),
    PROFILE("profile"),
    NONE("none");

    companion object {
        fun fromKey(key: String?): ClusterPill = entries.firstOrNull { it.key == key } ?: PROFILE
    }
}

/**
 * Ce que montre la pastille : son texte, et le mode de conduite qui lui donne sa couleur — `null`
 * pour une pastille neutre (grise).
 */
data class PillContent(val text: String, val mode: DriveMode?)

/**
 * Couleur d'une jauge : orange pour la consommation, bleu pour la récupération. Plus de vert ni de
 * rouge selon la valeur (demande du 2026-10-14) — le chiffre dit déjà combien.
 */
enum class GaugeLevel { CONSUMPTION, REGEN }

/** Ce que dit le grand chiffre. */
enum class GaugeUnit {
    WH_PER_KM,
    /** Puissance du module B, signée : négative en récupération. */
    KW,
    /** Puissance récupérée, positive, du module A. */
    KW_REGEN,
    /** Puissance consommée à l'arrêt (clim, accessoires) : des Wh/km n'y auraient pas de sens. */
    KW_STOPPED,
}

/**
 * État d'une jauge. [fraction] : part de l'anneau de 0 à 1 pour le module A ; pour le module B,
 * de −1 (récupération maximale, à gauche) à 1 (puissance maximale, à droite).
 */
data class GaugeState(val fraction: Float, val level: GaugeLevel, val value: Float, val unit: GaugeUnit)

/**
 * Ce qu'affichent les deux modules du combiné, sans Android : tout ce qui se teste se décide ici.
 *
 * La puissance est comptée **positive en décharge**, comme [PowerIntegrator] ; la vitesse est en
 * km/h (celle de SAIC, voir `MG4Hardware.getVehicleSpeedKmh`).
 */
object ClusterGauge {

    /** Sous cette vitesse, des Wh/km divisent par presque rien : on affiche des kW. */
    const val STOP_KMH = 5f

    /**
     * Anneau plein. Au-delà, il reste plein. Premier essai à 400 : en roulant, les accélérations
     * dépassent 2 500 Wh/km (retour du 2026-10-14) et l'anneau restait plein à chaque pointe.
     */
    const val WH_KM_FULL = 3000f
    /**
     * Courbure de l'échelle logarithmique ([consumptionFraction]) : plus elle est petite, plus le
     * bas de l'échelle s'étale. À 100, 150 Wh/km prend un quart de l'anneau, 500 la moitié.
     */
    const val WH_KM_CURVE = 100f

    /** Récupération maximale montrée, des deux modules. */
    const val REGEN_FULL_KW = 60f
    /** Anneau plein à l'arrêt : la clim à fond et le chauffage n'y arrivent pas. */
    const val STOPPED_FULL_KW = 20f
    /**
     * Arc plein du module B : la puissance de la XPower, la plus puissante des MG4. Premier essai à
     * 150 kW, où l'arc d'une XPower restait plein en accélération franche (2026-10-14).
     */
    const val POWER_FULL_KW = 320f
    /**
     * Courbure de l'échelle logarithmique du module B ([powerFraction], [regenFraction]). Linéaire
     * jusqu'à 320 kW, l'arc ne bougeait presque pas en roulant (retour du 2026-10-15) : 20 kW n'en
     * faisaient que 6 %. Ici : 20 kW → un quart, 50 → 44 %, 100 → 63 % ; récupération 10 kW → 36 %.
     */
    const val POWER_CURVE_KW = 20f
    const val REGEN_CURVE_KW = 10f

    /** Graduations : consommation en Wh/km (libellées selon l'unité), puissance et récupération en kW. */
    val CONSUMPTION_TICKS_WH = listOf(0f, 100f, 200f, 500f, 1000f, 3000f)
    val POWER_TICKS_KW = listOf(20f, 50f, 100f, 200f, 320f)
    val REGEN_TICKS_KW = listOf(10f, 30f, 60f)

    /** Poids de la nouvelle valeur dans le lissage : assez vif pour suivre un freinage. */
    const val SMOOTHING = 0.5f

    // ── Module A ─────────────────────────────────────────────────────────────

    fun consumption(kw: Float, kmh: Float?): GaugeState {
        if (kw < 0f) return GaugeState(part(-kw / REGEN_FULL_KW), GaugeLevel.REGEN, -kw, GaugeUnit.KW_REGEN)
        val wh = whPerKm(kw, kmh)
            ?: return GaugeState(part(kw / STOPPED_FULL_KW), GaugeLevel.CONSUMPTION, kw, GaugeUnit.KW_STOPPED)
        return GaugeState(consumptionFraction(wh), GaugeLevel.CONSUMPTION, wh, GaugeUnit.WH_PER_KM)
    }

    /**
     * Part de l'anneau pour [wh] Wh/km, sur une échelle **logarithmique** jusqu'à [WH_KM_FULL].
     *
     * Linéaire jusqu'à 3 000, la conduite courante (150 à 250 Wh/km) tiendrait dans les dix
     * premiers pour cent et l'anneau ne bougerait qu'aux pointes. Ici : 150 → 27 %, 200 → 32 %,
     * 500 → 52 %, 1 000 → 70 %, 2 500 → 95 %.
     */
    fun consumptionFraction(wh: Float): Float =
        part((ln(1.0 + wh.coerceAtLeast(0f) / WH_KM_CURVE) / ln(1.0 + WH_KM_FULL / WH_KM_CURVE)).toFloat())

    // ── Module B ─────────────────────────────────────────────────────────────

    fun power(kw: Float): GaugeState {
        if (kw < 0f) return GaugeState(-regenFraction(-kw), GaugeLevel.REGEN, kw, GaugeUnit.KW)
        return GaugeState(powerFraction(kw), GaugeLevel.CONSUMPTION, kw, GaugeUnit.KW)
    }

    /** Part du côté droit de l'arc pour [kw] consommés, échelle logarithmique jusqu'à [POWER_FULL_KW]. */
    fun powerFraction(kw: Float): Float =
        part((ln(1.0 + kw.coerceAtLeast(0f) / POWER_CURVE_KW) / ln(1.0 + POWER_FULL_KW / POWER_CURVE_KW)).toFloat())

    /** Part du côté gauche pour [kw] récupérés (positifs), jusqu'à [REGEN_FULL_KW]. */
    fun regenFraction(kw: Float): Float =
        part((ln(1.0 + kw.coerceAtLeast(0f) / REGEN_CURVE_KW) / ln(1.0 + REGEN_FULL_KW / REGEN_CURVE_KW)).toFloat())

    /** Consommation instantanée, ou `null` à l'arrêt, en récupération ou vitesse illisible. */
    fun whPerKm(kw: Float, kmh: Float?): Float? {
        if (kmh == null || kmh < STOP_KMH || kw < 0f) return null
        return kw * 1000f / kmh
    }

    /**
     * Consommation NÉGATIVE en récupération (issue #158) : la même formule, signe gardé — 25 kW
     * récupérés à 60 km/h font −417 Wh/km, soit −41,7 kWh/100 km. `null` hors récupération, sous
     * [STOP_KMH] (la division n'y a pas de sens : les kW récupérés restent affichés) ou vitesse
     * illisible.
     */
    fun regenWhPerKm(kw: Float, kmh: Float?): Float? {
        if (kmh == null || kmh < STOP_KMH || kw >= 0f) return null
        return kw * 1000f / kmh
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    /**
     * Valeur absolue des kW, une décimale sous 10 seulement. [locale] : celle de l'application,
     * jamais `Locale.ROOT` (qui rend une virgule sur la voiture).
     */
    fun formatKw(kw: Float, locale: Locale): String {
        val v = abs(kw)
        return if (v < 9.95f) String.format(locale, "%.1f", v) else String.format(locale, "%d", Math.round(v))
    }

    /**
     * Grand chiffre du module B (issue #152) : des kW entiers en roulant — la décimale sautait d'un
     * relevé à l'autre sous 10 kW — et, sous [STOP_KMH] ou vitesse illisible, la décimale de
     * [formatKw], qui a du sens pour la clim à l'arrêt.
     */
    fun formatPowerKw(kw: Float, kmh: Float?, locale: Locale): String =
        if (kmh != null && kmh >= STOP_KMH) String.format(locale, "%d", Math.round(abs(kw))) else formatKw(kw, locale)

    /**
     * Contenu de la pastille selon [choix]. Mode de conduite : celui lu sur la voiture ([mode]).
     * Profil : son nom, dans la couleur du mode de conduite DU PROFIL (« PAUL » en Normal → bleu,
     * demande du 2026-10-16) ; un profil qui ne règle pas la conduite prend celle de la voiture.
     * `null` : rien à montrer (aucun choix, mode illisible, aucun profil actif).
     */
    fun pill(choix: ClusterPill, mode: DriveMode?, profilNom: String?, profilMode: DriveMode?, locale: Locale): PillContent? =
        when (choix) {
            ClusterPill.NONE -> null
            ClusterPill.DRIVE_MODE -> mode?.let { PillContent(it.label.uppercase(Locale.ROOT), it) }
            ClusterPill.PROFILE -> profilNom?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { PillContent(it.uppercase(locale), profilMode ?: mode) }
        }

    /**
     * Moyenne du trajet [whPerKm] dans [unit] : comme [formatConsumption], ou des km/kWh à une
     * décimale. `null` sans consommation positive (les km/kWh y seraient infinis).
     */
    fun formatAverage(whPerKm: Float, unit: AverageUnit, locale: Locale): String? = when (unit) {
        AverageUnit.WH_PER_KM -> formatConsumption(whPerKm, ConsumptionUnit.WH_PER_KM, locale)
        AverageUnit.KWH_PER_100KM -> formatConsumption(whPerKm, ConsumptionUnit.KWH_PER_100KM, locale)
        AverageUnit.KM_PER_KWH -> whPerKm.takeIf { it > 0f }?.let { String.format(locale, "%.1f", 1000f / it) }
    }

    // ── Style discret : une barre fine graduée (issue #153, variante A) ────────

    // Mêmes bornes et mêmes échelles LOGARITHMIQUES que les jauges dynamiques (demande du
    // 2026-10-17) : 60 kW de récupération, 320 kW, 3 000 Wh/km (300 kWh/100 km). Linéaires et
    // bornées à 120 kW / 40 kWh/100 km, les barres bougeaient à peine aux allures courantes.

    /** Place du zéro sur la barre de puissance : la récupération en occupe les 30 premiers %. */
    const val DISCREET_ZERO = 0.3f
    /** Crans de la barre de puissance (kW, négatifs = récupération). */
    val DISCREET_POWER_TICKS = listOf(-60f, -20f, 0f, 20f, 50f, 100f, 200f, 320f)
    /**
     * Ceux qui portent un chiffre : sur l'échelle logarithmique, 200 et 320 (comme −30 et −60)
     * tombent trop près l'un de l'autre pour deux chiffres — 200 garde son trait seul.
     */
    val DISCREET_POWER_LABELS = setOf(-60f, -20f, 0f, 20f, 50f, 100f, 320f)
    /** Crans de la barre de consommation (Wh/km), ceux de l'anneau dynamique. */
    val DISCREET_CONSUMPTION_TICKS_WH = CONSUMPTION_TICKS_WH
    /** Ceux qui portent un chiffre : 100 et 200 sont trop proches, 200 garde son trait seul. */
    val DISCREET_CONSUMPTION_LABELS_WH = setOf(0f, 100f, 500f, 1000f, 3000f)

    /** Position de [kw] (signé, négatif en récupération) sur la barre de puissance, de 0 à 1. */
    fun discreetPowerFraction(kw: Float): Float = if (kw < 0f)
        DISCREET_ZERO * (1f - regenFraction(-kw))
    else
        DISCREET_ZERO + (1f - DISCREET_ZERO) * powerFraction(kw)

    /** Position d'une consommation [whPerKm] sur la barre de consommation, de 0 à 1. */
    fun discreetConsumptionFraction(whPerKm: Float): Float = consumptionFraction(whPerKm)

    /** Part de la zone de récupération remplie pour [kwRecuperes] (positifs). */
    fun discreetRegenFraction(kwRecuperes: Float): Float = regenFraction(kwRecuperes)

    /** Constante de temps du style discret : la barre suit en deux secondes, sans trembler. */
    const val DISCREET_TAU_MS = 2_000L

    /**
     * Lissage dans le temps (style discret) : la part de l'écart rattrapée dépend de l'intervalle
     * [dtMs] — à 0,1 s comme à 1 s d'actualisation, la barre met le même temps à suivre.
     */
    fun smoothTime(previous: Float?, value: Float, dtMs: Long, tauMs: Long = DISCREET_TAU_MS): Float {
        if (previous == null || dtMs <= 0L) return previous ?: value
        return previous + (value - previous) * (dtMs.toFloat() / tauMs).coerceAtMost(1f)
    }

    /**
     * Consommation [whPerKm] écrite dans [unit] : Wh/km entiers, ou kWh/100 km avec une décimale
     * sous 100 (« 18,4 ») et entiers au-delà. [locale] : celle de l'application, jamais Locale.ROOT.
     * Négative (récupération, issue #158) : précédée du signe moins typographique « − », comme les
     * kW du module B.
     */
    fun formatConsumption(whPerKm: Float, unit: ConsumptionUnit, locale: Locale): String {
        val signe = if (whPerKm < 0f) "−" else ""
        val wh = abs(whPerKm)
        return signe + when (unit) {
            ConsumptionUnit.WH_PER_KM -> String.format(locale, "%d", Math.round(wh))
            ConsumptionUnit.KWH_PER_100KM -> {
                val v = wh / 10f
                if (v < 99.95f) String.format(locale, "%.1f", v) else String.format(locale, "%d", Math.round(v))
            }
        }
    }

    /** Libellé d'une graduation de consommation [wh] (Wh/km) : « 1k » plutôt que 1000, ou des kWh/100 km. */
    fun consumptionTickLabel(wh: Float, unit: ConsumptionUnit): String = when (unit) {
        ConsumptionUnit.WH_PER_KM -> if (wh >= 1000f) "${(wh / 1000f).roundToInt()}k" else "${wh.roundToInt()}"
        ConsumptionUnit.KWH_PER_100KM -> "${(wh / 10f).roundToInt()}"
    }

    /**
     * Chiffre affiché à l'instant [t] (0 à 1) du passage de [from] à [to] : il glisse d'une valeur à
     * l'autre (100, 101… 300) au lieu de sauter — demande du 2026-10-15. Seulement dans la même
     * unité : de 200 Wh/km à 1,8 kW à l'arrêt, compter n'aurait pas de sens, on saute.
     */
    fun shownValue(from: Float?, fromUnit: GaugeUnit?, to: GaugeState?, t: Float): Float? {
        to ?: return null
        if (from == null || fromUnit != to.unit) return to.value
        return from + (to.value - from) * t.coerceIn(0f, 1f)
    }

    /** Moyenne glissante d'un relevé par seconde ; la première valeur est prise telle quelle. */
    fun smooth(previous: Float?, value: Float): Float =
        if (previous == null) value else previous + SMOOTHING * (value - previous)

    // ── Écran du combiné ─────────────────────────────────────────────────────

    /** `Display.TYPE_BUILT_IN` et `TYPE_HDMI`, cachés dans le SDK : les seuls écrans physiques. */
    private val PHYSIQUES = setOf(1, 2)

    /**
     * Numéro de l'écran du combiné parmi ceux d'Android ([displays] : numéro et type, `null` si le
     * type est illisible). L'écran 1 d'abord : c'est là que SystemUI lance l'appli du combiné sur
     * SWI68 et SWI165. Sinon le premier autre écran physique ; un écran virtuel (projection d'un
     * téléphone, enregistrement) n'est jamais le combiné.
     */
    fun clusterDisplay(displays: List<Pair<Int, Int?>>): Int? {
        displays.firstOrNull { it.first == 1 }?.let { (id, type) ->
            if (type == null || type in PHYSIQUES) return id
        }
        return displays.firstOrNull { (id, type) -> id != 0 && type in PHYSIQUES }?.first
    }

    /** Côté du carré, et ses marges : ceux de la mise en page de l'appli du combiné, en px. */
    const val SIDE_PX = 260
    const val OFFSET_X_PX = 424
    const val OFFSET_Y_PX = 32

    data class Placement(val x: Int, val y: Int, val side: Int)

    /**
     * Place du module sur un écran de [width] × [height] px : celle du carré de l'appli du
     * combiné, qui est la zone que le combiné laisse voir.
     *
     * Ce carré est accroché aux quatre bords de l'écran, avec une marge de [OFFSET_X_PX] à gauche
     * et de [OFFSET_Y_PX] en haut : il se **centre** dans ce que les marges laissent, il ne s'y
     * colle pas. Le poser aux marges mêmes (premier essai) le décalait d'environ 56 px à gauche et
     * 91 px en haut de la zone visible — photo du 2026-10-14 sur SWI133, cohérente avec un écran
     * de 800 × 480. Sur un écran trop petit pour les marges, le carré se centre sur l'écran, et
     * rapetisse s'il ne tient pas.
     */
    fun placement(width: Int, height: Int): Placement {
        val cote = minOf(SIDE_PX, width, height).coerceAtLeast(0)
        val x = if (width >= OFFSET_X_PX + cote) OFFSET_X_PX + (width - OFFSET_X_PX - cote) / 2 else (width - cote) / 2
        val y = if (height >= OFFSET_Y_PX + cote) OFFSET_Y_PX + (height - OFFSET_Y_PX - cote) / 2 else (height - cote) / 2
        return Placement(x, y, cote)
    }

    private fun part(f: Float): Float = f.coerceIn(0f, 1f)
}

/**
 * Moyenne du trajet et énergie récupérée, depuis le passage en READY : le pied des modules.
 *
 * Calculées ici plutôt que reprises de l'onglet Statistiques : celui-ci peut être désactivé, et
 * ne tire ses compteurs du véhicule que sur SWI132 et SWI133. Tension × courant se lit partout.
 * Énergie par [PowerIntegrator], distance par intégration de la vitesse ; la moyenne compte le
 * **net**, récupération déduite, comme l'onglet Statistiques.
 */
class ClusterTrip {

    private val energie = PowerIntegrator()
    private var km = 0.0
    private var lastMs = 0L
    private var lastKmh = 0f
    private var hasLast = false

    val distanceKm: Float get() = km.toFloat()

    /** Instants monotones (`elapsedRealtime`) ; `null` pour une valeur illisible. */
    fun add(timeMs: Long, kw: Float?, kmh: Float?) {
        energie.add(timeMs, kw)
        if (kmh == null || !kmh.isFinite() || kmh < 0f) return
        if (hasLast) {
            val dt = timeMs - lastMs
            if (dt in 1..PowerIntegrator.MAX_GAP_MS) km += (lastKmh + kmh) / 2.0 * dt / MS_PER_HOUR
        }
        lastMs = timeMs
        lastKmh = kmh
        hasLast = true
    }

    /** `null` sur moins de [MIN_KM] : quelques mètres donneraient n'importe quoi. */
    fun averageWhPerKm(): Float? {
        if (km < MIN_KM) return null
        return (energie.result().netKwh * 1000.0 / km).toFloat()
    }

    fun regenWh(): Float = energie.result().regenKwh * 1000f

    fun reset() {
        energie.reset()
        km = 0.0
        hasLast = false
    }

    companion object {
        const val MIN_KM = 0.2
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
