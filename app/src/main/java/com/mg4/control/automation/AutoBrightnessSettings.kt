package com.mg4.control.automation

import android.content.Context
import com.mg4.control.BuildConfig
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.BrightnessCurve

/**
 * Clés + défauts de la luminosité automatique, partagés entre l'UI et le service.
 * Même fichier de préférences que les autres automatisations.
 */
object AutoBrightnessSettings {

    const val PREFS         = AutomationSettings.PREFS
    const val KEY_ENABLED   = "autobri_enabled"
    /** « Ajuster la luminosité pendant la conduite » (feux + écart de la lumière estimée), au-delà du READY. */
    const val KEY_FOLLOW    = "autobri_follow"
    /** Tenir compte des feux de position (garde-fou garage/tunnel/nuit). */
    const val KEY_USE_LIGHTS = "autobri_use_lights"
    const val KEY_NIGHT     = "autobri_night"
    const val KEY_TWILIGHT  = "autobri_twilight"
    const val KEY_OVERCAST  = "autobri_overcast"
    const val KEY_SUNNY     = "autobri_sunny"
    /** Version hors ligne : luminosité feux éteints / feux allumés (pas de courbe, pas de réseau). */
    const val KEY_LIGHTS_OFF_PERCENT = "autobri_lights_off_percent"
    const val KEY_LIGHTS_ON_PERCENT  = "autobri_lights_on_percent"
    const val DEFAULT_LIGHTS_OFF_PERCENT = 80
    const val DEFAULT_LIGHTS_ON_PERCENT  = 20
    /** Prévision Open-Meteo en cache (JSON). */
    const val KEY_FORECAST  = "autobri_forecast"
    /** Heure de la dernière requête Open-Meteo, réussie ou non. */
    const val KEY_LAST_FETCH_ATTEMPT = "autobri_last_fetch_attempt"

    // Dernier réglage, pour la ligne d'état de la carte.
    const val KEY_LAST_AT      = "autobri_last_at"
    const val KEY_LAST_SOURCE  = "autobri_last_source"
    const val KEY_LAST_LUX     = "autobri_last_lux"
    const val KEY_LAST_PERCENT = "autobri_last_percent"
    /** Vrai quand un réglage à la main a suspendu l'automatisme jusqu'au prochain READY. */
    const val KEY_PAUSED       = "autobri_paused"

    /** Le plancher de l'écran lui-même : en dessous, on risquerait de l'éteindre. */
    const val MIN_PERCENT = MG4Hardware.BRIGHTNESS_MIN_PERCENT
    const val MAX_PERCENT = 100
    const val STEP_PERCENT = 5

    data class Config(
        val enabled: Boolean,
        val follow: Boolean,
        val useLights: Boolean,
        val curve: BrightnessCurve,
        /** Version hors ligne seulement. */
        val lightsOffPercent: Int,
        val lightsOnPercent: Int,
    )

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = BrightnessCurve.DEFAULT
        return Config(
            enabled   = p.getBoolean(KEY_ENABLED, false),
            // Cochée par défaut dans les deux variantes (choix du 2026-10-01). Un choix déjà fait
            // dans la carte est enregistré et reste prioritaire.
            follow    = p.getBoolean(KEY_FOLLOW, true),
            // Cochée par défaut : c'est le comportement validé au garage le 2026-10-01. Hors ligne,
            // toujours : sans les feux, la variante n'a plus aucune source.
            useLights = BuildConfig.OFFLINE || p.getBoolean(KEY_USE_LIGHTS, true),
            curve = BrightnessCurve(
                night    = clamp(p.getInt(KEY_NIGHT, d.night)),
                twilight = clamp(p.getInt(KEY_TWILIGHT, d.twilight)),
                overcast = clamp(p.getInt(KEY_OVERCAST, d.overcast)),
                sunny    = clamp(p.getInt(KEY_SUNNY, d.sunny)),
            ),
            lightsOffPercent = clamp(p.getInt(KEY_LIGHTS_OFF_PERCENT, DEFAULT_LIGHTS_OFF_PERCENT)),
            lightsOnPercent  = clamp(p.getInt(KEY_LIGHTS_ON_PERCENT, DEFAULT_LIGHTS_ON_PERCENT)),
        )
    }

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}
