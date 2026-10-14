package com.mg4.control.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

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
        if (kw < 0f) return GaugeState(-part(-kw / REGEN_FULL_KW), GaugeLevel.REGEN, kw, GaugeUnit.KW)
        return GaugeState(part(kw / POWER_FULL_KW), GaugeLevel.CONSUMPTION, kw, GaugeUnit.KW)
    }

    /** Consommation instantanée, ou `null` à l'arrêt, en récupération ou vitesse illisible. */
    fun whPerKm(kw: Float, kmh: Float?): Float? {
        if (kmh == null || kmh < STOP_KMH || kw < 0f) return null
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
