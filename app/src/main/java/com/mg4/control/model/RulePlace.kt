package com.mg4.control.model

import java.util.UUID
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Un lieu enregistré de Mes automatisations (étape 3, lot 2) : un point et un rayon. Dans `model`
 * pour Gson (noms gardés une fois minifié).
 */
data class RulePlace(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radius: Int = 200,
) {
    fun contains(lat: Double, lng: Double): Boolean = Geo.distanceM(this.lat, this.lng, lat, lng) <= radius
}

object Geo {
    private const val R_TERRE_M = 6_371_000.0

    /** Distance à vol d'oiseau, formule de haversine. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLng = rad(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R_TERRE_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun rad(d: Double) = d * PI / 180.0
}

/**
 * Lever et coucher du soleil (algorithme de l'« Almanac for Computers », précision de l'ordre de
 * la minute — largement assez pour allumer des feux ou baisser une luminosité).
 */
object SunCalc {

    /** Zénith officiel : centre du soleil 50' sous l'horizon (réfraction et demi-diamètre). */
    private const val ZENITH = 90.833

    /**
     * Heure locale, en minutes depuis minuit, du lever ([rising]) ou du coucher, au jour [dayOfYear]
     * (1 = 1er janvier), pour un décalage horaire [tzOffsetMin]. `null` : pas de lever ou de coucher
     * ce jour-là (nuit ou jour polaire).
     */
    fun minuteOfDay(dayOfYear: Int, lat: Double, lng: Double, tzOffsetMin: Int, rising: Boolean): Int? {
        val lngHeure = lng / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHeure) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm360(m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634)
        var ra = norm360(degres(atan(0.91764 * tanD(l))))
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(ZENITH) - sinDec * sinD(lat)) / (cosDec * cosD(lat))
        if (cosH > 1.0 || cosH < -1.0) return null
        val h = (if (rising) 360.0 - degres(acos(cosH)) else degres(acos(cosH))) / 15.0
        val tLocal = h + ra - 0.06571 * t - 6.622
        val ut = ((tLocal - lngHeure) % 24.0 + 24.0) % 24.0
        return (((ut * 60.0).toInt() + tzOffsetMin) % 1440 + 1440) % 1440
    }

    /** Vrai si le soleil est levé à [minute] ; `null` si on ne peut pas le dire. */
    fun isUp(minute: Int, dayOfYear: Int, lat: Double, lng: Double, tzOffsetMin: Int): Boolean? {
        val lever = minuteOfDay(dayOfYear, lat, lng, tzOffsetMin, rising = true)
        val coucher = minuteOfDay(dayOfYear, lat, lng, tzOffsetMin, rising = false)
        if (lever == null || coucher == null) return null
        return RuleLogic.inRange(minute, lever, coucher)
    }

    private fun degres(r: Double) = r * 180.0 / PI
    private fun sinD(d: Double) = sin(d * PI / 180.0)
    private fun cosD(d: Double) = cos(d * PI / 180.0)
    private fun tanD(d: Double) = tan(d * PI / 180.0)
    private fun norm360(d: Double) = (d % 360.0 + 360.0) % 360.0
}

/**
 * Clés d'écriture propres aux automatisations (étape 3), en plus de `ExternalApi.SET_*` : réglages
 * que MG4Control sait écrire mais que l'API externe n'expose pas. Valeurs : "1" / "0" pour les
 * interrupteurs, un nombre sinon.
 */
object RuleKeys {
    const val BRIGHTNESS = "rule_brightness"          // 0..100 %
    const val VOLUME = "rule_volume"                  // niveau absolu
    const val VOLUME_STEP = "rule_volume_step"        // + / − n crans
    const val ONE_PEDAL = "rule_one_pedal"
    const val ENERGY_SAVING = "rule_energy_saving"
    const val AEB = "rule_aeb"
    const val TSR = "rule_tsr"
    const val ESC = "rule_esc"
    const val DROWSINESS = "rule_drowsiness"
    const val OVERSPEED_ALARM = "rule_overspeed_alarm"
    const val SPEED_LIMIT_TONE = "rule_speed_limit_tone"
    const val HVAC_ECO = "rule_hvac_eco"
    const val AIR_FLOW = "rule_air_flow"              // 0..6
    const val AUTO_VOLUME = "rule_auto_volume"        // 0 fort, 1 moyen, 2 faible
    const val AUTO_HIGH_BEAM = "rule_auto_high_beam"
    const val BATTERY_HEAT = "rule_battery_heat"
    const val CHARGE_LIMIT = "rule_charge_limit"      // 40..100 %
    // Lot 2 : Android
    const val WIFI = "rule_wifi"
    const val BLUETOOTH = "rule_bluetooth"
    const val HOTSPOT = "rule_hotspot"

    val ALL = setOf(BRIGHTNESS, VOLUME, VOLUME_STEP, ONE_PEDAL, ENERGY_SAVING, AEB, TSR, ESC, DROWSINESS,
        OVERSPEED_ALARM, SPEED_LIMIT_TONE, HVAC_ECO, AIR_FLOW, AUTO_VOLUME, AUTO_HIGH_BEAM, BATTERY_HEAT,
        CHARGE_LIMIT, WIFI, BLUETOOTH, HOTSPOT)
}

/** Un son importé depuis l'éditeur de Mes automatisations (2026-11-06). Dans `model` pour Gson. */
data class RuleSound(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val path: String = "",
    val durationMs: Long = 0L,
)
