package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeDisplay
import com.mg4.control.model.ChargeLimit
import com.mg4.control.model.ChargeType
import com.mg4.control.model.EnergySnapshot
import com.mg4.control.model.PowerIntegrator
import com.mg4.control.model.StatsSettings

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
    /**
     * Compteur de l'écran d'origine, « AC and others » depuis le contact. Seul poste disponible là
     * où les compteurs ci-dessus restent à zéro (issue #117) — et au **kWh entier** seulement.
     */
    private const val PROP_STOCK_AUX_SINCE_START = 0x2160a198
    private const val PROP_CHARGE_STATUS = 0x2140f409
    private const val PROP_PLUG_AC = 0x2140f43e
    private const val PROP_PLUG_DC = 0x2140f43f
    // Module « charge » du combiné (2026-10-16) — `VehicleChargingBinder` du service SAIC (SWI133).
    private const val PROP_CHARGE_PLUG = 0x2140f408
    private const val PROP_CHARGE_TIME = 0x2140f417
    private const val PROP_CHARGE_TARGET = 0x2140f40c
    // Sonde seulement (2026-10-16) : position de la trappe et validité du signal continu
    // (`CCU_OFFBD_CHRG_PLUG_ON_V`, true = invalide comme les autres drapeaux « V »).
    private const val PROP_CHARGE_DOOR = 0x2140f43b
    private const val PROP_PLUG_DC_INVALID = 0x2140f456
    // Fenêtre « État de charge » seulement (2026-10-23). Noms relevés par MG4Dash dans le
    // CarBMSManager SAIC : motif d'arrêt `BMS_CHRG_SP_RSN`, décharge `V2X`, et 0x2160f43c / 0x2160f43d
    // = courant / tension d'entrée du chargeur embarqué — que notre sonde appelle, elle,
    // « température batterie » et « tension secteur » : c'est à trancher en charge alternative.
    private const val PROP_CHARGE_STOP_REASON = 0x2140f415
    private const val PROP_V2X = 0x2140f430
    private const val PROP_BMS_43C = 0x2160f43c
    private const val PROP_BMS_43D = 0x2160f43d
    private const val PROP_PACK_VOLTAGE = 0x2160f406
    private const val PROP_PACK_CURRENT = 0x2160f407
    private const val PROP_RANGE = 0x2140f41c
    /** Statique, en Wh d'après AOSP. Gardée par `CAR_INFO`. */
    private const val PROP_BATTERY_CAPACITY = 0x11600106

    /** Valeur rendue par les mesures batterie non publiées (relevé du 2026-09-19). */
    private const val SENTINEL_MEASURE = 82.3f
    private const val SENTINEL_HALF = 511.5f
    /** Journal de la charge : celui de la sonde [ChargeProbe] et du pop-up de charge. */
    private const val CHARGE_TAG = "MG4_CHARGE"

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
        val charging = ChargeDisplay.enCharge(int(PROP_CHARGE_STATUS))
        // Les statistiques relèvent la voiture même sans module combiné : la sonde y voit aussi le
        // type de borne.
        ChargeProbe.observer(chargeRaw(), if (volts != null && amperes != null) -volts * amperes / 1000f else null)

        return EnergySnapshot(
            timestampMs = now,
            socPercent = float(PROP_SOC)?.takeIf { it in 0f..100f },
            odometerKm = int(PROP_ODOMETER)?.takeIf { it > 0 },
            energySinceStartKwh = energy(PROP_ENERGY_SINCE_START),
            energySinceChargeKwh = energy(PROP_ENERGY_SINCE_CHARGE),
            climateSinceStartKwh = energy(PROP_CLIMATE_SINCE_START),
            accessoriesSinceStartKwh = energy(PROP_ACCESSORIES_SINCE_START),
            regenSinceStartKwh = energy(PROP_REGEN_SINCE_START),
            auxSinceStartKwh = energy(PROP_STOCK_AUX_SINCE_START),
            charging = charging,
            chargeType = chargeType(),
            // Pas de propriété de puissance : c'est le produit, le courant étant négatif en charge.
            powerKw = if (volts != null && amperes != null) -volts * amperes / 1000f else null,
            rangeKm = int(PROP_RANGE)?.takeIf { it in 1..1500 },
            // Déjà en km/h chez SAIC malgré la spec AOSP : voir MG4Hardware.getVehicleSpeedKmh().
            speedKmh = MG4Hardware.getVehicleSpeedKmh()?.takeIf { it in 0f..300f },
            outsideTempC = MG4Hardware.getOutsideTempCelsius(),
        )
    }

    /** Valeurs BRUTES des signaux de charge, pour le module du combiné et la sonde `MG4_CHARGE`. */
    data class ChargeRaw(
        val statut: Int?,
        val prise: Int?,
        val ac: Int?,
        val dc: Int?,
        val restant: Int?,
        val cible: Int?,
        val trappe: Int? = null,
        val dcInvalide: Int? = null,
    )

    /** Huit lectures. Appeler hors du fil principal. */
    fun chargeRaw(): ChargeRaw = ChargeRaw(
        statut = int(PROP_CHARGE_STATUS),
        prise = int(PROP_CHARGE_PLUG),
        ac = int(PROP_PLUG_AC),
        dc = int(PROP_PLUG_DC),
        restant = int(PROP_CHARGE_TIME),
        cible = int(PROP_CHARGE_TARGET),
        trappe = int(PROP_CHARGE_DOOR),
        dcInvalide = int(PROP_PLUG_DC_INVALID),
    )

    /** Relevé de la fenêtre « État de charge » ([com.mg4.control.ui.ChargeStateDialog]). */
    data class ChargeDebug(
        val brut: ChargeRaw,
        val raison: Int?,
        val v2x: Int?,
        val volts: Float?,
        val amperes: Float?,
        val bms43c: Float?,
        val bms43d: Float?,
        val soc: Float?,
    )

    /** Quinze lectures. Appeler hors du fil principal. */
    fun chargeDebug(): ChargeDebug = ChargeDebug(
        brut = chargeRaw(),
        raison = int(PROP_CHARGE_STOP_REASON),
        v2x = int(PROP_V2X),
        volts = float(PROP_PACK_VOLTAGE),
        amperes = float(PROP_PACK_CURRENT),
        bms43c = float(PROP_BMS_43C),
        bms43d = float(PROP_BMS_43D),
        soc = soc(),
    )

    /** La voiture charge-t-elle ([ChargeDisplay.enCharge]) ? `null` si illisible. Une seule lecture. */
    fun charging(): Boolean? = ChargeDisplay.enCharge(int(PROP_CHARGE_STATUS))

    /** Autonomie annoncée par la batterie, en km ; `null` hors de 1..1500. Une seule lecture. */
    fun rangeKm(): Int? = int(PROP_RANGE)?.takeIf { it in 1..1500 }

    /**
     * Limite de charge ([ChargeLimit], pop-up de charge, 2026-10-16). Voie d'origine sur SWI133
     * comme sur A9 : `CarBMSManager.setGlobalProperty(Integer, 0x2140f40c, code)` (service SAIC
     * `VehicleChargingBinder`, appli `EvCharge` des A9). Repli : la même propriété par le
     * CarPropertyManager, zone globale puis zone 0. Passe par [VehicleWriteGate] comme toute
     * écriture. Vrai si une voie a accepté — la voiture, elle, peut encore refuser : relire.
     */
    fun setChargeLimitPercent(pct: Int): Boolean {
        val code = ChargeLimit.code(pct)
        if (!VehicleWriteGate.allow("limite de charge $pct %")) return false
        val bms = MG4Hardware.car()?.let { car ->
            runCatching { car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, "bms") }.getOrNull()
        }
        if (bms != null) {
            val ok = runCatching {
                bms.javaClass.getMethod("setGlobalProperty", Class::class.java, Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(bms, Integer::class.java, PROP_CHARGE_TARGET, code)
            }
            if (ok.isSuccess) {
                AppLogger.i(CHARGE_TAG, "limite de charge → $pct % (code $code) par le gestionnaire bms")
                return true
            }
            AppLogger.w(CHARGE_TAG, "limite de charge par le gestionnaire bms : ${ok.exceptionOrNull()?.let { (it.cause ?: it).javaClass.simpleName + " " + (it.cause ?: it).message }}")
        }
        val cpm = MG4Hardware.carPropertyManager() ?: return false
        for (zone in intArrayOf(AREA_GLOBAL, 0)) {
            val ok = runCatching {
                cpm.javaClass.getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                    .invoke(cpm, PROP_CHARGE_TARGET, zone, code)
            }
            if (ok.isSuccess) {
                AppLogger.i(CHARGE_TAG, "limite de charge → $pct % (code $code) par le CPM, zone 0x${Integer.toHexString(zone)}")
                return true
            }
        }
        AppLogger.w(CHARGE_TAG, "limite de charge $pct % : aucune voie n'a accepté l'écriture")
        return false
    }

    /**
     * Pourcentage de batterie seul, pour l'automatisation qui le surveille en roulant : une
     * propriété au lieu de la quinzaine que lit [read]. Mêmes garde-fous — hors de 0..100, c'est
     * une lecture ratée, pas une valeur.
     */
    fun soc(): Float? = float(PROP_SOC)?.takeIf { it in 0f..100f }

    /**
     * Puissance qui sort de la batterie, en kW : positive en décharge, négative quand elle se
     * remplit (récupération). Le signe est l'inverse de [EnergySnapshot.powerKw], pensé pour la
     * charge. Deux lectures seulement, pour pouvoir être appelée chaque seconde en roulant.
     * Une tension ou un courant hors de sa plage donne `null` : voir [PowerIntegrator.batteryPowerKw].
     */
    fun dischargeKw(): Float? =
        PowerIntegrator.batteryPowerKw(float(PROP_PACK_VOLTAGE), float(PROP_PACK_CURRENT))

    /**
     * Capacité de la batterie annoncée par le véhicule, en kWh — `null` si la propriété ne répond
     * pas ou rend une valeur invraisemblable.
     *
     * **La MG4 ne la publie pas** : sondée le 2026-09-20 sur SWI133, la propriété rend 0,0. Le
     * réglage manuel reste donc la seule source. La lecture est gardée parce qu'elle ne coûte rien
     * et que les firmwares A9 pourraient, eux, répondre.
     *
     * AOSP la donne en Wh ; certains véhicules la publient déjà en kWh, d'où les deux échelles
     * acceptées. Elle sert de valeur par défaut au réglage de l'onglet Statistiques : c'est mieux
     * qu'un 62 arbitraire, sans priver l'utilisateur du dernier mot — la valeur annoncée peut être
     * la capacité brute, alors que le calcul demande la capacité utile.
     */
    fun batteryCapacityKwh(): Float? {
        // Propriété AOSP et non SAIC : sa zone n'est pas forcément celle des propriétés vendeur,
        // on tente donc les deux, comme la sonde le fait pour tout le reste.
        val brut = float(PROP_BATTERY_CAPACITY) ?: floatAt(PROP_BATTERY_CAPACITY, 0) ?: return null
        val kwh = if (brut > 1000f) brut / 1000f else brut
        return kwh.takeIf { it in StatsSettings.MIN_CAPACITY_KWH..StatsSettings.MAX_CAPACITY_KWH }
    }

    /** Type de prise : voir [ChargeDisplay.type] (alternatif 2 / continu 0 en AC, 4 / 1 en DC). */
    private fun chargeType(): ChargeType? = ChargeDisplay.type(int(PROP_PLUG_AC), int(PROP_PLUG_DC))

    /** Compteur d'énergie : négatif impossible, sentinelle exclue. */
    private fun energy(prop: Int): Float? = float(prop)?.takeIf { it >= 0f }

    private fun float(prop: Int): Float? = floatAt(prop, AREA_GLOBAL)

    private fun floatAt(prop: Int, area: Int): Float? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        val value = runCatching {
            cpm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
                .invoke(cpm, prop, area) as? Float
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
