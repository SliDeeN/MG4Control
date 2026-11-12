package com.mg4.control.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Un bloc d'information de l'écran conducteur (issue #229, 2026-11-12, maquette validée).
 * Tout est facultatif, comme [ClusterConfig] : un champ absent vaut son défaut.
 *
 * @param info     clé de [ClusterInfo] (moyenne du trajet, puissance…) ou de [BubbleInfo] (signaux)
 * @param style    [ClusterInfo.STYLE_VALUE], [ClusterInfo.STYLE_LABEL], [ClusterInfo.STYLE_ICON] ou
 *                 [ClusterInfo.STYLE_PILL] (profil et mode de conduite seulement)
 * @param size     [ClusterInfo.SIZE_S], [ClusterInfo.SIZE_M] ou [ClusterInfo.SIZE_L]
 * @param unit     unité propre à l'information (voir [ClusterInfo.units])
 * @param distance km, miles ou « auto » (le réglage général) pour ce qui se compte en distance
 * @param wide     toute la largeur de sa rangée (case de gauche seulement, voisine libre)
 */
data class ClusterBlock(
    val info: String? = null,
    val style: String? = null,
    val size: String? = null,
    val unit: String? = null,
    val distance: String? = null,
    val wide: Boolean? = null,
)

/**
 * Disposition du module de l'écran conducteur (issue #229) : la jauge en haut, au milieu ou en bas,
 * et quatre cases d'information sur deux rangées — Info 1 et 2, puis Info 3 et 4 —, plus l'allure
 * de la jauge. `null` dans [ClusterConfig] : la disposition d'avant ([ClusterLayouts.defaults]).
 */
data class ClusterLayout(
    /** [ClusterLayouts.GAUGE_TOP], [ClusterLayouts.GAUGE_MIDDLE] ou [ClusterLayouts.GAUGE_BOTTOM]. */
    val gauge: String? = null,
    /** Les quatre cases, dans l'ordre Info 1 à 4 ; `null` = case libre. */
    val slots: List<ClusterBlock?>? = null,
    /** Couleur de la consommation (ARGB), `null` = l'orange d'origine. */
    val consumptionColor: Int? = null,
    /** Couleur de la récupération (ARGB), `null` = le bleu d'origine. */
    val regenColor: Int? = null,
    /** [ClusterLayouts.THIN], [ClusterLayouts.NORMAL] ou [ClusterLayouts.THICK]. */
    val thickness: String? = null,
    /** Repère de la moyenne du trajet sur la jauge de consommation ; `null` = comme avant (style discret seul). */
    val averageCursor: Boolean? = null,
    /** Jauge de consommation : les kW sous l'unité. */
    val consumptionKw: Boolean? = null,
    /** Jauge de puissance : la consommation sous les kW ; `null` = affichée, comme avant. */
    val powerConsumption: Boolean? = null,
    /** Unité de puissance de la jauge (2026-11-12) : [ClusterInfo.UNIT_HP] = chevaux ; `null` = kW. */
    val powerUnit: String? = null,
)

object ClusterLayouts {

    const val GAUGE_TOP = "top"
    const val GAUGE_MIDDLE = "middle"
    const val GAUGE_BOTTOM = "bottom"

    const val THIN = "thin"
    const val NORMAL = "normal"
    const val THICK = "thick"

    const val SLOTS = 4

    /** Le carré du combiné, en px, comme [ClusterGauge.SIDE_PX]. */
    const val SIDE = 260f
    /** Hauteur d'une rangée d'informations : la pastille et le pied d'avant y tiennent. */
    const val ROW = 40f
    /** Marge des cases sur les côtés, et entre deux cases d'une rangée. */
    const val MARGIN = 4f

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + w && py >= y && py <= y + h
    }

    /** Une case posée : son rang (0 à 3) et sa place. */
    data class Cell(val slot: Int, val box: Box)

    data class Geometry(val gauge: Box, val cells: List<Cell>)

    fun gaugePosition(l: ClusterLayout): String =
        l.gauge?.takeIf { it == GAUGE_TOP || it == GAUGE_BOTTOM } ?: GAUGE_MIDDLE

    fun slots(l: ClusterLayout): List<ClusterBlock?> {
        val s = l.slots.orEmpty().map { b -> b?.takeIf { it.info != null } }
        return List(SLOTS) { s.getOrNull(it) }
    }

    fun rowUsed(l: ClusterLayout, row: Int): Boolean = slots(l).let { it[row * 2] != null || it[row * 2 + 1] != null }

    /** Toute la largeur, et permis : case de gauche, voisine libre. */
    fun isWide(l: ClusterLayout, slot: Int): Boolean {
        val s = slots(l)
        return slot % 2 == 0 && s[slot]?.wide == true && s[slot + 1] == null
    }

    /**
     * Places de la jauge et des cases dans le carré. Une rangée vide disparaît, la jauge prend sa
     * place (décision du 2026-11-12) ; [showEmpty] les garde, pour l'édition où l'on doit pouvoir y
     * ajouter une information.
     */
    fun geometry(l: ClusterLayout, showEmpty: Boolean = false): Geometry {
        val rangees = (0..1).filter { showEmpty || rowUsed(l, it) }
        val hauteurRangees = rangees.size * ROW
        val yRangee = mutableMapOf<Int, Float>()
        val gauge: Box
        when (gaugePosition(l)) {
            GAUGE_TOP -> {
                gauge = Box(0f, 0f, SIDE, SIDE - hauteurRangees)
                rangees.forEachIndexed { i, r -> yRangee[r] = SIDE - hauteurRangees + i * ROW }
            }
            GAUGE_BOTTOM -> {
                rangees.forEachIndexed { i, r -> yRangee[r] = i * ROW }
                gauge = Box(0f, hauteurRangees, SIDE, SIDE - hauteurRangees)
            }
            else -> {
                if (0 in rangees) yRangee[0] = 0f
                if (1 in rangees) yRangee[1] = SIDE - ROW
                val haut = if (0 in rangees) ROW else 0f
                gauge = Box(0f, haut, SIDE, SIDE - hauteurRangees)
            }
        }
        val demi = (SIDE - 3 * MARGIN) / 2f
        val cells = mutableListOf<Cell>()
        rangees.forEach { r ->
            val y = yRangee.getValue(r)
            if (isWide(l, r * 2)) {
                cells += Cell(r * 2, Box(MARGIN, y, SIDE - 2 * MARGIN, ROW))
            } else {
                cells += Cell(r * 2, Box(MARGIN, y, demi, ROW))
                cells += Cell(r * 2 + 1, Box(2 * MARGIN + demi, y, demi, ROW))
            }
        }
        return Geometry(gauge, cells)
    }

    /** La case sous le point ([x], [y] dans le carré), `null` hors des cases. */
    fun cellAt(l: ClusterLayout, x: Float, y: Float, showEmpty: Boolean = true): Int? =
        geometry(l, showEmpty).cells.firstOrNull { it.box.contains(x, y) }?.slot

    /** Position de la jauge déposée à la hauteur [y] : tiers du haut, du milieu ou du bas. */
    fun gaugeDrop(y: Float): String = when {
        y < SIDE / 3f -> GAUGE_TOP
        y > SIDE * 2f / 3f -> GAUGE_BOTTOM
        else -> GAUGE_MIDDLE
    }

    /** [l] avec [b] dans la case [slot] ; « toute la largeur » retombe si elle n'est plus permise. */
    fun withSlot(l: ClusterLayout, slot: Int, b: ClusterBlock?): ClusterLayout {
        val s = slots(l).toMutableList()
        s[slot] = b?.takeIf { it.info != null }
        return l.copy(slots = tidy(s))
    }

    /** Échange deux cases (glisser-déposer). */
    fun swap(l: ClusterLayout, a: Int, b: Int): ClusterLayout {
        if (a == b || a !in 0 until SLOTS || b !in 0 until SLOTS) return l
        val s = slots(l).toMutableList()
        val x = s[a]
        s[a] = s[b]
        s[b] = x
        return l.copy(slots = tidy(s))
    }

    /** « Toute la largeur » seulement sur une case de gauche dont la voisine est libre. */
    private fun tidy(s: List<ClusterBlock?>): List<ClusterBlock?> = s.mapIndexed { i, b ->
        if (b?.wide == true && (i % 2 == 1 || s.getOrNull(i + 1) != null)) b.copy(wide = null) else b
    }

    /**
     * Disposition d'avant l'issue #229, tirée des réglages de la carte : la pastille en haut à
     * gauche (profil, mode, ou rien), la moyenne du trajet et la récupération en bas, la jauge au
     * milieu. Rien ne change pour qui n'ouvre pas la personnalisation.
     */
    fun defaults(c: ClusterConfig): ClusterLayout {
        val pastille = when (ClusterPill.fromKey(c.pill)) {
            ClusterPill.PROFILE -> ClusterBlock(RuleSignal.PROFILE.name, ClusterInfo.STYLE_PILL)
            ClusterPill.DRIVE_MODE -> ClusterBlock(RuleSignal.DRIVE_MODE.name, ClusterInfo.STYLE_PILL)
            ClusterPill.NONE -> null
        }
        val moyenne = AverageUnit.resolve(c.average, ConsumptionUnit.fromKey(c.unit))
        return ClusterLayout(
            gauge = GAUGE_MIDDLE,
            slots = listOf(
                pastille, null,
                ClusterBlock(ClusterInfo.TRIP_AVERAGE, ClusterInfo.STYLE_LABEL, ClusterInfo.SIZE_M, moyenne.key),
                ClusterBlock(ClusterInfo.TRIP_REGEN, ClusterInfo.STYLE_LABEL, ClusterInfo.SIZE_M, ClusterInfo.UNIT_WH),
            ),
        )
    }

    /** La disposition enregistrée, sinon celle d'avant. */
    fun of(c: ClusterConfig): ClusterLayout = c.layout ?: defaults(c)

    fun thicknessFactor(l: ClusterLayout): Float = when (l.thickness) {
        THIN -> 0.6f
        THICK -> 1.45f
        else -> 1f
    }

    /** Repère de la moyenne : choisi, sinon comme avant — sur la barre discrète seulement. */
    fun averageCursor(l: ClusterLayout, style: ClusterGaugeStyle): Boolean =
        l.averageCursor ?: (style == ClusterGaugeStyle.DISCREET)

    fun powerConsumption(l: ClusterLayout): Boolean = l.powerConsumption != false

    /** La jauge compte-t-elle en chevaux ? */
    fun horsepower(l: ClusterLayout?): Boolean = l?.powerUnit == ClusterInfo.UNIT_HP
}

