package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterTab
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
    const val KEY_REFRESH = "cluster_refresh_ms"

    /**
     * Actualisation de la puissance, de la vitesse et de la jauge, en ms : 0,1 à 1 s par pas de
     * 0,1 s. Une demi-seconde par défaut — la seconde du premier essai paraissait lente (2026-10-14).
     */
    const val MIN_REFRESH_MS = 100
    const val MAX_REFRESH_MS = 1000
    const val STEP_REFRESH_MS = 100
    const val DEFAULT_REFRESH_MS = 500

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

    /** Relu à chaque relevé : changer le curseur vaut tout de suite. */
    fun refreshMs(ctx: Context?): Int =
        sanitizeRefresh(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY_REFRESH, DEFAULT_REFRESH_MS))

    fun setRefreshMs(ctx: Context, ms: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_REFRESH, sanitizeRefresh(ms)).apply()
    }

    // ── Sans Android ─────────────────────────────────────────────────────────

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
}
