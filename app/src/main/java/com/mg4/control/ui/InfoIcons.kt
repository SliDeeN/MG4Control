package com.mg4.control.ui

import com.mg4.control.R
import com.mg4.control.model.BubbleInfo
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.RuleSignal

/**
 * Pictogramme d'une information : bulles d'information (issue #236) et cases de l'écran
 * conducteur (issue #229). Blancs : la vue les teinte.
 */
object InfoIcons {

    /**
     * Un symbole plein par information (refonte du 2026-11-26) : plus de pictogrammes en contour,
     * ni de pictogramme partagé par toute une famille (bouclier, flocon, voiture…).
     */
    fun of(key: String?): Int {
        when (key) {
            BubbleInfo.TIME -> return R.drawable.ic_sym_schedule
            ClusterInfo.TRIP_AVERAGE -> return R.drawable.ic_sym_equalizer
            ClusterInfo.TRIP_DISTANCE -> return R.drawable.ic_sym_route
            ClusterInfo.TRIP_ENERGY -> return R.drawable.ic_sym_offline_bolt
            ClusterInfo.TRIP_REGEN -> return R.drawable.ic_sym_savings
            ClusterInfo.POWER -> return R.drawable.ic_sym_bolt
        }
        val s = BubbleInfo.signal(key) ?: return R.drawable.ic_sym_info
        return when (s) {
            // Batterie et charge
            RuleSignal.SOC -> R.drawable.ic_sym_battery_5_bar
            RuleSignal.RANGE -> R.drawable.ic_sym_electric_car
            RuleSignal.CHARGING -> R.drawable.ic_sym_battery_charging_full
            RuleSignal.CHARGE_LIMIT -> R.drawable.ic_sym_battery_full
            RuleSignal.CHARGE_CURRENT -> R.drawable.ic_sym_electrical_services
            RuleSignal.PLUGGED -> R.drawable.ic_sym_power
            RuleSignal.CHARGE_DC -> R.drawable.ic_sym_ev_station
            RuleSignal.BATTERY_HEAT -> R.drawable.ic_sym_local_fire_department
            // Climatisation
            RuleSignal.OUTSIDE_TEMP -> R.drawable.ic_sym_device_thermostat
            RuleSignal.HVAC_TEMP -> R.drawable.ic_sym_thermostat
            RuleSignal.HVAC_ON -> R.drawable.ic_sym_air
            RuleSignal.AC_ON -> R.drawable.ic_sym_ac_unit
            // AUTO règle surtout la ventilation (issue #160) : ventilateur « A ».
            RuleSignal.HVAC_AUTO -> R.drawable.ic_info_fan_auto
            RuleSignal.HVAC_ECO -> R.drawable.ic_clim_eco
            RuleSignal.FAN -> R.drawable.ic_hvac_fan
            RuleSignal.RECIRC -> R.drawable.ic_sym_repeat
            RuleSignal.DEFROST_FRONT -> R.drawable.ic_air_windshield_front
            RuleSignal.DEFROST_REAR -> R.drawable.ic_air_windshield_rear
            RuleSignal.SEAT_HEAT_LEFT -> R.drawable.ic_seat_heat_left
            RuleSignal.SEAT_HEAT_RIGHT -> R.drawable.ic_seat_heat_right
            RuleSignal.STEERING_HEAT -> R.drawable.ic_steering_wheel_heat
            // Sécurité et accès
            RuleSignal.AEB -> R.drawable.ic_sym_car_crash
            RuleSignal.TSR -> R.drawable.ic_sym_traffic
            RuleSignal.ESC -> R.drawable.ic_sym_security
            RuleSignal.DROWSINESS -> R.drawable.ic_sym_coffee
            RuleSignal.OVERSPEED_ALARM -> R.drawable.ic_sym_notifications
            RuleSignal.LOCKED -> R.drawable.ic_sym_lock
            RuleSignal.KEY_AWAY -> R.drawable.ic_sym_key
            RuleSignal.FRONT_DOOR -> R.drawable.ic_info_car_door
            // Conduite
            RuleSignal.READY -> R.drawable.ic_sym_power_settings_new
            RuleSignal.GEAR -> R.drawable.ic_sym_local_parking
            RuleSignal.SPEED -> R.drawable.ic_sym_speed
            RuleSignal.DRIVE_MODE -> R.drawable.ic_sym_tune
            RuleSignal.REGEN -> R.drawable.ic_sym_sync
            RuleSignal.ONE_PEDAL -> R.drawable.ic_info_one_pedal
            RuleSignal.ENERGY_SAVING -> R.drawable.ic_sym_battery_saver
            RuleSignal.DRIVE_MINUTES -> R.drawable.ic_sym_timer
            RuleSignal.ODOMETER -> R.drawable.ic_sym_map
            RuleSignal.ALTITUDE -> R.drawable.ic_sym_terrain
            RuleSignal.TURN_SIGNAL -> R.drawable.ic_sym_swap_horiz
            // Écran et média
            RuleSignal.BRIGHTNESS -> R.drawable.ic_sym_brightness_high
            RuleSignal.NIGHT -> R.drawable.ic_sym_dark_mode
            RuleSignal.SUN_UP -> R.drawable.ic_sym_wb_sunny
            RuleSignal.VOLUME -> R.drawable.ic_sym_volume_up
            RuleSignal.MEDIA_PLAYING -> R.drawable.ic_sym_play_circle
            RuleSignal.IN_CALL -> R.drawable.ic_sym_call
            RuleSignal.PROJECTION -> R.drawable.ic_source_projection
            // Connexions et divers
            RuleSignal.BT_ON -> R.drawable.ic_sym_bluetooth
            RuleSignal.BT_DEVICE -> R.drawable.ic_sym_smartphone
            RuleSignal.WIFI_ON, RuleSignal.WIFI_SSID -> R.drawable.ic_sym_wifi
            RuleSignal.HOTSPOT_ON -> R.drawable.ic_sym_podcasts
            RuleSignal.DATA_TODAY_MB, RuleSignal.DATA_MONTH_MB -> R.drawable.ic_sym_signal_cellular_alt
            RuleSignal.PROFILE -> R.drawable.ic_sym_person
            RuleSignal.FIRMWARE -> R.drawable.ic_sym_info
            // Pneus : le même pneu, l'unité (bar ou °C) fait la différence ; l'alerte à part.
            RuleSignal.TYRE_PRESSURE_MIN, RuleSignal.TYRE_PRESSURE_FL, RuleSignal.TYRE_PRESSURE_FR, RuleSignal.TYRE_PRESSURE_RL,
            RuleSignal.TYRE_PRESSURE_RR, RuleSignal.TYRE_TEMP_MAX, RuleSignal.TYRE_TEMP_FL, RuleSignal.TYRE_TEMP_FR,
            RuleSignal.TYRE_TEMP_RL, RuleSignal.TYRE_TEMP_RR -> R.drawable.ic_sym_tire_repair
            RuleSignal.TYRE_ALERT -> R.drawable.ic_sym_warning
        }
    }

