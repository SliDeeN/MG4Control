package com.mg4.control.automation

import android.content.Context

/**
 * Clés + défauts de l'automatisation « profil selon la batterie » (issue #112), partagés entre
 * l'UI et le service. Même fichier de préférences que l'automatisation température.
 */
object BatteryAutomationSettings {

    const val PREFS            = AutomationSettings.PREFS
    const val KEY_ENABLED      = "automation_soc_enabled"
    const val KEY_THRESHOLD    = "automation_soc_threshold"
    const val KEY_PROFILE_ID   = "automation_soc_profile_id"
    const val KEY_AUTO_EXECUTE = "automation_soc_auto_execute"

    const val DEFAULT_THRESHOLD = 20
    /** Sous 5 %, la voiture limite déjà d'elle-même ; au-delà de 60 %, ce n'est plus une réserve. */
    const val MIN_THRESHOLD = 5
    const val MAX_THRESHOLD = 60

    data class Config(
        val enabled: Boolean,
        val threshold: Int,
        val profileId: String,
        val autoExecute: Boolean,
    )

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled     = p.getBoolean(KEY_ENABLED, false),
            threshold   = clamp(p.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)),
            profileId   = p.getString(KEY_PROFILE_ID, "") ?: "",
            autoExecute = p.getBoolean(KEY_AUTO_EXECUTE, false),
        )
    }

    fun clamp(threshold: Int): Int = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
}
