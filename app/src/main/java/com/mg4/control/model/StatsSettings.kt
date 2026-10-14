package com.mg4.control.model

import kotlin.math.abs

/**
 * Réglages de l'onglet Statistiques. Pur : la persistance vit dans `stats/StatsStore`.
 *
 * [capacityKwh] sert à convertir une différence de pourcentage en kilowattheures — la seule
 * méthode qui marche quand le boîtier a dormi pendant la charge. La MG4 existe en trois batteries
 * ([Battery]), d'où le réglage plutôt qu'une constante.
 */
data class StatsSettings(
    val enabled: Boolean = false,
    val retention: Retention = Retention.MONTHS_3,
    /** Prix du kWh en charge alternative (domicile, en général). */
    val priceAc: Float = 0.187f,
    /** Prix du kWh en charge continue (borne rapide). */
    val priceDc: Float = 0.45f,
    /** Symbole affiché : la langue ne dit pas la monnaie. */
    val currency: String = "€",
    /** Capacité utile de la batterie, en kWh : celle d'une des trois [Battery]. */
    val capacityKwh: Float = DEFAULT_CAPACITY_KWH,
    /** Vrai pour ne plus enregistrer les trajets plus courts que [minTripKm]. */
    val skipShortTrips: Boolean = false,
    val minTripKm: Float = DEFAULT_MIN_TRIP_KM,
) {
    val battery: Battery get() = Battery.nearest(capacityKwh)

    /**
     * Faux pour un trajet que le filtre des petits trajets écarte. La distance jugée est celle que
     * l'écran afficherait ([Trip.distance]) : l'utilisateur doit pouvoir prévoir le résultat.
     */
    fun records(trip: Trip): Boolean = !skipShortTrips || trip.distance >= minTripKm

    /**
     * Les trois batteries de la MG4. La capacité **utile** est celle qui compte : c'est elle que
     * représentent les pourcentages affichés, donc elle qui convertit une recharge en kWh.
     */
    enum class Battery(val nominalKwh: Int, val usableKwh: Float, val chemistry: String) {
        KWH_51(51, 50.8f, "LFP"),
        KWH_64(64, 61.7f, "NMC"),
        KWH_77(77, 74.4f, "NMC");

        companion object {
            /**
             * Batterie la plus proche d'une capacité. Le réglage était autrefois un nombre libre
             * (62 par défaut) : c'est ainsi qu'une valeur déjà enregistrée retrouve sa batterie.
             */
            fun nearest(capacityKwh: Float): Battery =
                entries.minBy { abs(it.usableKwh - capacityKwh) }
        }
    }

    fun priceFor(type: ChargeType?): Float = if (type == ChargeType.DC) priceDc else priceAc

    /** Convertit une différence de pourcentage en kWh. Null si l'une des bornes manque. */
    fun kwhFromSoc(socStart: Float?, socEnd: Float?): Float? {
        if (socStart == null || socEnd == null) return null
        val delta = socEnd - socStart
        return if (delta <= 0f) null else (delta / 100f * capacityKwh).roundTenth()
    }

    enum class Retention(val days: Int) {
        DAYS_30(30),
        MONTHS_3(90),
        MONTHS_6(182),
        YEAR(365),
    }

    companion object {
        /** La batterie 64 kWh, la plus répandue. Voir [Battery] pour les deux autres. */
        const val DEFAULT_CAPACITY_KWH = 61.7f
        const val DEFAULT_MIN_TRIP_KM = 1f
        /** La distance d'un trajet est connue au dixième de kilomètre : inutile de viser plus fin. */
        const val MIN_TRIP_FLOOR_KM = 0.1f
        const val MIN_TRIP_CEILING_KM = 50f
        const val MIN_CAPACITY_KWH = 20f
        const val MAX_CAPACITY_KWH = 120f
        /**
         * Garde-fou contre une faute de frappe, pas une limite d'usage : selon la monnaie un kWh
         * vaut quelques centimes ou plusieurs milliers d'unités (roupie indonésienne, dong).
         */
        const val MAX_PRICE = 100_000f

        fun clampPrice(value: Float): Float = value.coerceIn(0f, MAX_PRICE)
        fun clampMinTrip(value: Float): Float = value.coerceIn(MIN_TRIP_FLOOR_KM, MIN_TRIP_CEILING_KM)
    }
}
