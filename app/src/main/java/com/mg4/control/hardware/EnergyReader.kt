package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeType
import com.mg4.control.model.EnergySnapshot

/**
 * Lecture des grandeurs énergétiques du véhicule, **en lecture seule**.
 *
 * Tout passe par le `CarPropertyManager` : les relevés du 19 septembre 2026 ont montré que les
 * gestionnaires SAIC (`bms`, `advanced_assisted_driving`) rendent exactement les mêmes valeurs, il
 * n'y a donc rien à gagner à en ajouter un.
 *
 * Trois filtres, tous appris sur la voiture et tous indispensables :
 *  - **82,3** est la sentinelle des mesures de la famille batterie : cinq propriétés la rendaient
 *    en même temps, dont la « consommation moyenne » — ce n'est pas une valeur ;
 *  - **511,5** et **1023** sont les sentinelles des grandeurs de charge à l'arrêt ;
 *  - un pourcentage hors de 0..100 est une lecture ratée (`BMS_PACK_SOC` rendait 102,3).
 *
 * Toute valeur refusée devient `null`, jamais zéro : un zéro dirait « batterie vide » ou
 * « rien consommé », deux affirmations que nous ne sommes pas en mesure de faire.
 */
object EnergyReader {

    private const val TAG = "MG4_STATS"

    private const val AREA_GLOBAL = 0x1000000

    // ── Identifiants relevés dans les firmwares, validés sur SWI133 ─────────
    private const val PROP_SOC = 0x2160f404
    private const val PROP_ODOMETER = 0x21401566
    private const val PROP_ENERGY_SINCE_START = 0x2160a1bc
    private const val PROP_ENERGY_SINCE_CHARGE = 0x2160a1bb
    private const val PROP_CLIMATE_SINCE_START = 0x2160a1c2
    private const val PROP_ACCESSORIES_SINCE_START = 0x2160a1be
    private const val PROP_REGEN_SINCE_START = 0x2160a1c0
    private const val PROP_CHARGE_STATUS = 0x2140f409
    private const val PROP_PLUG_AC = 0x2140f43e
    private const val PROP_PLUG_DC = 0x2140f43f
    private const val PROP_PACK_VOLTAGE = 0x2160f406
    private const val PROP_PACK_CURRENT = 0x2160f407
    private const val PROP_RANGE = 0x2140f41c

    /** Valeur rendue par les mesures batterie non publiées (relevé du 2026-09-19). */
    private const val SENTINEL_MEASURE = 82.3f
    private const val SENTINEL_HALF = 511.5f
    private const val SENTINEL_INT = 1023

    @Volatile
    private var warnedNoCpm = false

    /** Instantané complet. Appeler hors du fil principal : une quinzaine d'appels binder. */
    fun read(): EnergySnapshot {
        val now = System.currentTimeMillis()
        if (MG4Hardware.carPropertyManager() == null) {
            if (!warnedNoCpm) {
                warnedNoCpm = true
                AppLogger.w(TAG, "liaison véhicule absente : aucun relevé possible")
            }
            return EnergySnapshot(timestampMs = now)
        }
        warnedNoCpm = false

        val volts = float(PROP_PACK_VOLTAGE)
        val amperes = float(PROP_PACK_CURRENT)
        val charging = int(PROP_CHARGE_STATUS)?.let { it == 1 }

        return EnergySnapshot(
            timestampMs = now,
            socPercent = float(PROP_SOC)?.takeIf { it in 0f..100f },
            odometerKm = int(PROP_ODOMETER)?.takeIf { it > 0 },
            energySinceStartKwh = energy(PROP_ENERGY_SINCE_START),
            energySinceChargeKwh = energy(PROP_ENERGY_SINCE_CHARGE),
            climateSinceStartKwh = energy(PROP_CLIMATE_SINCE_START),
            accessoriesSinceStartKwh = energy(PROP_ACCESSORIES_SINCE_START),
            regenSinceStartKwh = energy(PROP_REGEN_SINCE_START),
            charging = charging,
            chargeType = chargeType(),
            // Pas de propriété de puissance : c'est le produit, le courant étant négatif en charge.
            powerKw = if (volts != null && amperes != null) -volts * amperes / 1000f else null,
            rangeKm = int(PROP_RANGE)?.takeIf { it in 1..1500 },
            outsideTempC = MG4Hardware.getOutsideTempCelsius(),
        )
    }

    /**
     * Type de prise. Les deux propriétés valent 0 hors charge et la prise active passe à 2
     * (relevé sur charge alternative) : on ne teste donc pas une valeur précise mais un non-zéro.
     */
    private fun chargeType(): ChargeType? = when {
        int(PROP_PLUG_DC)?.takeIf { it != 0 && it != SENTINEL_INT } != null -> ChargeType.DC
        int(PROP_PLUG_AC)?.takeIf { it != 0 && it != SENTINEL_INT } != null -> ChargeType.AC
        else -> null
    }

    /** Compteur d'énergie : négatif impossible, sentinelle exclue. */
    private fun energy(prop: Int): Float? = float(prop)?.takeIf { it >= 0f }

    private fun float(prop: Int): Float? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        val value = runCatching {
            cpm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
                .invoke(cpm, prop, AREA_GLOBAL) as? Float
        }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.takeIf { it != SENTINEL_MEASURE && it != SENTINEL_HALF }
    }

    private fun int(prop: Int): Int? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        return runCatching {
            cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, prop, AREA_GLOBAL) as? Int
        }.getOrNull()
    }
}
