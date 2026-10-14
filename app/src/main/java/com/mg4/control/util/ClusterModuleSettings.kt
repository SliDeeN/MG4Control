package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.ClusterModule
import kotlin.math.roundToInt

/**
 * Réglage « Module sur le combiné » (Réglages › Interface) : un interrupteur, désactivé par
 * défaut, et le module montré. Le module est relu à chaque relevé : en changer vaut tout de
 * suite, sans réafficher la fenêtre.
 */
object ClusterModuleSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_ENABLED = "cluster_module_enabled"
    const val KEY_MODULE = "cluster_module"
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

    fun module(ctx: Context?): ClusterModule =
        ClusterModule.fromKey(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_MODULE, null))

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun setModule(ctx: Context, module: ClusterModule) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODULE, module.key).apply()
    }

    /** Relu à chaque relevé : changer le curseur vaut tout de suite. */
    fun refreshMs(ctx: Context?): Int =
        sanitizeRefresh(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY_REFRESH, DEFAULT_REFRESH_MS))

    fun setRefreshMs(ctx: Context, ms: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_REFRESH, sanitizeRefresh(ms)).apply()
    }

    // ── Sans Android ─────────────────────────────────────────────────────────

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
