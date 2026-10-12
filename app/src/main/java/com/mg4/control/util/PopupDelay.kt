package com.mg4.control.util

import android.content.Context
import kotlin.math.roundToInt

/**
 * Réglage « Délai avant fermeture des pop-ups » (Réglages › Interface).
 *
 * Un seul délai pour les trois fenêtres qui se ferment seules : le sélecteur de profils, la
 * confirmation d'automatisation (OUI / NON, qui vaut NON à l'échéance) et le pop-up HVAC. Chacune
 * le lit à son ouverture : changer le réglage vaut pour le prochain pop-up, sans redémarrage.
 */
object PopupDelay {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY = "popup_close_delay_s"

    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 60
    const val DEFAULT_SECONDS = 10

    fun seconds(ctx: Context?): Int =
        sanitize(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY, DEFAULT_SECONDS))

    fun millis(ctx: Context?): Long = toMillis(seconds(ctx))

    fun set(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, sanitize(seconds)).apply()
    }

    // ── Sans Android ─────────────────────────────────────────────────────────

    /**
     * Valeur utilisable : le défaut si rien n'est enregistré, sinon ramenée dans la plage. Une
     * préférence aberrante ne doit donner ni un pop-up refermé avant d'être vu, ni un pop-up
     * qui ne se referme plus.
     */
    fun sanitize(stored: Int?): Int = (stored ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun toMillis(seconds: Int): Long = sanitize(seconds) * 1_000L

    /** Position du curseur (un nombre à virgule) → secondes. */
    fun fromSlider(value: Float): Int = sanitize(value.roundToInt())
}
