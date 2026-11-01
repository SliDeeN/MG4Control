package com.mg4.control.service

import android.content.Context
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.RegenLevel
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.LocaleHelper

/**
 * État et valeur montrés par les boutons flottants (2026-10-24, maquette A, règle dans
 * [com.mg4.control.model.FloatingButtonStyle]). Mêmes lectures que celles des raccourcis et du
 * Dashboard : appels binder, hors du fil principal. Toute lecture ratée rend `null` — la bulle
 * garde alors son allure ordinaire plutôt que d'afficher un état supposé.
 */
object FloatingButtonState {

    /**
     * Bascule activée ? `null` si illisible. [clim] : l'état de la clim, lu au plus une fois par
     * tour pour toutes les bulles qui en dépendent.
     */
    fun bascule(action: ShortcutAction, clim: () -> MG4Hardware.ClimateState?): Boolean? = runCatching {
        when (action) {
            ShortcutAction.ONE_PEDAL -> MG4Hardware.getRegenLevel()?.let { it == RegenLevel.ONE_PEDAL }
            ShortcutAction.ENERGY_SAVING_TOGGLE -> MG4Hardware.isEnergySavingOn()
            ShortcutAction.TSR_TOGGLE -> MG4Hardware.isTsrOn()
            ShortcutAction.SOUND_WARNING -> MG4Hardware.isSoundWarningOn()
            ShortcutAction.OVERSPEED_ALARM -> MG4Hardware.isOverspeedAlarmOn()
            ShortcutAction.SPEED_LIMIT_TONE -> MG4Hardware.isSpeedLimitToneOn()
            ShortcutAction.ESC_TOGGLE -> MG4Hardware.isEscOn()
            ShortcutAction.DROWSINESS_TOGGLE -> MG4Hardware.isDrowsinessOn()
            ShortcutAction.HVAC_TOGGLE -> clim()?.powerOn
            ShortcutAction.HVAC_AC_TOGGLE -> clim()?.acOn
            ShortcutAction.DEFROST_FRONT_TOGGLE -> clim()?.defrostFront
            ShortcutAction.DEFROST_REAR_TOGGLE -> clim()?.defrostRear
            ShortcutAction.STEERING_HEAT_TOGGLE -> MG4Hardware.getSteeringHeatOrNull()
            ShortcutAction.AUTO_HIGH_BEAM_TOGGLE -> MG4Hardware.isAutoHighBeamOn()
            ShortcutAction.BATTERY_HEAT_TOGGLE -> MG4Hardware.isBatteryHeatingOn()
            else -> null
        }
    }.getOrNull()

    /** Valeur d'un cycle ou d'un réglage, telle que la voiture la donne, dans la langue de l'appli ; `null` si illisible. */
    fun valeur(ctx: Context, action: ShortcutAction): String? = runCatching {
        val loc = LocaleHelper.applyLocale(ctx)
        when (action) {
            ShortcutAction.REGEN_CYCLE -> MG4Hardware.getRegenLevel()?.let { loc.getString(libelleRegen(it)) }
            ShortcutAction.DROWSINESS_SEN_CYCLE -> when (MG4Hardware.getDrowsinessSensitivity()) {
                MG4Hardware.DrowsinessSensitivity.LOW -> loc.getString(R.string.sens_low)
                MG4Hardware.DrowsinessSensitivity.MEDIUM -> loc.getString(R.string.sens_medium)
                MG4Hardware.DrowsinessSensitivity.HIGH -> loc.getString(R.string.sens_high)
                else -> null
            }
            ShortcutAction.SEAT_HEAT_LEFT_CYCLE -> MG4Hardware.getSeatHeatLeftOrNull()?.let { niveau(loc, it) }
            ShortcutAction.SEAT_HEAT_RIGHT_CYCLE -> MG4Hardware.getSeatHeatRightOrNull()?.let { niveau(loc, it) }
            ShortcutAction.HVAC_RECIRC_CYCLE -> when (MG4Hardware.getClimateState()?.loopMode) {
                MG4Hardware.LoopMode.INNER -> loc.getString(R.string.recirc_inner)
                MG4Hardware.LoopMode.OUTSIDE -> loc.getString(R.string.recirc_outside)
                MG4Hardware.LoopMode.AUTO -> loc.getString(R.string.recirc_auto)
                else -> null
            }
            ShortcutAction.HVAC_AUTO_VOLUME_CYCLE -> when (MG4Hardware.getClimateState()?.autoVolume) {
                MG4Hardware.AutoVolume.LOW -> loc.getString(R.string.clim_autovol_low)
                MG4Hardware.AutoVolume.MEDIUM -> loc.getString(R.string.clim_autovol_medium)
                MG4Hardware.AutoVolume.HIGH -> loc.getString(R.string.clim_autovol_high)
                else -> null
            }?.let { loc.getString(R.string.floating_value_autovol, it) }
            ShortcutAction.HVAC_TEMP_UP, ShortcutAction.HVAC_TEMP_DOWN ->
                MG4Hardware.getClimateState()?.tempC?.let { loc.getString(R.string.preclim_value_celsius, it) }
            ShortcutAction.HVAC_FAN_UP, ShortcutAction.HVAC_FAN_DOWN -> MG4Hardware.getClimateState()?.let { etat ->
                // En AUTO la voiture annonce 15, qui n'est pas un cran : on écrit « Auto ».
                val niveau = if (etat.reglages().enAuto) loc.getString(R.string.clim_auto) else etat.fanLevel?.toString()
                niveau?.let { loc.getString(R.string.floating_value_fan, it) }
            }
            ShortcutAction.BRIGHTNESS_UP, ShortcutAction.BRIGHTNESS_DOWN ->
                MG4Hardware.getScreenBrightnessPercent(journal = false).takeIf { it >= 0 }
                    ?.let { loc.getString(R.string.preclim_value_percent, it) }
            ShortcutAction.VOLUME_UP, ShortcutAction.VOLUME_DOWN ->
                MG4Hardware.getMediaVolume().takeIf { it >= 0 }?.let { loc.getString(R.string.floating_value_volume, it) }
            else -> null
        }
    }.getOrNull()

    /** Les libellés courts des niveaux, ceux de l'onglet Raccourcis. */
    private fun libelleRegen(n: RegenLevel): Int = when (n) {
        RegenLevel.LOW -> R.string.regen_low
        RegenLevel.MEDIUM -> R.string.regen_medium
        RegenLevel.HIGH -> R.string.regen_high
        RegenLevel.ADAPTIVE -> R.string.regen_adaptive_short
        RegenLevel.ONE_PEDAL -> R.string.regen_one_pedal_short
        RegenLevel.OFF -> R.string.regen_off
    }

    /** Siège chauffant : coupé ou niveau 1 à 3, les libellés du pop-up HVAC. */
    private fun niveau(loc: Context, n: Int): String? = when (n) {
        0 -> loc.getString(R.string.climate_off)
        1 -> loc.getString(R.string.climate_level_1)
        2 -> loc.getString(R.string.climate_level_2)
        3 -> loc.getString(R.string.climate_level_3)
        else -> null
    }
}
