package com.mg4.control.util

import android.content.Context

/**
 * Ce que règle le mode AUTO de la clim (issue #160, Réglages › Avancé) : en AUTO la voiture choisit
 * elle-même la ventilation, le sens de l'air et la recirculation (constaté par l'utilisateur et par
 * l'auteur de l'issue, SWI68). L'utilisateur peut garder à sa main le sens de l'air et la
 * recirculation : MG4Control les remet juste après le passage en AUTO (MG4Hardware.setClimateAuto).
 * Par défaut, tout est laissé à la voiture — son comportement.
 */
object ClimateAutoScope {

    private const val PREFS = "mg4_settings"
    const val KEY_AIR = "hvac_auto_sets_air"
    const val KEY_LOOP = "hvac_auto_sets_loop"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Vrai (défaut) : la voiture choisit le sens de l'air en AUTO. */
    fun carSetsAir(ctx: Context?): Boolean = ctx?.let { prefs(it).getBoolean(KEY_AIR, true) } ?: true

    /** Vrai (défaut) : la voiture choisit la recirculation en AUTO. */
    fun carSetsLoop(ctx: Context?): Boolean = ctx?.let { prefs(it).getBoolean(KEY_LOOP, true) } ?: true

    fun setCarSetsAir(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AIR, on).apply()
    fun setCarSetsLoop(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_LOOP, on).apply()
}
