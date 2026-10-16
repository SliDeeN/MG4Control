package com.mg4.control.util

import android.content.Context

/**
 * Réglage « Pop-up de charge » (Réglages › Interface, 2026-10-16) : désactivé par défaut, et
 * caché tant que l'utilisateur ne l'a pas fait apparaître par cinq appuis sur le logo en haut à
 * gauche. Au contraire du bouton Diagnostic, débloqué en mémoire seulement, l'option reste visible
 * une fois apparue.
 */
object ChargePopupSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_UNLOCKED = "charge_popup_unlocked"
    const val KEY_ENABLED = "charge_popup_enabled"

    fun isUnlocked(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_UNLOCKED, false) ?: false

    /** Vrai si l'option vient d'apparaître (elle ne l'était pas encore). */
    fun unlock(ctx: Context): Boolean {
        if (isUnlocked(ctx)) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UNLOCKED, true).apply()
        return true
    }

    /** Activé ET visible : une option cachée ne fait rien, quoi qu'il reste dans les préférences. */
    fun isEnabled(ctx: Context?): Boolean {
        val prefs = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return false
        return prefs.getBoolean(KEY_UNLOCKED, false) && prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
