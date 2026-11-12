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

    fun of(key: String?): Int {
        when (key) {
            ClusterInfo.TRIP_AVERAGE -> return R.drawable.ic_info_chart
            ClusterInfo.TRIP_DISTANCE -> return R.drawable.ic_info_route
            ClusterInfo.TRIP_ENERGY, ClusterInfo.POWER -> return R.drawable.ic_cat_bolt
            ClusterInfo.TRIP_REGEN -> return R.drawable.ic_info_regen
        }
        val s = BubbleInfo.signal(key) ?: return R.drawable.ic_cat_clock
        return when (s) {
            RuleSignal.SOC, RuleSignal.RANGE, RuleSignal.CHARGING, RuleSignal.CHARGE_LIMIT, RuleSignal.BATTERY_HEAT -> R.drawable.ic_cat_battery
            RuleSignal.PLUGGED, RuleSignal.CHARGE_DC -> R.drawable.ic_cat_plug
            // Les températures (extérieure, consigne) : un thermomètre, pas le flocon de la clim.
            RuleSignal.OUTSIDE_TEMP, RuleSignal.HVAC_TEMP -> R.drawable.ic_info_thermometer
            RuleSignal.HVAC_ON, RuleSignal.AC_ON, RuleSignal.HVAC_AUTO, RuleSignal.HVAC_ECO,
            RuleSignal.FAN, RuleSignal.RECIRC, RuleSignal.DEFROST_FRONT, RuleSignal.DEFROST_REAR -> R.drawable.ic_cat_snow
            RuleSignal.SEAT_HEAT_LEFT, RuleSignal.SEAT_HEAT_RIGHT -> R.drawable.ic_cat_seat
            RuleSignal.STEERING_HEAT -> R.drawable.ic_cat_steering
            RuleSignal.AEB, RuleSignal.TSR, RuleSignal.ESC, RuleSignal.DROWSINESS, RuleSignal.OVERSPEED_ALARM,
            RuleSignal.LOCKED, RuleSignal.KEY_AWAY, RuleSignal.FRONT_DOOR -> R.drawable.ic_cat_shield
            RuleSignal.BRIGHTNESS, RuleSignal.NIGHT, RuleSignal.SUN_UP -> R.drawable.ic_cat_screen
            RuleSignal.VOLUME, RuleSignal.MEDIA_PLAYING, RuleSignal.IN_CALL, RuleSignal.PROJECTION -> R.drawable.ic_cat_music
            RuleSignal.BT_ON, RuleSignal.WIFI_ON, RuleSignal.HOTSPOT_ON, RuleSignal.WIFI_SSID, RuleSignal.BT_DEVICE,
            RuleSignal.DATA_TODAY_MB, RuleSignal.DATA_MONTH_MB -> R.drawable.ic_cat_wifi
            RuleSignal.PROFILE -> R.drawable.ic_cat_star
            else -> R.drawable.ic_cat_car
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
