package com.mg4.control.automation

import android.content.Context
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.BrightnessCurve

/**
 * Clés + défauts de la luminosité automatique au démarrage, partagés entre l'UI et le service.
 * Même fichier de préférences que les autres automatisations.
 */
object AutoBrightnessSettings {

    const val PREFS         = AutomationSettings.PREFS
    const val KEY_ENABLED   = "autobri_enabled"
    const val KEY_NIGHT     = "autobri_night"
    const val KEY_TWILIGHT  = "autobri_twilight"
    const val KEY_OVERCAST  = "autobri_overcast"
    const val KEY_SUNNY     = "autobri_sunny"
    /** Prévision Open-Meteo en cache (JSON). */
    const val KEY_FORECAST  = "autobri_forecast"

    // Dernier réglage, pour la ligne d'état de la carte.
    const val KEY_LAST_AT      = "autobri_last_at"
    const val KEY_LAST_SOURCE  = "autobri_last_source"
    const val KEY_LAST_LUX     = "autobri_last_lux"
    const val KEY_LAST_PERCENT = "autobri_last_percent"

    /** Le plancher de l'écran lui-même : en dessous, on risquerait de l'éteindre. */
    const val MIN_PERCENT = MG4Hardware.BRIGHTNESS_MIN_PERCENT
    const val MAX_PERCENT = 100
    const val STEP_PERCENT = 5

    data class Config(val enabled: Boolean, val curve: BrightnessCurve)

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = BrightnessCurve.DEFAULT
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            curve = BrightnessCurve(
                night    = clamp(p.getInt(KEY_NIGHT, d.night)),
                twilight = clamp(p.getInt(KEY_TWILIGHT, d.twilight)),
                overcast = clamp(p.getInt(KEY_OVERCAST, d.overcast)),
                sunny    = clamp(p.getInt(KEY_SUNNY, d.sunny)),
            ),
        )
    }

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}
