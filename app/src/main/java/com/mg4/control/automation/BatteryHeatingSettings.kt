package com.mg4.control.automation

import android.content.Context

/**
 * Clés + défauts de la coupure automatique du chauffage de la batterie, partagés entre l'UI et le
 * moteur. Même fichier de préférences que les autres automatisations.
 */
object BatteryHeatingSettings {

    const val PREFS       = AutomationSettings.PREFS
    const val KEY_ENABLED = "batheat_auto_enabled"
    const val KEY_MINUTES = "batheat_auto_minutes"

    const val DEFAULT_MINUTES = 30
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 120
    const val STEP_MINUTES = 5

    data class Config(val enabled: Boolean, val minutes: Int)

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            minutes = clamp(p.getInt(KEY_MINUTES, DEFAULT_MINUTES)),
        )
    }

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
}
