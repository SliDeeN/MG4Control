package com.mg4.control.ui

import androidx.annotation.DrawableRes
import com.mg4.control.R
import com.mg4.control.shortcut.ShortcutAction

/**
 * Pictogramme de chaque action dans la fenêtre de choix des boutons du volant et des bulles
 * (2026-11-26) : les symboles Material déjà embarqués pour les bulles, ou les icônes de l'application
 * (pédale, vitres) — de quoi repérer une action d'un coup d'œil dans une liste de près de soixante.
 */
object ShortcutIcons {

    @DrawableRes
    fun of(action: ShortcutAction): Int = when (action) {
        ShortcutAction.NONE -> R.drawable.ic_sym_cancel
        ShortcutAction.ONE_PEDAL -> R.drawable.ic_rail_pedal
        ShortcutAction.REGEN_CYCLE -> R.drawable.ic_sym_autorenew
        ShortcutAction.DRIVE_MODE_CYCLE -> R.drawable.ic_sym_speed
        ShortcutAction.AEB_CYCLE -> R.drawable.ic_sym_car_crash
        ShortcutAction.SOUND_WARNING -> R.drawable.ic_sym_notifications
        ShortcutAction.OVERSPEED_ALARM -> R.drawable.ic_sym_warning
        ShortcutAction.SPEED_LIMIT_TONE -> R.drawable.ic_sym_notifications
        ShortcutAction.ADAS_CYCLE -> R.drawable.ic_sym_directions_car
        ShortcutAction.ENERGY_SAVING_TOGGLE -> R.drawable.ic_sym_battery_saver
        ShortcutAction.TSR_TOGGLE -> R.drawable.ic_sym_visibility
        ShortcutAction.ESC_TOGGLE -> R.drawable.ic_sym_security
        ShortcutAction.DROWSINESS_TOGGLE -> R.drawable.ic_sym_dark_mode
        ShortcutAction.DROWSINESS_SEN_CYCLE -> R.drawable.ic_sym_equalizer
        ShortcutAction.SEAT_HEAT_LEFT_CYCLE, ShortcutAction.SEAT_HEAT_RIGHT_CYCLE -> R.drawable.ic_sym_airline_seat_recline_normal
        ShortcutAction.STEERING_HEAT_TOGGLE -> R.drawable.ic_sym_whatshot
        ShortcutAction.HVAC_TOGGLE, ShortcutAction.HVAC_POPUP -> R.drawable.ic_sym_hvac
        ShortcutAction.HVAC_AC_TOGGLE -> R.drawable.ic_sym_ac_unit
        ShortcutAction.HVAC_TEMP_UP, ShortcutAction.HVAC_TEMP_DOWN -> R.drawable.ic_sym_device_thermostat
        ShortcutAction.HVAC_FAN_UP, ShortcutAction.HVAC_FAN_DOWN, ShortcutAction.HVAC_AUTO_VOLUME_CYCLE -> R.drawable.ic_sym_air
        ShortcutAction.DEFROST_FRONT_TOGGLE, ShortcutAction.DEFROST_REAR_TOGGLE -> R.drawable.ic_sym_waves
        ShortcutAction.HVAC_RECIRC_CYCLE -> R.drawable.ic_sym_sync
        ShortcutAction.AUTO_HIGH_BEAM_TOGGLE -> R.drawable.ic_sym_highlight
        ShortcutAction.BATTERY_HEAT_TOGGLE -> R.drawable.ic_sym_local_fire_department
        ShortcutAction.BRIGHTNESS_UP -> R.drawable.ic_sym_brightness_high
        ShortcutAction.BRIGHTNESS_DOWN -> R.drawable.ic_sym_brightness_low
        ShortcutAction.MEDIA_NEXT -> R.drawable.ic_sym_skip_next
        ShortcutAction.MEDIA_PREVIOUS -> R.drawable.ic_sym_skip_previous
        ShortcutAction.MEDIA_PLAY_PAUSE -> R.drawable.ic_sym_play_arrow
        ShortcutAction.VOLUME_UP -> R.drawable.ic_sym_volume_up
        ShortcutAction.VOLUME_DOWN -> R.drawable.ic_sym_volume_down
        ShortcutAction.WINDOWS_OPEN_ALL, ShortcutAction.WINDOWS_PULSE_OPEN -> R.drawable.ic_window_down
        ShortcutAction.WINDOWS_CLOSE_ALL, ShortcutAction.WINDOWS_PULSE_CLOSE -> R.drawable.ic_window_up
        ShortcutAction.APPLY_PROFILE -> R.drawable.ic_sym_person
        ShortcutAction.PROFILE_PICKER -> R.drawable.ic_sym_people
        ShortcutAction.PROFILE_CYCLE -> R.drawable.ic_sym_repeat
        ShortcutAction.AUDIO_SOURCE_CYCLE -> R.drawable.ic_sym_radio
        ShortcutAction.CHARGE_POPUP -> R.drawable.ic_sym_ev_station
        ShortcutAction.TYRE_POPUP -> R.drawable.ic_sym_tire_repair
        ShortcutAction.CLUSTER_NEXT_MODULE -> R.drawable.ic_sym_swap_horiz
        ShortcutAction.QUICK_SETTINGS -> R.drawable.ic_sym_tune
        ShortcutAction.PRE_CLIMATE -> R.drawable.ic_sym_garage
        ShortcutAction.PROJECTION -> R.drawable.ic_sym_cast
        ShortcutAction.TRACK_MODE -> R.drawable.ic_sym_sports_motorsports
        ShortcutAction.OPEN_APP -> R.drawable.ic_sym_home
        ShortcutAction.OPEN_CUSTOM_APP -> R.drawable.ic_sym_apps
        ShortcutAction.ANDROID_SETTINGS -> R.drawable.ic_sym_settings
        ShortcutAction.CUSTOM -> R.drawable.ic_sym_bolt
        ShortcutAction.RUN_RULE -> R.drawable.ic_sym_play_circle
        ShortcutAction.VEHICLE_POWER_OFF -> R.drawable.ic_sym_power_settings_new
    }
}
