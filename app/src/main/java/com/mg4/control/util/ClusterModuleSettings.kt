package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterPill
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.PowerScale
import kotlin.math.roundToInt

/**
 * Réglage « Module sur le combiné » (Réglages › Interface) : un interrupteur, désactivé par
 * défaut, et un module par onglet du combiné — Navigation et Musique, les seuls qui montrent
 * l'écran central (2026-10-14). Relu à chaque relevé : en changer vaut tout de suite, sans
 * réafficher la fenêtre.
 *
 * Depuis le 2026-10-24, ces réglages appartiennent à chaque profil ([ClusterConfig]) : les lectures
 * à partir d'un `Context` rendent ceux du profil que suit le combiné ([ClusterProfiles.effective]).
 * Les clés ci-dessous ne gardent que les réglages communs d'avant : repris par chaque profil à la
 * mise à jour, ils ne valent plus que tant qu'il n'existe aucun profil.
 */
object ClusterModuleSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_ENABLED = "cluster_module_enabled"
    /** Ancien réglage unique, d'avant le choix par onglet : relu pour la navigation seulement. */
    const val KEY_MODULE = "cluster_module"
    const val KEY_TAB_NAVIGATION = "cluster_module_tab_navigation"
    const val KEY_TAB_MEDIA = "cluster_module_tab_media"
    /** Valeur enregistrée pour « Aucun » : l'onglet garde sa page d'origine. */
    const val NONE = "none"

    /** Onglets qui montrent l'écran central ; les autres sont dessinés par le combiné lui-même. */
    val TABS = listOf(ClusterTab.NAVIGATION, ClusterTab.MEDIA)

    /**
     * Firmwares où le combiné montre l'image de l'écran central : SWI133 (vu en voiture le
     * 2026-10-14), SWI68 et SWI165 (même appli SAIC du combiné, lancée par SystemUI). Pas les A9 :
     * essai SWI69 du 2026-10-14, rien ne s'affiche, même en annonçant au combiné une image prête.
     */
    val SUPPORTED = setOf(FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI165)

    fun isSupported(): Boolean = supportedOn(FirmwareInfo.getGeneration())
    const val KEY_REFRESH = "cluster_refresh_ms"
    /** Unité de la consommation (2026-10-15), Wh/km par défaut. */
    const val KEY_UNIT = "cluster_consumption_unit"
    /**
     * Unité de distance de tout le module : km, miles (2026-10-23) ou « auto » = comme la voiture
     * — le défaut quand rien n'est enregistré, depuis le même jour.
     */
    const val KEY_DISTANCE = "cluster_distance_unit"

    /**
     * Actualisation de la puissance, de la vitesse et de la jauge, en ms : 0,1 à 1 s par pas de
     * 0,1 s. Une seconde par défaut (2026-10-15) : le chiffre défile maintenant d'une valeur à
     * l'autre pendant 80 % de l'intervalle, et à 1 s on le suit bien (0,5 s du 2026-10-14 au 15).
     */
    const val MIN_REFRESH_MS = 100
    const val MAX_REFRESH_MS = 1000
    const val STEP_REFRESH_MS = 100
    const val DEFAULT_REFRESH_MS = 1000

    /** Style des modules (issue #153) : dynamique par défaut, l'affichage d'avant. */
    const val KEY_STYLE = "cluster_gauge_style"
    /** Unité de la moyenne du trajet (2026-10-17) : sans choix, celle de la jauge. */
    const val KEY_AVG_UNIT = "cluster_average_unit"

    /** Pastille en haut à gauche (issue #152) : profil actif par défaut. */
    const val KEY_PILL = "cluster_pill"

    /**
     * Durée du glissement de la jauge d'une valeur à la suivante (issue #152), en ms : 0,2 à 2 s
     * par pas de 0,1 s, à part de l'actualisation. Avant, elle valait 80 % de celle-ci (50 à
     * 800 ms) : une actualisation rapide imposait une animation sèche. 0,8 s par défaut, ce que
     * donnait l'actualisation par défaut (1 s). Plus longue que l'actualisation, la jauge lisse :
     * chaque relevé repart de ce qui est à l'écran.
     */
    const val KEY_ANIMATION = "cluster_animation_ms"
    const val MIN_ANIMATION_MS = 200
    const val MAX_ANIMATION_MS = 2000
    const val STEP_ANIMATION_MS = 100
    const val DEFAULT_ANIMATION_MS = 800

    // ── Réglages du profil que suit le combiné, relus à chaque relevé ────────

    fun isEnabled(ctx: Context?): Boolean = isEnabled(ClusterProfiles.effective(ctx))

    /** Module de [tab], ou `null` pour « Aucun » et pour un onglet dessiné par le combiné. */
    fun moduleFor(ctx: Context?, tab: ClusterTab): ClusterModule? = moduleFor(ClusterProfiles.effective(ctx), tab)

    fun consumptionUnit(ctx: Context?): ConsumptionUnit = consumptionUnit(ClusterProfiles.effective(ctx))

    fun refreshMs(ctx: Context?): Int = refreshMs(ClusterProfiles.effective(ctx))

    fun style(ctx: Context?): ClusterGaugeStyle = style(ClusterProfiles.effective(ctx))

    /** Choix enregistré : km ou miles, `null` = Auto (« auto » enregistré, ou aucun choix). */
    fun distanceChoice(ctx: Context?): DistanceUnit? = distanceChoice(ClusterProfiles.effective(ctx))

    /**
     * Le choix, sinon [vehicle] — l'unité de la voiture, que l'appelant tient de
     * `VehicleDistanceUnit.connue()` — et km si elle n'est pas connue.
     */
    fun distanceUnit(ctx: Context?, vehicle: DistanceUnit?): DistanceUnit =
        distanceUnit(ClusterProfiles.effective(ctx), vehicle)

    /** Sans choix, l'unité de la jauge. */
    fun averageUnit(ctx: Context?): AverageUnit = averageUnit(ClusterProfiles.effective(ctx))

    fun pill(ctx: Context?): ClusterPill = pill(ClusterProfiles.effective(ctx))

    fun animationMs(ctx: Context?): Int = animationMs(ClusterProfiles.effective(ctx))

    // ── Fin de l'échelle de puissance (issue #165) ───────────────────────────
    // Le CHOIX appartient au profil comme le reste de la carte ; le plafond APPRIS est celui de la
    // voiture, commun à tous les profils.

    const val KEY_POWER_LEARNED = "cluster_power_learned"
    const val KEY_POWER_PEAKS = "cluster_power_peaks"

    /** Fin de l'échelle pour le profil que suit le combiné. */
    fun fullScaleKw(ctx: Context?): Float = fullScaleKw(ClusterProfiles.effective(ctx), learnedPowerKw(ctx))

    fun fullScaleKw(c: ClusterConfig, appris: Int): Float = PowerScale.pleineEchelle(powerMaxChoice(c), appris)

    /** Puissance maxi choisie, ou `null` pour Auto. */
    fun powerMaxChoice(c: ClusterConfig): Int? = c.powerMax?.takeIf { it in PowerScale.CHOIX_KW }

    fun learnedPowerKw(ctx: Context?): Int =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY_POWER_LEARNED, PowerScale.DEPART_KW)
            ?.takeIf { it > 0 } ?: PowerScale.DEPART_KW

    /** Pointes des derniers trajets, une par trajet. */
    fun powerPeaks(ctx: Context): List<Float> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POWER_PEAKS, null).orEmpty()
            .split(',').mapNotNull { it.trim().toFloatOrNull() }

    fun saveLearnedPower(ctx: Context, plafond: Int, pointes: List<Float>? = null) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_POWER_LEARNED, plafond)
            // Nombres pour machine : toujours le point décimal (piège de la virgule sur la voiture).
            pointes?.let { putString(KEY_POWER_PEAKS, it.joinToString(",") { p -> p.roundToInt().toString() }) }
        }.apply()
    }

    // ── Réglages communs d'avant les profils ─────────────────────────────────

    /** Les réglages communs, tels qu'enregistrés avant le 2026-10-24 (ou sans aucun profil). */
    fun legacy(ctx: Context): ClusterConfig {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return legacyConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            navigation = p.getString(KEY_TAB_NAVIGATION, null),
            media = p.getString(KEY_TAB_MEDIA, null),
            legacyModule = p.getString(KEY_MODULE, null),
            unit = p.getString(KEY_UNIT, null),
            distance = p.getString(KEY_DISTANCE, null),
            style = p.getString(KEY_STYLE, null),
            average = p.getString(KEY_AVG_UNIT, null),
            pill = p.getString(KEY_PILL, null),
            refreshMs = if (p.contains(KEY_REFRESH)) p.getInt(KEY_REFRESH, DEFAULT_REFRESH_MS) else null,
            animationMs = if (p.contains(KEY_ANIMATION)) p.getInt(KEY_ANIMATION, DEFAULT_ANIMATION_MS) else null,
        )
    }

    /** Sans aucun profil, la carte règle les réglages communs : ils sont réécrits en entier. */
    fun saveLegacy(ctx: Context, c: ClusterConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_ENABLED, c.enabled == true)
            putString(KEY_TAB_NAVIGATION, c.navigation)
            putString(KEY_TAB_MEDIA, c.media)
            putString(KEY_UNIT, c.unit)
            putString(KEY_DISTANCE, c.distance)
            putString(KEY_STYLE, c.style)
            putString(KEY_AVG_UNIT, c.average)
            putString(KEY_PILL, c.pill)
            c.refreshMs?.let { putInt(KEY_REFRESH, it) } ?: remove(KEY_REFRESH)
            c.animationMs?.let { putInt(KEY_ANIMATION, it) } ?: remove(KEY_ANIMATION)
        }.apply()
    }

    // ── Sans Android ─────────────────────────────────────────────────────────

    fun supportedOn(gen: FirmwareInfo.Gen): Boolean = gen in SUPPORTED

    fun isEnabled(c: ClusterConfig): Boolean = c.enabled == true

    fun moduleFor(c: ClusterConfig, tab: ClusterTab): ClusterModule? = when (tab) {
        ClusterTab.NAVIGATION -> resolveTab(tab, c.navigation, null)
        ClusterTab.MEDIA -> resolveTab(tab, c.media, null)
        else -> null
    }

    fun consumptionUnit(c: ClusterConfig): ConsumptionUnit = ConsumptionUnit.fromKey(c.unit)

    fun refreshMs(c: ClusterConfig): Int = sanitizeRefresh(c.refreshMs)

    fun style(c: ClusterConfig): ClusterGaugeStyle = ClusterGaugeStyle.fromKey(c.style)

    fun distanceChoice(c: ClusterConfig): DistanceUnit? =
        c.distance?.let { k -> DistanceUnit.entries.firstOrNull { it.key == k } }

    fun distanceUnit(c: ClusterConfig, vehicle: DistanceUnit?): DistanceUnit = DistanceUnit.resolve(c.distance, vehicle)

    fun averageUnit(c: ClusterConfig): AverageUnit = AverageUnit.resolve(c.average, consumptionUnit(c))

    fun pill(c: ClusterConfig): ClusterPill = ClusterPill.fromKey(c.pill)

    fun animationMs(c: ClusterConfig): Int = sanitizeAnimation(c.animationMs)

    /** [c] avec [module] pour [tab] (`null` = Aucun) ; un onglet sans module possible ne change rien. */
    fun withModule(c: ClusterConfig, tab: ClusterTab, module: ClusterModule?): ClusterConfig = when (tab) {
        ClusterTab.NAVIGATION -> c.copy(navigation = module?.key ?: NONE)
        ClusterTab.MEDIA -> c.copy(media = module?.key ?: NONE)
        else -> c
    }

    /**
     * Réglages communs d'avant les profils, rendus dans la forme d'un profil. Le module de chaque
     * onglet est résolu une fois pour toutes, l'ancien réglage unique ([legacyModule]) compris :
     * le profil n'a pas à le connaître.
     */
    fun legacyConfig(
        enabled: Boolean, navigation: String?, media: String?, legacyModule: String?,
        unit: String?, distance: String?, style: String?, average: String?, pill: String?,
        refreshMs: Int?, animationMs: Int?,
    ): ClusterConfig = ClusterConfig(
        enabled = enabled,
        navigation = resolveTab(ClusterTab.NAVIGATION, navigation, legacyModule)?.key ?: NONE,
        media = resolveTab(ClusterTab.MEDIA, media, null)?.key ?: NONE,
        unit = unit, distance = distance, style = style, average = average, pill = pill,
        refreshMs = refreshMs, animationMs = animationMs,
    )

    /**
     * Module d'un onglet d'après ce qui est enregistré. Sans choix pour lui : l'ancien réglage
     * unique ([legacy]) vaut pour la navigation, où le module s'affichait jusque-là, et rien pour
     * la musique.
     */
    fun resolveTab(tab: ClusterTab, stored: String?, legacy: String?): ClusterModule? = when {
        tab !in TABS -> null
        stored == NONE -> null
        stored != null -> ClusterModule.entries.firstOrNull { it.key == stored }
        tab == ClusterTab.NAVIGATION -> ClusterModule.fromKey(legacy)
        else -> null
    }

    /**
     * Module à montrer pour l'onglet [tabValue] choisi au volant (`IPK_CLUSTER_TAB_BTN_STS`), ou
     * `null` pour laisser la page d'origine — ou le combiné dessiner la sienne. Onglet illisible :
     * celui de la navigation, à défaut celui de la musique, plutôt qu'un module qui disparaît.
     */
    fun moduleToShow(tabValue: Int?, navigation: ClusterModule?, media: ClusterModule?): ClusterModule? =
        when (tabValue) {
            null -> navigation ?: media
            ClusterTab.NAVIGATION.value -> navigation
            ClusterTab.MEDIA.value -> media
            else -> null
        }

    /** Valeur utilisable : le défaut si rien n'est enregistré, sinon calée sur un pas et ramenée dans la plage. */
    fun sanitizeRefresh(stored: Int?): Int {
        val ms = stored ?: DEFAULT_REFRESH_MS
        val cale = Math.floorDiv(ms + STEP_REFRESH_MS / 2, STEP_REFRESH_MS) * STEP_REFRESH_MS
        return cale.coerceIn(MIN_REFRESH_MS, MAX_REFRESH_MS)
    }

    /** Le curseur compte en dixièmes de seconde (1 à 10) : un pas entier, sans erreur d'arrondi. */
    fun refreshFromSlider(value: Float): Int = sanitizeRefresh((value * STEP_REFRESH_MS).roundToInt())

    fun refreshToSlider(ms: Int): Float = (sanitizeRefresh(ms) / STEP_REFRESH_MS).toFloat()

    /** Comme [sanitizeRefresh], pour la durée de l'animation. */
    fun sanitizeAnimation(stored: Int?): Int {
        val ms = stored ?: DEFAULT_ANIMATION_MS
        val cale = Math.floorDiv(ms + STEP_ANIMATION_MS / 2, STEP_ANIMATION_MS) * STEP_ANIMATION_MS
        return cale.coerceIn(MIN_ANIMATION_MS, MAX_ANIMATION_MS)
    }

    /** Le curseur compte en dixièmes de seconde (2 à 20). */
    fun animationFromSlider(value: Float): Int = sanitizeAnimation((value * STEP_ANIMATION_MS).roundToInt())

    fun animationToSlider(ms: Int): Float = (sanitizeAnimation(ms) / STEP_ANIMATION_MS).toFloat()
}