/**
 * Les informations d'un bloc de l'écran conducteur (issue #229) : celles du combiné (le trajet, la
 * puissance), puis toutes celles des bulles d'information ([BubbleInfo]). Ici ce qui se décide
 * sans Android : choix, unités, chiffres.
 */
object ClusterInfo {

    const val TRIP_AVERAGE = "TRIP_AVERAGE"
    const val TRIP_DISTANCE = "TRIP_DISTANCE"
    const val TRIP_ENERGY = "TRIP_ENERGY"
    const val TRIP_REGEN = "TRIP_REGEN"
    const val POWER = "POWER"
    val CLUSTER = listOf(TRIP_AVERAGE, TRIP_DISTANCE, TRIP_ENERGY, TRIP_REGEN, POWER)

    const val STYLE_VALUE = "value"
    const val STYLE_LABEL = "label"
    const val STYLE_ICON = "icon"
    const val STYLE_PILL = "pill"

    const val SIZE_S = "S"
    const val SIZE_M = "M"
    const val SIZE_L = "L"

    const val UNIT_KWH = "kwh"
    const val UNIT_WH = "wh"
    const val UNIT_KW = "kw"
    const val UNIT_HP = "hp"

    /** 1 kW = 1,36 ch (cheval-vapeur métrique), comme le demande l'issue. */
    const val HP_PER_KW = 1.36f

