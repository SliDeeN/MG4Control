package com.mg4.control.util

import android.content.Context

/**
 * Réglage « Pop-up de charge » (Réglages › Interface, 2026-10-16). Caché derrière cinq appuis sur
 * le logo pendant ses essais, visible de tous et ACTIVÉ par défaut depuis le 2026-10-17 (demande de
 * l'utilisateur) : sans réglage enregistré, le pop-up s'ouvre à la charge.
 */
object ChargePopupSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_ENABLED = "charge_popup_enabled"
    const val DEFAULT_ENABLED = true

    fun isEnabled(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_ENABLED, DEFAULT_ENABLED) ?: false

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