    /** Ce que la voiture sait lire : mêmes filtres que la liste des valeurs des automatisations. */
    fun readable(s: RuleSignal): Boolean = when (s) {
        RuleSignal.HVAC_ON, RuleSignal.AC_ON, RuleSignal.HVAC_AUTO, RuleSignal.HVAC_ECO, RuleSignal.HVAC_TEMP, RuleSignal.FAN,
        RuleSignal.RECIRC, RuleSignal.DEFROST_FRONT, RuleSignal.DEFROST_REAR -> com.mg4.control.hardware.MG4Hardware.hasClimateControl()
        RuleSignal.ESC, RuleSignal.DROWSINESS -> com.mg4.control.hardware.MG4Hardware.hasDrowsinessAndEsc()
        RuleSignal.BRIGHTNESS -> com.mg4.control.hardware.MG4Hardware.hasBrightnessControl()
        RuleSignal.BATTERY_HEAT -> com.mg4.control.hardware.MG4Hardware.hasBatteryHeating()
        RuleSignal.FRONT_DOOR -> com.mg4.control.hardware.MG4Hardware.hasDoorDetection()
        // Sièges et volant chauffants : seulement les finitions qui en ont (SWI133, SWI68, SWI165).
        RuleSignal.SEAT_HEAT_LEFT, RuleSignal.SEAT_HEAT_RIGHT, RuleSignal.STEERING_HEAT ->
            com.mg4.control.util.FirmwareInfo.hasHeatFeatures()
        else -> true
    }
}