    /**
     * Retirées de l'écran conducteur à sa demande (2026-11-12) : projection, média en lecture,
     * appel en cours. Les bulles d'information les gardent.
     */
    private val HORS_COMBINE = setOf(RuleSignal.PROJECTION.name, RuleSignal.MEDIA_PLAYING.name, RuleSignal.IN_CALL.name)

    /** Les choix : le combiné d'abord, puis les bulles ([available] écarte ce que la voiture ne lit pas). */
    fun choices(available: (RuleSignal) -> Boolean = { true }): List<String> =
        CLUSTER + BubbleInfo.choices(available).filter { it !in HORS_COMBINE }

    fun known(key: String?): Boolean = key in CLUSTER || key == BubbleInfo.TIME || BubbleInfo.signal(key) != null

    /** La pastille de couleur n'a de sens que pour le profil et le mode de conduite. */
    fun pillAllowed(key: String?): Boolean = key == RuleSignal.PROFILE.name || key == RuleSignal.DRIVE_MODE.name

    fun styles(key: String?): List<String> =
        if (pillAllowed(key)) listOf(STYLE_PILL, STYLE_VALUE, STYLE_ICON) else listOf(STYLE_VALUE, STYLE_LABEL, STYLE_ICON)

    fun style(b: ClusterBlock): String = b.style?.takeIf { it in styles(b.info) } ?: styles(b.info).let {
        if (pillAllowed(b.info)) STYLE_PILL else STYLE_LABEL
    }

