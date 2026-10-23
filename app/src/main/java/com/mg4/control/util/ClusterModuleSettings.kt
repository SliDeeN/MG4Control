package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterPill
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DistanceUnit
import kotlin.math.roundToInt

/**
 * Réglage « Module sur le combiné » (Réglages › Interface) : un interrupteur, désactivé par
 * défaut, et un module par onglet du combiné — Navigation et Musique, les seuls qui montrent
 * l'écran central (2026-10-14). Relu à chaque relevé : en changer vaut tout de suite, sans
 * réafficher la fenêtre.
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
    /** Unité de distance de tout le module : km ou miles (2026-10-23). */
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

    fun isEnabled(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_ENABLED, false) ?: false

    /** Module de [tab], ou `null` pour « Aucun » et pour un onglet dessiné par le combiné. */
    fun moduleFor(ctx: Context?, tab: ClusterTab): ClusterModule? {
        val prefs = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cle = keyFor(tab) ?: return null
        return resolveTab(tab, prefs?.getString(cle, null), prefs?.getString(KEY_MODULE, null))
    }

    fun setModuleFor(ctx: Context, tab: ClusterTab, module: ClusterModule?) {
        val cle = keyFor(tab) ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(cle, module?.key ?: NONE).apply()
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Relue à chaque relevé, comme le reste. */
    fun consumptionUnit(ctx: Context?): ConsumptionUnit =
        ConsumptionUnit.fromKey(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_UNIT, null))

    fun setConsumptionUnit(ctx: Context, unit: ConsumptionUnit) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_UNIT, unit.key).apply()
    }

    /** Relu à chaque relevé : changer le curseur vaut tout de suite. */
    fun refreshMs(ctx: Context?): Int =
        sanitizeRefresh(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY_REFRESH, DEFAULT_REFRESH_MS))

    fun setRefreshMs(ctx: Context, ms: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_REFRESH, sanitizeRefresh(ms)).apply()
    }

    /** Relu à chaque relevé : changer de style vaut tout de suite. */
    fun style(ctx: Context?): ClusterGaugeStyle =
        ClusterGaugeStyle.fromKey(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_STYLE, null))

    fun setStyle(ctx: Context, style: ClusterGaugeStyle) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, style.key).apply()
    }

    /** Relue à chaque relevé ; km sans choix (2026-10-23). */
    fun distanceUnit(ctx: Context?): DistanceUnit =
        DistanceUnit.fromKey(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_DISTANCE, null))

    fun setDistanceUnit(ctx: Context, unit: DistanceUnit) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DISTANCE, unit.key).apply()
    }

    /** Relue à chaque relevé ; sans choix, l'unité de la jauge. */
    fun averageUnit(ctx: Context?): AverageUnit = AverageUnit.resolve(
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_AVG_UNIT, null), consumptionUnit(ctx))

    fun setAverageUnit(ctx: Context, unit: AverageUnit) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_AVG_UNIT, unit.key).apply()
    }

    /** Relu à chaque relevé, comme le reste. */
    fun pill(ctx: Context?): ClusterPill =
        ClusterPill.fromKey(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_PILL, null))

    fun setPill(ctx: Context, pill: ClusterPill) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PILL, pill.key).apply()
    }

    /** Relue à chaque relevé : changer le curseur vaut au relevé suivant. */
    fun animationMs(ctx: Context?): Int =
        sanitizeAnimation(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY_ANIMATION, DEFAULT_ANIMATION_MS))

    fun setAnimationMs(ctx: Context, ms: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ANIMATION, sanitizeAnimation(ms)).apply()
    }

    // ── Sans Android ─────────────────────────────────────────────────────────

    fun supportedOn(gen: FirmwareInfo.Gen): Boolean = gen in SUPPORTED

    private fun keyFor(tab: ClusterTab): String? = when (tab) {
        ClusterTab.NAVIGATION -> KEY_TAB_NAVIGATION
        ClusterTab.MEDIA -> KEY_TAB_MEDIA
        else -> null
    }

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
