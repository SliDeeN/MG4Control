package com.mg4.control.util

import android.content.Context

/**
 * Réglage « Pop-up de charge » (Réglages › Interface, 2026-10-16). Caché derrière cinq appuis sur
 * le logo pendant ses essais, visible de tous depuis le 2026-10-17. DÉSACTIVÉ par défaut depuis le
 * 2026-10-30 (sa demande) : sans réglage enregistré, rien ne s'ouvre ; seul un « ON » mis par
 * l'utilisateur l'active — y compris chez ceux qui avaient l'appli avant et n'y avaient jamais touché.
 */
object ChargePopupSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_ENABLED = "charge_popup_enabled"
    const val DEFAULT_ENABLED = false

    fun isEnabled(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_ENABLED, DEFAULT_ENABLED) ?: false

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