    fun size(b: ClusterBlock): String = b.size?.takeIf { it == SIZE_S || it == SIZE_L } ?: SIZE_M

    /** Unités au choix pour [key] (vide : rien à choisir). */
    fun units(key: String?): List<String> = when (key) {
        TRIP_AVERAGE -> AverageUnit.entries.map { it.key }
        TRIP_ENERGY, TRIP_REGEN -> listOf(UNIT_KWH, UNIT_WH)
        POWER -> listOf(UNIT_KW, UNIT_HP)
        else -> emptyList()
    }

    /** Ce qui se compte en distance : km, miles, ou le réglage général. */
    fun hasDistance(key: String?): Boolean =
        key == TRIP_AVERAGE || key == TRIP_DISTANCE || key == RuleSignal.SPEED.name || key == RuleSignal.RANGE.name

    fun unit(b: ClusterBlock): String? = units(b.info).let { u -> b.unit?.takeIf { it in u } ?: u.firstOrNull() }

    /** Distance du bloc : son choix, sinon [general] (le réglage de l'appli). */
    fun distance(b: ClusterBlock, general: DistanceUnit): DistanceUnit =
        DistanceUnit.entries.firstOrNull { it.key == b.distance } ?: general

    /** Le bloc par défaut quand on choisit [key] dans une case : son style, sa taille, sa première unité. */
    fun newBlock(key: String, wide: Boolean? = null): ClusterBlock = ClusterBlock(
        info = key,
        style = if (pillAllowed(key)) STYLE_PILL else STYLE_LABEL,
        size = SIZE_M,
        unit = units(key).firstOrNull(),
        wide = wide,
    )

    /**
     * Ce que lisent les infos du combiné à un instant : le trajet depuis READY et la puissance.
     * Tout en km, kWh et kW ; `null` = illisible ou pas encore de sens.
     */
    data class Trip(
        val averageWhPerKm: Float?,
        val distanceKm: Float?,
        val consumedKwh: Float?,
        val regenWh: Float?,
        val powerKw: Float?,
    )

    /**
     * Chiffre et unité d'une info du combiné ([CLUSTER]), `null` si [b] n'en est pas une.
     * Un tiret pour ce qui n'a pas encore de valeur : jamais un chiffre inventé.
     */
    fun tripValue(b: ClusterBlock, t: Trip, general: DistanceUnit, locale: Locale): Pair<String, String?>? {
        val d = distance(b, general)
        return when (b.info) {
            TRIP_AVERAGE -> {
                val u = AverageUnit.entries.firstOrNull { it.key == unit(b) } ?: AverageUnit.KWH_PER_100KM
                t.averageWhPerKm?.let { ClusterGauge.formatAverage(it, u, locale, d) }?.let { it to u.labelFor(d) }
                    ?: (BubbleInfo.UNKNOWN to null)
            }
            TRIP_DISTANCE -> t.distanceKm?.let { decimal(d.fromKm(it), locale) to d.short } ?: (BubbleInfo.UNKNOWN to null)
            TRIP_ENERGY -> energy(t.consumedKwh, unit(b), locale, "")
            TRIP_REGEN -> energy(t.regenWh?.div(1000f), unit(b), locale, "+")
            POWER -> t.powerKw?.let { kw ->
                // L'unité « ch » se traduit (PS, CV, hk…) : l'appelant remplace [UNIT_HP] par son mot.
                if (unit(b) == UNIT_HP) (kw * HP_PER_KW).roundToInt().toString() to UNIT_HP
                else ClusterGauge.formatKw(kw, locale) to "kW"
            } ?: (BubbleInfo.UNKNOWN to null)
            else -> null
        }
    }

    private fun energy(kwh: Float?, unit: String?, locale: Locale, signe: String): Pair<String, String?> {
        kwh ?: return BubbleInfo.UNKNOWN to null
        return if (unit == UNIT_WH) signe + kwh.times(1000f).roundToInt() to "Wh"
        else signe + String.format(locale, "%.2f", kwh) to "kWh"
    }

    /** Une décimale sous 100, l'entier au-delà : la place est comptée sur le combiné. */
    fun decimal(v: Float, locale: Locale): String =
        if (v < 100f) String.format(locale, "%.1f", v) else v.roundToInt().toString()
}
